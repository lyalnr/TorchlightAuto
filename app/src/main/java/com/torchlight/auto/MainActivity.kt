package com.torchlight.auto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.torchlight.auto.data.DropRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var pagerAdapter: PagerAdapter
    private var ocrReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private val floatMgr by lazy { FloatWindowManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var logMonitor: LogMonitor? = null
    private var logDropReceiver: BroadcastReceiver? = null
    private var logDebugReceiver: BroadcastReceiver? = null
    private var mapStateReceiver: BroadcastReceiver? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rc = result.resultCode
        val isOk = rc == RESULT_OK
        toast("📥 回调触发 resultCode=$rc RESULT_OK=${RESULT_OK} 匹配=$isOk")
        sendBroadcast(Intent(ScreenCaptureService.ACTION_DEBUG).putExtra("msg", "📥 授权回调 rc=$rc RESULT_OK=${RESULT_OK} data=${result.data != null}"))
        if (isOk && result.data != null) {
            val p1 = pagerAdapter.fragments[0] as Page1Fragment
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("left", p1.cropL)
                putExtra("top", p1.cropT)
                putExtra("right", p1.cropR)
                putExtra("bottom", p1.cropB)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                toast("🚀 Service启动指令已发送")
            } catch (e: Exception) {
                toast("❌ 启动Service失败: ${e.message}")
                Log.e("OCR", "start service failed", e)
            }
        } else {
            toast("录屏权限被拒绝")
            floatMgr.hide()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkDayReset()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabLayout = TabLayout(this)
        viewPager = ViewPager2(this)
        root.addView(tabLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val vpParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
        vpParams.weight = 1f
        root.addView(viewPager, vpParams)
        setContentView(root)

        pagerAdapter = PagerAdapter(this)
        viewPager.adapter = pagerAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "监控"
                1 -> "价格表"
                else -> "今日账单"
            }
        }.attach()

        registerReceivers()
        registerLogReceivers()
        checkPermissions()
        DropRepository.listeners.add {
            runOnUiThread {
                floatMgr.update()
                (pagerAdapter.fragments[2] as? Page3Fragment)?.refresh()
            }
        }
    }

    private fun checkDayReset() {
        val prefs = getSharedPreferences("app_data", Context.MODE_PRIVATE)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val last = prefs.getString("last_active_date", "")
        if (last != today) {
            DropRepository.clear()
            prefs.edit().putString("last_active_date", today).apply()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun registerReceivers() {
        ocrReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val name = i?.getStringExtra(ScreenCaptureService.EXTRA_NAME) ?: return
                val price = i.getFloatExtra(ScreenCaptureService.EXTRA_PRICE, -1f)
                val color = i.getStringExtra(ScreenCaptureService.EXTRA_COLOR) ?: "未知"
                (pagerAdapter.fragments[2] as? Page3Fragment)?.addDrop(name, price, color)
            }
        }
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val msg = i?.getStringExtra("msg") ?: return
                (pagerAdapter.fragments[0] as? Page1Fragment)?.appendLog(msg)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ocrReceiver, IntentFilter(ScreenCaptureService.ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
            registerReceiver(debugReceiver, IntentFilter(ScreenCaptureService.ACTION_DEBUG), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ocrReceiver, IntentFilter(ScreenCaptureService.ACTION_RESULT))
            registerReceiver(debugReceiver, IntentFilter(ScreenCaptureService.ACTION_DEBUG))
        }
    }

    private fun registerLogReceivers() {
        logDropReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val name = i?.getStringExtra("name") ?: return
                val price = i.getFloatExtra("price", 0f)
                val qty = i.getIntExtra("quantity", 1)
                (pagerAdapter.fragments[2] as? Page3Fragment)?.refresh()
            }
        }
        logDebugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val msg = i?.getStringExtra("msg") ?: return
                (pagerAdapter.fragments[0] as? Page1Fragment)?.appendLog(msg)
            }
        }
        mapStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val inMap = i?.getBooleanExtra("inMap", false) ?: return
                (pagerAdapter.fragments[0] as? Page1Fragment)?.appendLog(
                    if (inMap) "🗺️ 进入新地图" else "🏠 返回城镇"
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logDropReceiver, IntentFilter(LogMonitor.ACTION_LOG_DROP), ContextCompat.RECEIVER_NOT_EXPORTED)
            registerReceiver(logDebugReceiver, IntentFilter(LogMonitor.ACTION_LOG_DEBUG), ContextCompat.RECEIVER_NOT_EXPORTED)
            registerReceiver(mapStateReceiver, IntentFilter(LogMonitor.ACTION_MAP_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logDropReceiver, IntentFilter(LogMonitor.ACTION_LOG_DROP))
            registerReceiver(logDebugReceiver, IntentFilter(LogMonitor.ACTION_LOG_DEBUG))
            registerReceiver(mapStateReceiver, IntentFilter(LogMonitor.ACTION_MAP_STATE))
        }
    }

    fun startOCR() {
        if (!Settings.canDrawOverlays(this)) {
            toast("需要悬浮窗权限")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        DropRepository.clear()
        floatMgr.show()
        sendBroadcast(Intent(ScreenCaptureService.ACTION_DEBUG).putExtra("msg", "🔔 诊断: MainActivity广播测试"))
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    fun stopOCR() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        floatMgr.hide()
        DropRepository.clear()
        (pagerAdapter.fragments[2] as? Page3Fragment)?.refresh()
    }

    fun unlockFloatWindow() {
        floatMgr.unlock()
    }

    fun startLogMonitor() {
        if (logMonitor == null) logMonitor = LogMonitor(this)
        logMonitor?.start()
    }

    fun stopLogMonitor() {
        logMonitor?.stop()
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { ocrReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { logDropReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { logDebugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { mapStateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        floatMgr.hide()
    }

    inner class PagerAdapter(act: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(act) {
        val fragments = listOf(Page1Fragment(), Page2Fragment(), Page3Fragment())
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int) = fragments[position]
    }
}

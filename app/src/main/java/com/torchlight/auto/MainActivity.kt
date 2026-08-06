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
import android.widget.LinearLayout
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        com.torchlight.auto.util.CrashLogger.init(this)
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
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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

    fun startOCR() {
        if (!Settings.canDrawOverlays(this)) {
            toast("需要悬浮窗权限")
            return
        }
        DropRepository.clear()
        floatMgr.show()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), 999)
    }

    fun stopOCR() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        floatMgr.hide()
        DropRepository.clear()
        (pagerAdapter.fragments[2] as? Page3Fragment)?.refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 999 && resultCode == RESULT_OK && data != null) {
            val p1 = pagerAdapter.fragments[0] as Page1Fragment
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("left", p1.cropL)
                putExtra("top", p1.cropT)
                putExtra("right", p1.cropR)
                putExtra("bottom", p1.cropB)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            toast("录屏权限被拒绝")
            floatMgr.hide()
        }
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { ocrReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        floatMgr.hide()
    }

    inner class PagerAdapter(act: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(act) {
        val fragments = listOf(Page1Fragment(), Page2Fragment(), Page3Fragment())
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int) = fragments[position]
    }
}

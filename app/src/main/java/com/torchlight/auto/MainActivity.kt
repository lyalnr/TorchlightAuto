package com.torchlight.auto

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView
    private var ocrReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var previewView: ImageView? = null
    private var floatContainer: LinearLayout? = null

    private val REQ_MEDIA_PROJECTION = 999
    private val REQ_NOTIFICATION = 100

    // 识别区域（百分比）
    private var cropLeft = 0.55f
    private var cropTop = 0.08f
    private var cropRight = 0.95f
    private var cropBottom = 0.42f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        registerReceivers()
        checkPermissions()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 标题
        val tvTitle = TextView(this).apply {
            text = "🎮 火炬之光掉落识别 (OCR版)\n"
            textSize = 16f
        }
        root.addView(tvTitle)

        // 区域调整按钮行
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeBtn("区域↑") { cropTop -= 0.02f; cropBottom -= 0.02f; showArea() })
        row1.addView(makeBtn("区域↓") { cropTop += 0.02f; cropBottom += 0.02f; showArea() })
        row1.addView(makeBtn("区域←") { cropLeft -= 0.02f; cropRight -= 0.02f; showArea() })
        row1.addView(makeBtn("区域→") { cropLeft += 0.02f; cropRight += 0.02f; showArea() })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeBtn("放大") {
            cropLeft -= 0.03f; cropTop -= 0.03f
            cropRight += 0.03f; cropBottom += 0.03f
            clampArea(); showArea()
        })
        row2.addView(makeBtn("缩小") {
            cropLeft += 0.03f; cropTop += 0.03f
            cropRight -= 0.03f; cropBottom -= 0.03f
            clampArea(); showArea()
        })
        row2.addView(makeBtn("默认区域") {
            cropLeft = 0.55f; cropTop = 0.08f
            cropRight = 0.95f; cropBottom = 0.42f
            showArea()
        })
        root.addView(row2)

        val tvArea = TextView(this).apply {
            text = "当前区域: 右上方 (可微调)"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(tvArea)

        val btnStart = Button(this).apply {
            text = "▶ 开始录屏识别"
            setOnClickListener { startOCR() }
        }
        root.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "⏹ 停止"
            setOnClickListener { stopOCR() }
        }
        root.addView(btnStop)

        val tvHint = TextView(this).apply {
            text = "\n📋 识别结果："
            textSize = 14f
        }
        root.addView(tvHint)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        tvLogs = TextView(this).apply {
            text = "等待启动...\n"
            textSize = 12f
            setTextIsSelectable(true)
        }
        scrollView.addView(tvLogs)
        root.addView(scrollView)

        setContentView(root)
    }

    private fun makeBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun clampArea() {
        cropLeft = cropLeft.coerceIn(0f, 0.9f)
        cropTop = cropTop.coerceIn(0f, 0.9f)
        cropRight = cropRight.coerceIn(0.1f, 1f)
        cropBottom = cropBottom.coerceIn(0.1f, 1f)
        if (cropRight <= cropLeft) cropRight = cropLeft + 0.1f
        if (cropBottom <= cropTop) cropBottom = cropTop + 0.1f
    }

    private fun showArea() {
        appendLog("📐 区域: L=${(cropLeft*100).toInt()}% T=${(cropTop*100).toInt()}% R=${(cropRight*100).toInt()}% B=${(cropBottom*100).toInt()}%")
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun registerReceivers() {
        ocrReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_TEXT) ?: return
                appendLog("🎯 $text")
                showFloatPreview("识别: $text")
            }
        }
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra("msg") ?: return
                appendLog(msg)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ocrReceiver, IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registerReceiver(debugReceiver, IntentFilter(ScreenCaptureService.ACTION_DEBUG),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(ocrReceiver, IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT))
            registerReceiver(debugReceiver, IntentFilter(ScreenCaptureService.ACTION_DEBUG))
        }
    }

    private fun startOCR() {
        if (!Settings.canDrawOverlays(this)) {
            appendLog("❌ 需要悬浮窗权限")
            return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        appendLog("📡 请允许录屏权限...")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                appendLog("❌ 录屏权限被拒绝")
                return
            }
            appendLog("✅ 录屏权限已获取，启动服务...")
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("left", cropLeft)
                putExtra("top", cropTop)
                putExtra("right", cropRight)
                putExtra("bottom", cropBottom)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            showFloatPreview("OCR监控中...")
        }
    }

    private fun stopOCR() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        removeFloatPreview()
        appendLog(">>> 已停止")
    }

    private fun showFloatPreview(text: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (floatContainer == null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

            floatContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xCC000000.toInt())
                setPadding(16, 16, 16, 16)
            }

            previewView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(300, 200)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            floatContainer?.addView(previewView)

            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
            }
            floatContainer?.addView(tv)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20; y = 100
            }
            wm.addView(floatContainer, params)
        }
        (floatContainer?.getChildAt(1) as? TextView)?.text = text
    }

    private fun removeFloatPreview() {
        floatContainer?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            floatContainer = null
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLogs.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatPreview()
        try { ocrReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}

package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.regex.Pattern

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_RESULT = "com.torchlight.auto.OCR_RESULT"
        const val ACTION_DEBUG = "com.torchlight.auto.OCR_DEBUG"
        const val ACTION_REGION_SELECTED = "com.torchlight.auto.REGION_SELECTED"
        const val EXTRA_NAME = "name"
        const val EXTRA_PRICE = "price"
        const val EXTRA_COLOR = "color"

        private const val STATE_DETECTING = 0
        private const val STATE_SELECTING = 1
        private const val STATE_OCR = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var w = 0; private var h = 0; private var density = 0
    private var serviceState = STATE_DETECTING

    var cropL = 0.55f; var cropT = 0.08f; var cropR = 0.95f; var cropB = 0.42f

    private val lastSeenTime = mutableMapOf<String, Long>()
    private var selectorWindow: RegionSelectorWindow? = null

    private val priceMap = mapOf(
        "初火源质" to 1.0,
        "灰烬" to 0.1,
        "记忆碎片" to 0.5,
        "落雪之翼" to 5.0,
        "幽邃之翼" to 10.0,
        "永恒之翼" to 50.0
    )

    private val regionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != ACTION_REGION_SELECTED) return
            cropL = i.getFloatExtra("left", 0.55f)
            cropT = i.getFloatExtra("top", 0.08f)
            cropR = i.getFloatExtra("right", 0.95f)
            cropB = i.getFloatExtra("bottom", 0.42f)

            getSharedPreferences("ocr_settings", Context.MODE_PRIVATE).edit().apply {
                putFloat("cropL", cropL); putFloat("cropT", cropT)
                putFloat("cropR", cropR); putFloat("cropB", cropB)
                apply()
            }

            serviceState = STATE_OCR
            sendDebug("✅ 区域已保存: \${(cropL*100).toInt()}%/\${(cropT*100).toInt()}%/\${(cropR*100).toInt()}%/\${(cropB*100).toInt()}%")
            sendDebug("🚀 进入持续识别模式")
            handler.post(captureRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Toast.makeText(this, "🔔 Service.onCreate", Toast.LENGTH_SHORT).show()
        createChannel()
        startForeground(1002, createNotification("正在检测游戏画面..."))
        registerReceiver(regionReceiver, IntentFilter(ACTION_REGION_SELECTED))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Toast.makeText(this, "📥 onStartCommand", Toast.LENGTH_SHORT).show()
        if (running) {
            sendDebug("⚠️ 服务已在运行")
            return START_STICKY
        }

        val rc = intent?.getIntExtra("resultCode", -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }

        if (rc == -1 || data == null) {
            sendDebug("❌ 录屏数据无效")
            stopSelf()
            return START_STICKY
        }

        running = true
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(rc, data)
        if (projection == null) {
            sendDebug("❌ MediaProjection失败")
            stopSelf()
            return START_STICKY
        }

        setupCapture()
        serviceState = STATE_DETECTING
        handler.post(detectRunnable)
        return START_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        w = dm.widthPixels
        h = dm.heightPixels
        density = dm.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private val detectRunnable = object : Runnable {
        override fun run() {
            if (!running || serviceState != STATE_DETECTING) return
            captureAndDetectMainScreen()
            handler.postDelayed(this, 800)
        }
    }

    private fun captureAndDetectMainScreen() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                sendDebug("⏸️ 未获取到图像")
                return
            }

            val bmp = imageToBitmap(image)
            val input = InputImage.fromBitmap(bmp, 0)

            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .process(input)
                .addOnSuccessListener { result ->
                    val text = result.text
                    sendDebug("🔍 检测: \${text.take(50)}")

                    if (isMainScreen(text)) {
                        sendDebug("🎮 检测到游戏主页面！等待框选...")
                        serviceState = STATE_SELECTING
                        handler.removeCallbacks(detectRunnable)
                        selectorWindow = RegionSelectorWindow(this@ScreenCaptureService)
                        selectorWindow?.show { l, t, r, b ->
                            sendBroadcast(Intent(ACTION_REGION_SELECTED).apply {
                                putExtra("left", l); putExtra("top", t)
                                putExtra("right", r); putExtra("bottom", b)
                            })
                        }
                    }
                    bmp.recycle()
                }
                .addOnFailureListener {
                    bmp.recycle()
                    sendDebug("❌ OCR检测失败")
                }
        } catch (e: Exception) {
            sendDebug("❌ 检测异常: \${e.message}")
        } finally {
            image?.close()
        }
    }

    private fun isMainScreen(text: String): Boolean {
        val keywords = listOf("开始游戏", "点击开始游戏", "切换角色", "守夜人", "原初之火", "赛季", "甘草前调")
        return keywords.any { text.contains(it) }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running || serviceState != STATE_OCR) return
            captureAndOCR()
            val interval = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
                .getInt("ocr_interval", 350).toLong()
            handler.postDelayed(this, interval)
        }
    }

    private fun captureAndOCR() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                sendDebug("⏸️ 未获取到图像")
                return
            }

            val bmp = imageToBitmap(image)
            val iw = bmp.width; val ih = bmp.height

            val cx = (iw * cropL).toInt()
            val cy = (ih * cropT).toInt()
            val cw = ((iw * cropR).toInt() - cx).coerceAtLeast(80)
            val ch = ((ih * cropB).toInt() - cy).coerceAtLeast(40)

            sendDebug("✂️ 裁剪: \${cx},\${cy} \${cw}x\${ch}")

            if (cw <= 0 || ch <= 0) { bmp.recycle(); return }

            val cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
            bmp.recycle()
            doOCR(cropped)
        } catch (e: Exception) {
            sendDebug("❌ 截图异常: \${e.message}")
        } finally {
            image?.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val buf = image.planes[0].buffer
        val ps = image.planes[0].pixelStride
        val rs = image.planes[0].rowStride
        val iw = image.width; val ih = image.height
        val off = (rs - ps * iw) / ps
        val bmp = Bitmap.createBitmap(iw + off, ih, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun doOCR(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(input)
            .addOnSuccessListener { result ->
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val txt = line.text.trim()
                        if (txt.isEmpty()) continue
                        val color = detectColor(bitmap, line.boundingBox)
                        val (name, price) = processText(txt, color)
                        if (name != null && price > 0) {
                            val key = "\$name-\$color"
                            val now = System.currentTimeMillis()
                            val last = lastSeenTime.getOrDefault(key, 0L)
                            val cool = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
                                .getInt("ocr_cooldown", 500).toLong()
                            if (now - last > cool) {
                                lastSeenTime[key] = now
                                sendResult(name, price, color)
                            }
                        }
                    }
                }
                bitmap.recycle()
            }
            .addOnFailureListener {
                bitmap.recycle()
                sendDebug("❌ OCR识别失败")
            }
    }

    private fun detectColor(bitmap: Bitmap, box: android.graphics.Rect?): String {
        if (box == null) return "white"
        val w = bitmap.width; val h = bitmap.height
        val left = box.left.coerceIn(0, w - 1)
        val top = box.top.coerceIn(0, h - 1)
        val right = box.right.coerceIn(0, w - 1)
        val bottom = box.bottom.coerceIn(0, h - 1)
        if (right <= left || bottom <= top) return "white"

        var orange = 0; var purple = 0; var green = 0; var total = 0
        val step = if (1 > (right - left) / 10) 1 else (right - left) / 10

        for (x in left until right step step) {
            for (y in top until bottom step step) {
                val px = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(px)
                val g = android.graphics.Color.green(px)
                val b = android.graphics.Color.blue(px)

                if (r > 200 && g in 100..180 && b < 100) orange++
                else if (r > 150 && g < 100 && b > 150) purple++
                else if (r < 100 && g > 150 && b < 100) green++
                total++
            }
        }
        if (total == 0) return "white"
        return when {
            orange * 3 > total -> "orange"
            purple * 3 > total -> "purple"
            green * 3 > total -> "green"
            else -> "white"
        }
    }

    private fun processText(text: String, color: String): Pair<String?, Double> {
        val p = Pattern.compile("(.+?)(\\d+\\.?\\d*)")
        val m = p.matcher(text)
        if (m.find()) {
            val name = m.group(1)?.trim()
            val qty = m.group(2)?.toDoubleOrNull() ?: 1.0
            val base = priceMap[name] ?: 0.0
            return name to (base * qty)
        }
        return null to 0.0
    }

    private fun sendResult(name: String, price: Double, color: String) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_PRICE, price)
            putExtra(EXTRA_COLOR, color)
        })
    }

    private fun sendDebug(msg: String) {
        sendBroadcast(Intent(ACTION_DEBUG).apply {
            putExtra("msg", msg)
        })
    }

    private fun createNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "torch_channel")
            .setContentTitle("火炬助手")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "torch_channel", "录屏服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(regionReceiver)
        selectorWindow?.hide()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        sendDebug("🔴 服务已停止")
    }
}

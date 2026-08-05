package com.torchlight.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0

    // 识别区域（百分比 0.0~1.0）
    var cropLeft = 0.55f
    var cropTop = 0.08f
    var cropRight = 0.95f
    var cropBottom = 0.42f

    companion object {
        private const val CHANNEL_ID = "ocr_channel"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_OCR_RESULT = "com.torchlight.auto.OCR_RESULT"
        const val ACTION_DEBUG = "com.torchlight.auto.OCR_DEBUG"
        const val ACTION_PREVIEW = "com.torchlight.auto.OCR_PREVIEW"
        const val EXTRA_TEXT = "text"
        const val EXTRA_BITMAP = "bitmap"

        val WHITELIST = listOf(
            "异界回响", "能量核心", "猫眼石", "破空", "传奇", "稀有", "史诗",
            "通货", "装备", "武器", "护甲", "饰品", "吊坠", "戒指", "腰带",
            "头盔", "手套", "靴子", "法杖", "弓箭", "长剑", "盾牌"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("OCR 监控准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true

        // 读取区域参数
        intent?.let {
            cropLeft = it.getFloatExtra("left", 0.55f)
            cropTop = it.getFloatExtra("top", 0.08f)
            cropRight = it.getFloatExtra("right", 0.95f)
            cropBottom = it.getFloatExtra("bottom", 0.42f)
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == -1 || data == null) {
            sendDebug("❌ 录屏权限数据无效")
            stopSelf()
            return START_STICKY
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            sendDebug("❌ MediaProjection 获取失败")
            stopSelf()
            return START_STICKY
        }

        setupCapture()
        return START_STICKY
    }

    private fun setupCapture() {
        val display = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi

        // K50 Pro 2K屏优化：如果分辨率太高，降采样以提升OCR速度
        val maxDim = 2560
        var w = screenWidth
        var h = screenHeight
        if (w > maxDim || h > maxDim) {
            val scale = maxDim.toFloat() / maxOf(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        sendDebug("✅ 录屏已启动 ${screenWidth}x$screenHeight → ${w}x$h")
        sendDebug("🔍 识别区域: L=${(cropLeft*100).toInt()}% T=${(cropTop*100).toInt()}% R=${(cropRight*100).toInt()}% B=${(cropBottom*100).toInt()}%")

        // 每 400ms 截一次图
        handler.postDelayed(captureRunnable, 800)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            captureAndOCR()
            handler.postDelayed(this, 400)
        }
    }

    private fun captureAndOCR() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return

            val buffer: ByteBuffer = image.planes[0].buffer
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            val w = image.width
            val h = image.height
            val offset = (rowStride - pixelStride * w) / pixelStride

            val bitmap = Bitmap.createBitmap(w + offset, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪目标区域
            val cropX = (w * cropLeft).toInt()
            val cropY = (h * cropTop).toInt()
            val cropW = ((w * cropRight).toInt() - cropX).coerceAtLeast(100)
            val cropH = ((h * cropBottom).toInt() - cropY).coerceAtLeast(50)

            if (cropW <= 0 || cropH <= 0) {
                image.close()
                return
            }

            val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
            bitmap.recycle()

            // 发送预览图
            sendPreview(cropped)

            // OCR
            doOCR(cropped)

        } catch (e: Exception) {
            Log.e("ScreenCapture", "capture error", e)
        } finally {
            image?.close()
        }
    }

    private fun doOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val fullText = result.text.trim()
                if (fullText.isEmpty()) return@addOnSuccessListener

                // 检查是否包含白名单关键词
                val matched = WHITELIST.filter { fullText.contains(it) }
                if (matched.isNotEmpty()) {
                    sendDebug("🎯 OCR识别: $fullText")
                    sendResult(fullText)
                }
            }
            .addOnFailureListener { e ->
                Log.e("ScreenCapture", "OCR failed", e)
            }
    }

    private fun sendResult(text: String) {
        sendBroadcast(Intent(ACTION_OCR_RESULT).putExtra(EXTRA_TEXT, text))
    }

    private fun sendDebug(msg: String) {
        Log.d("ScreenCapture", msg)
        sendBroadcast(Intent(ACTION_DEBUG).putExtra("msg", msg))
    }

    private fun sendPreview(bitmap: Bitmap) {
        // 压缩预览图发送给 Activity 显示
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            val intent = Intent(ACTION_PREVIEW)
            // Bitmap 不能直接放 Intent，这里用全局变量或 EventBus 更好
            // 简化为发送调试信息
            sendBroadcast(Intent(ACTION_DEBUG).putExtra("msg", "[预览] 截图 ${bitmap.width}x${bitmap.height}"))
            scaled.recycle()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OCR录屏服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("火炬之光掉落识别")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

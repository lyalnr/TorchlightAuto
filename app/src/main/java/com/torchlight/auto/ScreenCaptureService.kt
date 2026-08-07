package com.torchlight.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.torchlight.auto.data.AppDatabase
import com.torchlight.auto.data.DropRepository
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var w = 0; private var h = 0; private var density = 0
    var cropL = 0.55f; var cropT = 0.08f; var cropR = 0.95f; var cropB = 0.42f

    private val lastSeenTime = mutableMapOf<String, Long>()

    companion object {
        const val ACTION_RESULT = "com.torchlight.auto.OCR_RESULT"
        const val ACTION_DEBUG = "com.torchlight.auto.OCR_DEBUG"
        const val EXTRA_NAME = "name"
        const val EXTRA_PRICE = "price"
        const val EXTRA_COLOR = "color"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1002, createNotification("OCR准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            sendDebug("⚠️ 服务已在运行")
            return START_STICKY
        }
        running = true
        intent?.let {
            cropL = it.getFloatExtra("left", 0.55f); cropT = it.getFloatExtra("top", 0.08f)
            cropR = it.getFloatExtra("right", 0.95f); cropB = it.getFloatExtra("bottom", 0.42f)
        }
        val rc = intent?.getIntExtra("resultCode", -1) ?: -1

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        sendDebug("📥 onStartCommand rc=$rc data=${data != null}")

        if (rc == -1 || data == null) {
            sendDebug("❌ 录屏数据无效 rc=$rc data=${data != null}")
            stopSelf()
            return START_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(rc, data)
        if (projection == null) {
            sendDebug("❌ MediaProjection失败")
            stopSelf()
            return START_STICKY
        }
        setupCapture()
        return START_STICKY
    }

    private fun setupCapture() {
        try {
            // 修复1：用 DisplayManager 获取真实屏幕分辨率，避免 Service 里 WindowMetrics 不准
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)

            w = metrics.widthPixels
            h = metrics.heightPixels
            density = metrics.densityDpi

            sendDebug("📱 屏幕: ${w}x$h dpi=$density")

            var rw = w; var rh = h
            if (rw > 2560 || rh > 2560) {
                val maxPx = if (rw > rh) rw else rh
                val s = 2560f / maxPx
                rw = (rw * s).toInt(); rh = (rh * s).toInt()
            }

            sendDebug("🖼️ ImageReader: ${rw}x$rh")

            imageReader = ImageReader.newInstance(rw, rh, android.graphics.PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay("Cap", rw, rh, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)

            if (virtualDisplay == null) {
                sendDebug("❌ VirtualDisplay 创建失败")
                stopSelf()
                return
            }

            sendDebug("✅ 录屏启动成功")
            handler.postDelayed(captureRunnable, 1200)
        } catch (e: Exception) {
            sendDebug("❌ setupCapture 异常: ${e.message}")
            Log.e("OCR", "setup error", e)
            stopSelf()
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            captureAndOCR()
            val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
            val interval = prefs.getInt("ocr_interval", 350).toLong()
            handler.postDelayed(this, interval)
        }
    }

    private fun captureAndOCR() {
        val reader = imageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                // 修复2：null 图像也要记录，否则完全不知道发生了什么
                sendDebug("⏸️ 未获取到图像")
                return
            }
            sendDebug("📸 获取图像 ${image.width}x${image.height}")

            val buf: ByteBuffer = image.planes[0].buffer
            val ps = image.planes[0].pixelStride
            val rs = image.planes[0].rowStride
            val iw = image.width; val ih = image.height
            val off = (rs - ps * iw) / ps
            val bmp = Bitmap.createBitmap(iw + off, ih, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)

            val cx = (iw * cropL).toInt(); val cy = (ih * cropT).toInt()
            val cw = ((iw * cropR).toInt() - cx).coerceAtLeast(80)
            val ch = ((ih * cropB).toInt() - cy).coerceAtLeast(40)
            sendDebug("✂️ 裁剪: ${cx},${cy} ${cw}x${ch}")

            if (cw <= 0 || ch <= 0) { bmp.recycle(); return }
            val cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
            bmp.recycle()
            doOCR(cropped)
        } catch (e: Exception) {
            Log.e("OCR", "capture error", e)
            sendDebug("❌ 截图异常: ${e.message}")
        } finally {
            image?.close()
        }
    }

    private fun doOCR(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .process(input)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks
                sendDebug("🔍 OCR识别到 ${blocks.size} 个文字块")
                if (blocks.isEmpty()) {
                    sendDebug("📝 文字块为空，未识别到内容")
                    bitmap.recycle()
                    return@addOnSuccessListener
                }
                for (block in blocks) {
                    val text = block.text.trim()
                    if (text.isEmpty()) continue
                    val color = detectColor(bitmap, block.boundingBox)
                    sendDebug("📝 识别: [$text] 颜色=$color")
                    processText(text, color)
                }
                bitmap.recycle()
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                sendDebug("❌ OCR引擎失败: ${e.message}")
            }
    }

    private fun detectColor(bitmap: Bitmap, box: android.graphics.Rect?): String {
        if (box == null) return "未知"
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        if (cx < 0 || cy < 0 || cx >= bitmap.width || cy >= bitmap.height) return "未知"
        val px = bitmap.getPixel(cx, cy)
        val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
        return when {
            r > 200 && g < 80 && b < 80 -> "红色"
            r > 200 && g > 180 && b < 80 -> "金色"
            r > 180 && g < 100 && b > 180 -> "紫色"
            r < 80 && g < 120 && b > 180 -> "蓝色"
            r > 200 && g > 200 && b > 200 -> "白色"
            else -> "未知"
        }
    }

    private fun processText(text: String, color: String) {
        try {
            val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
            val cooldown = prefs.getInt("recognition_cooldown", 500).toLong()

            val dao = AppDatabase.getDatabase(this).itemDao()
            val allItems = dao.getAll().filter { it.enabled }
            val matched = allItems.filter { text.contains(it.name) }.maxByOrNull { it.name.length }

            if (matched != null) {
                val now = System.currentTimeMillis()
                val last = lastSeenTime[matched.name] ?: 0
                if (now - last < cooldown) {
                    sendDebug("⏳ [${matched.name}] 冷却中")
                    return
                }
                lastSeenTime[matched.name] = now

                val allowedColors = matched.enabledColors.split(",").filter { it.isNotBlank() }.toSet()
                if (allowedColors.isNotEmpty() && color != "未知" && color !in allowedColors) {
                    sendDebug("🚫 [${matched.name}] 颜色不匹配: $color (允许: $allowedColors)")
                    return
                }

                DropRepository.addDrop(matched.name, matched.price, color)
                sendResult(matched.name, matched.price, color)
                sendDebug("🎯 记录: ${matched.name}(${color})")
            } else {
                val newItem = com.torchlight.auto.data.ItemEntity(
                    name = text, price = -1f, enabled = true,
                    enabledColors = "红色,金色,紫色,蓝色,白色"
                )
                dao.insert(newItem)
                DropRepository.addDrop(text, -1f, color)
                sendResult(text, -1f, color)
                sendDebug("🆕 新物品: $text ($color)")
            }
        } catch (e: Exception) {
            sendDebug("❌ processText异常: ${e.message}")
            Log.e("OCR", "process error", e)
        }
    }

    private fun sendResult(name: String, price: Float, color: String) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_NAME, name); putExtra(EXTRA_PRICE, price); putExtra(EXTRA_COLOR, color)
        })
    }

    private fun sendDebug(msg: String) {
        Log.d("OCR", msg)
        sendBroadcast(Intent(ACTION_DEBUG).putExtra("msg", msg))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel("ocr", "OCR录屏", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, "ocr")
        .setContentTitle("火炬之光掉落识别").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_gallery).setOngoing(true).build()

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

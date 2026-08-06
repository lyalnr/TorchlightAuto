package com.torchlight.auto
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import com.torchlight.auto.data.AppDatabase
import com.torchlight.auto.data.DropRepository
import com.torchlight.auto.data.ItemEntity
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var w = 0; private var h = 0; private var density = 0
    var cropL = 0.55f; var cropT = 0.08f; var cropR = 0.95f; var cropB = 0.42f

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
        if (running) return START_STICKY
        running = true
        intent?.let {
            cropL = it.getFloatExtra("left", 0.55f); cropT = it.getFloatExtra("top", 0.08f)
            cropR = it.getFloatExtra("right", 0.95f); cropB = it.getFloatExtra("bottom", 0.42f)
        }
        val rc = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (rc == -1 || data == null) { sendDebug("❌ 录屏数据无效"); stopSelf(); return START_STICKY }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(rc, data)
        if (projection == null) { sendDebug("❌ MediaProjection失败"); stopSelf(); return START_STICKY }
        setupCapture()
        return START_STICKY
    }

    private fun setupCapture() {
        val dm = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        val m = android.util.DisplayMetrics(); dm.getRealMetrics(m)
        w = m.widthPixels; h = m.heightPixels; density = m.densityDpi
        var rw = w; var rh = h
        if (rw > 2560 || rh > 2560) {
            val maxPx = if (rw > rh) rw else rh
            val s = 2560f / maxPx; rw = (rw * s).toInt(); rh = (rh * s).toInt()
        }
        imageReader = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay("Cap", rw, rh, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
        sendDebug("✅ 录屏启动 ${w}x$h → ${rw}x$rh")
        handler.postDelayed(captureRunnable, 600)
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
            image = reader.acquireLatestImage() ?: return
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
            if (cw <= 0 || ch <= 0) { bmp.recycle(); return }
            val cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
            bmp.recycle()
            doOCR(cropped)
        } catch (e: Exception) {
            Log.e("OCR", "cap", e)
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
                for (block in blocks) {
                    val text = block.text.trim()
                    if (text.isEmpty()) continue
                    val color = detectColor(bitmap, block.boundingBox)
                    processText(text, color)
                }
                bitmap.recycle()
            }
            .addOnFailureListener { bitmap.recycle() }
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
        val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
        val enabledColors = prefs.getStringSet("enabled_colors", setOf("红色","金色","紫色","蓝色")) ?: setOf("红色","金色","紫色","蓝色")
        if (color != "未知" && color !in enabledColors) {
            sendDebug("🚫 颜色过滤跳过: $text ($color)")
            return
        }
        val dao = AppDatabase.getDatabase(this).itemDao()
        val allItems = dao.getAll().filter { it.enabled }
        val matched = allItems.filter { text.contains(it.name) }.maxByOrNull { it.name.length }
        if (matched != null) {
            val colorMatch = matched.color == "未知" || matched.color == color || color == "未知"
            if (colorMatch) {
                DropRepository.addDrop(matched.name, matched.price, color)
                sendResult(matched.name, matched.price, color)
                sendDebug("🎯 ${matched.name}(${color}) x${DropRepository.todayDrops.find{it.name==matched.name}?.quantity ?: 1}")
            }
        } else {
            val newItem = ItemEntity(name = text, price = -1f, color = color, enabled = true)
            dao.insert(newItem)
            DropRepository.addDrop(text, -1f, color)
            sendResult(text, -1f, color)
            sendDebug("🆕 新物品: $text ($color) - 请在价格表设置价格")
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
        running = false; handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release(); imageReader?.close(); projection?.stop()
        super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}

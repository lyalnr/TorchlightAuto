package com.torchlight.auto.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.torchlight.auto.MainActivity
import com.torchlight.auto.R
import com.torchlight.auto.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "torchlight_ocr_channel"
        const val NOTIFICATION_ID = 1

        val BLACKLIST = setOf(
            "点击", "确定", "取消", "返回", "设置", "背包", "地图",
            "任务", "技能", "商店", "退出", "开始", "暂停", "继续",
            "等级", "经验", "金币", "生命", "法力", "攻击", "防御"
        )
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var db: AppDatabase
    private var priceMap: Map<String, Float> = emptyMap()
    private var enabledColorsMap: Map<String, String> = emptyMap()

    private data class Record(val cx: Int, val cy: Int, val time: Long)
    private val recentRecords = ConcurrentHashMap<String, Record>()

    private var screenWidth = 0
    private var screenHeight = 0
    private var ocrInterval = 350L
    private var recognitionCooldown = 500L
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        refreshPriceTable()
    }

    private fun refreshPriceTable() {
        scope.launch {
            val items = db.itemDao().getAllEnabled()
            priceMap = items.associate { it.name to it.price }
            enabledColorsMap = items.associate { it.name to it.enabledColors }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            startCapture()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "火炬助手录屏", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("火炬助手")
            .setContentText("正在监控掉落...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .set(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .build()
    }

    private fun startCapture() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
        ocrInterval = prefs.getLong("ocr_interval", 350L)
        recognitionCooldown = prefs.getLong("recognition_cooldown", 500L)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2)

        mediaProjection?.let { mp ->
            virtualDisplay = mp.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            isRunning = true
            handler.post(captureRunnable)
        }
    }

    private val captureRunnable = Runnable {
        if (!isRunning) return@Runnable
        captureOnce()
        handler.postDelayed(captureRunnable, ocrInterval)
    }

    private fun captureOnce() {
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        }
        if (image == null) return

        try {
            val bitmap = imageToBitmap(image) ?: return

            val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
            val cropL = prefs.getFloat("cropL", 0f)
            val cropT = prefs.getFloat("cropT", 0f)
            val cropR = prefs.getFloat("cropR", 1f)
            val cropB = prefs.getFloat("cropB", 1f)

            val left = (bitmap.width * cropL).toInt().coerceIn(0, bitmap.width)
            val top = (bitmap.height * cropT).toInt().coerceIn(0, bitmap.height)
            val right = (bitmap.width * cropR).toInt().coerceIn(left, bitmap.width)
            val bottom = (bitmap.height * cropB).toInt().coerceIn(top, bitmap.height)

            val target = if (right - left > 10 && bottom - top > 10) {
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            } else {
                bitmap
            }

            runOcr(target)

            if (target !== bitmap) target.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "captureOnce error", e)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w

        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun runOcr(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(input)
            .addOnSuccessListener { result -> processOcrResult(result, bitmap) }
            .addOnFailureListener { e -> Log.e(TAG, "OCR failed", e) }
    }

    private fun processOcrResult(visionText: com.google.mlkit.vision.text.Text, bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        val names = mutableListOf<String>()
        val prices = mutableListOf<Float>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val raw = line.text.trim()
                if (raw.isEmpty()) continue

                if (BLACKLIST.any { raw.contains(it) }) continue

                val itemName = priceMap.keys.find { raw.contains(it) } ?: continue
                val price = priceMap[itemName] ?: continue
                val allowedColors = enabledColorsMap[itemName] ?: "红色,金色,紫色,蓝色"

                val box = line.boundingBox ?: continue
                val color = detectColor(bitmap, box)
                if (!allowedColors.contains(color)) continue

                val cx = (box.left + box.right) / 2
                val cy = (box.top + box.bottom) / 2
                val key = "${itemName}_$color"
                val last = recentRecords[key]
                if (last != null) {
                    val dist = hypot((cx - last.cx).toDouble(), (cy - last.cy).toDouble())
                    if (dist < 80 && (now - last.time) < recognitionCooldown) continue
                }
                recentRecords[key] = Record(cx, cy, now)

                names.add(itemName)
                prices.add(if (price > 0) price else 0f)
            }
        }

        if (names.isNotEmpty()) {
            sendBroadcast(Intent("com.torchlight.auto.DROP_DETECTED").apply {
                putExtra("names", names.toTypedArray())
                putExtra("prices", prices.toFloatArray())
            })
        }

        val expired = recentRecords.filterValues { now - it.time > recognitionCooldown * 3 }
        expired.keys.forEach { recentRecords.remove(it) }
    }

    private fun detectColor(bitmap: Bitmap, box: Rect): String {
        val cx = ((box.left + box.right) / 2).coerceIn(0, bitmap.width - 1)
        val cy = ((box.top + box.bottom) / 2).coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(cx, cy)
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)

        return when {
            r > 180 && g < 100 && b < 100 -> "红色"
            r > 180 && g > 150 && b < 100 -> "金色"
            r > 120 && g < 100 && b > 150 -> "紫色"
            r < 100 && g < 150 && b > 180 -> "蓝色"
            else -> "未知"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(captureRunnable)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        super.onDestroy()
    }
}

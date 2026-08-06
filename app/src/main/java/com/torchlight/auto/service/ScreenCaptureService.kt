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
import com.torchlight.auto.data.ItemEntity
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

        val BLACKLIST: Set<String> = setOf(
            "点击", "确定", "取消", "返回", "设置", "背包", "地图",
            "任务", "技能", "商店", "退出", "开始", "暂停", "继续",
            "等级", "经验", "金币", "生命", "法力", "攻击", "防御"
        )
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private lateinit var db: AppDatabase
    private var priceMap: Map<String, Float> = emptyMap()
    private var enabledColorsMap: Map<String, String> = emptyMap()

    private data class Record(val cx: Int, val cy: Int, val time: Long)
    private val recentRecords: ConcurrentHashMap<String, Record> = ConcurrentHashMap()

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var ocrInterval: Long = 350L
    private var recognitionCooldown: Long = 500L
    private var isRunning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        refreshPriceTable()
    }

    private fun refreshPriceTable() {
        scope.launch {
            val items: List<ItemEntity> = db.itemDao().getAllEnabled()
            priceMap = items.associate { item: ItemEntity -> item.name to item.price }
            enabledColorsMap = items.associate { item: ItemEntity -> item.name to item.enabledColors }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val resultCode: Int = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr: MediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            startCapture()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch: NotificationChannel = NotificationChannel(CHANNEL_ID, "火炬助手录屏", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        val pi: PendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("火炬助手")
            .setContentText("正在监控掉落...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
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

        mediaProjection?.let { mp: MediaProjection ->
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

    private val captureRunnable: Runnable = Runnable {
        if (!isRunning) return@Runnable
        captureOnce()
        handler.postDelayed(captureRunnable, ocrInterval)
    }

    private fun captureOnce() {
        val image: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        }
        if (image == null) return

        try {
            val bitmap: Bitmap = imageToBitmap(image) ?: return

            val prefs = getSharedPreferences("ocr_settings", Context.MODE_PRIVATE)
            val cropL: Float = prefs.getFloat("cropL", 0f)
            val cropT: Float = prefs.getFloat("cropT", 0f)
            val cropR: Float = prefs.getFloat("cropR", 1f)
            val cropB: Float = prefs.getFloat("cropB", 1f)

            val left: Int = (bitmap.width * cropL).toInt().coerceIn(0, bitmap.width)
            val top: Int = (bitmap.height * cropT).toInt().coerceIn(0, bitmap.height)
            val right: Int = (bitmap.width * cropR).toInt().coerceIn(left, bitmap.width)
            val bottom: Int = (bitmap.height * cropB).toInt().coerceIn(top, bitmap.height)

            val target: Bitmap = if (right - left > 10 && bottom - top > 10) {
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
        val w: Int = image.width
        val h: Int = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride: Int = plane.pixelStride
        val rowStride: Int = plane.rowStride
        val rowPadding: Int = rowStride - pixelStride * w

        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun runOcr(bitmap: Bitmap) {
        val input: InputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(input)
            .addOnSuccessListener { result: com.google.mlkit.vision.text.Text -> processOcrResult(result, bitmap) }
            .addOnFailureListener { e: Exception -> Log.e(TAG, "OCR failed", e) }
    }

    private fun processOcrResult(visionText: com.google.mlkit.vision.text.Text, bitmap: Bitmap) {
        val now: Long = System.currentTimeMillis()
        val names: MutableList<String> = mutableListOf()
        val prices: MutableList<Float> = mutableListOf()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val raw: String = line.text.trim()
                if (raw.isEmpty()) continue

                val hit: Boolean = BLACKLIST.any { word: String -> raw.contains(word) }
                if (hit) continue

                val itemName: String = priceMap.keys.find { key: String -> raw.contains(key) } ?: continue
                val price: Float = priceMap[itemName] ?: continue
                val allowedColors: String = enabledColorsMap[itemName] ?: "红色,金色,紫色,蓝色"

                val box: Rect = line.boundingBox ?: continue
                val color: String = detectColor(bitmap, box)
                if (!allowedColors.contains(color)) continue

                val cx: Int = (box.left + box.right) / 2
                val cy: Int = (box.top + box.bottom) / 2
                val key: String = "${itemName}_$color"
                val last: Record? = recentRecords[key]
                if (last != null) {
                    val dist: Double = hypot((cx - last.cx).toDouble(), (cy - last.cy).toDouble())
                    if (dist < 80 && (now - last.time) < recognitionCooldown) continue
                }
                recentRecords[key] = Record(cx, cy, now)

                names.add(itemName)
                prices.add(if (price > 0) price else 0f)
            }
        }

        if (names.isNotEmpty()) {
            val intent: Intent = Intent("com.torchlight.auto.DROP_DETECTED")
            intent.putExtra("names", names.toTypedArray())
            intent.putExtra("prices", prices.toFloatArray())
            sendBroadcast(intent)
        }

        // 清理过期记录：用普通 for 循环代替 forEach 避免递归类型推断
        val expired: List<String> = recentRecords.filterValues { record: Record -> now - record.time > recognitionCooldown * 3 }.keys.toList()
        for (key: String in expired) {
            recentRecords.remove(key)
        }
    }

    private fun detectColor(bitmap: Bitmap, box: Rect): String {
        val cx: Int = ((box.left + box.right) / 2).coerceIn(0, bitmap.width - 1)
        val cy: Int = ((box.top + box.bottom) / 2).coerceIn(0, bitmap.height - 1)
        val pixel: Int = bitmap.getPixel(cx, cy)
        val r: Int = android.graphics.Color.red(pixel)
        val g: Int = android.graphics.Color.green(pixel)
        val b: Int = android.graphics.Color.blue(pixel)

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

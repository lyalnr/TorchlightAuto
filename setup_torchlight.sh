#!/bin/bash
set -e
cd ~/TorchlightAuto || { echo "❌ 先 cd 到项目根目录"; exit 1; }

echo "🚀 开始补全火炬之光OCR记账系统..."

# ========== 0. 修改 build.gradle 增加 ML Kit ==========
python3 << 'PYEOF'
import re
with open('app/build.gradle', 'r') as f:
    c = f.read()

if 'kotlin-kapt' not in c:
    c = c.replace("id 'kotlin-parcelize'", "id 'kotlin-parcelize'\n    id 'kotlin-kapt'")

deps = '''
    implementation "androidx.room:room-runtime:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    implementation "androidx.viewpager2:viewpager2:1.0.0"
    implementation "com.google.android.material:material:1.11.0"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.7.0"
    implementation "com.google.mlkit:text-recognition-chinese:16.0.0"
'''
if 'room-runtime' not in c:
    c = c.replace('dependencies {', f'dependencies {{{deps}')

with open('app/build.gradle', 'w') as f:
    f.write(c)
print('✅ build.gradle')
PYEOF

mkdir -p app/src/main/java/com/torchlight/auto/data
mkdir -p app/src/main/res/xml
mkdir -p app/src/main/res/values

# ========== 1. AndroidManifest.xml ==========
cat > app/src/main/AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.torchlight.auto">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.Material3.Dark.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".ScreenCaptureService"
            android:foregroundServiceType="mediaProjection"
            android:exported="false" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
EOF

# ========== 2. file_paths.xml ==========
cat > app/src/main/res/xml/file_paths.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="documents" path="Documents/" />
</paths>
EOF

# ========== 3. 数据库实体 (不变) ==========
cat > app/src/main/java/com/torchlight/auto/data/ItemEntity.kt << 'EOF'
package com.torchlight.auto.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "price_table")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val price: Float = -1f,
    val color: String = "未知",
    val enabled: Boolean = true
)
EOF

cat > app/src/main/java/com/torchlight/auto/data/ItemDao.kt << 'EOF'
package com.torchlight.auto.data
import androidx.room.*
@Dao
interface ItemDao {
    @Query("SELECT * FROM price_table ORDER BY id DESC")
    fun getAll(): List<ItemEntity>
    @Query("SELECT * FROM price_table WHERE name = :name LIMIT 1")
    fun getByName(name: String): ItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(item: ItemEntity)
    @Delete
    fun delete(item: ItemEntity)
    @Update
    fun update(item: ItemEntity)
}
EOF

cat > app/src/main/java/com/torchlight/auto/data/AppDatabase.kt << 'EOF'
package com.torchlight.auto.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(entities = [ItemEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "torchlight_db")
                    .build().also { INSTANCE = it }
            }
        }
    }
}
EOF

# ========== 4. DropRepository (不变) ==========
cat > app/src/main/java/com/torchlight/auto/data/DropRepository.kt << 'EOF'
package com.torchlight.auto.data
data class TodayDrop(val name: String, var quantity: Int, var unitPrice: Float, val color: String)
object DropRepository {
    val todayDrops = mutableListOf<TodayDrop>()
    var totalFire: Float = 0f
        private set
    val listeners = mutableListOf<() -> Unit>()
    fun addDrop(name: String, unitPrice: Float, color: String) {
        val exist = todayDrops.find { it.name == name }
        if (exist != null) exist.quantity++ else todayDrops.add(TodayDrop(name, 1, unitPrice, color))
        recalculate()
        notifyListeners()
    }
    fun updatePrice(name: String, newPrice: Float) {
        todayDrops.find { it.name == name }?.unitPrice = newPrice
        recalculate()
        notifyListeners()
    }
    fun recalculate() {
        totalFire = todayDrops.filter { it.unitPrice >= 0 }
            .sumOf { it.quantity * it.unitPrice.toDouble() }.toFloat()
    }
    fun clear() {
        todayDrops.clear()
        totalFire = 0f
        notifyListeners()
    }
    private fun notifyListeners() = listeners.forEach { it() }
}
EOF

# ========== 5. FloatWindowManager (不变) ==========
cat > app/src/main/java/com/torchlight/auto/FloatWindowManager.kt << 'EOF'
package com.torchlight.auto
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.torchlight.auto.data.DropRepository

class FloatWindowManager(private val ctx: Context) {
    private var wm: WindowManager? = null
    private var container: LinearLayout? = null
    private var tvTotal: TextView? = null
    private var tvList: TextView? = null

    fun show() {
        if (container != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(520, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = 20; y = 180
        }
        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xDD000000.toInt()); setPadding(20,20,20,20) }
        tvTotal = TextView(ctx).apply { text = "💰 今日收入: 0 火"; setTextColor(0xFFFFD700.toInt()); textSize = 18f }
        tvList = TextView(ctx).apply { text = "等待掉落..."; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; setPadding(0,12,0,0) }
        container?.addView(tvTotal)
        container?.addView(tvList)
        wm?.addView(container, p)
        update()
    }
    fun update() {
        tvTotal?.text = "💰 今日收入: ${DropRepository.totalFire} 火"
        val txt = DropRepository.todayDrops.takeLast(4).joinToString("\n") {
            val v = if (it.unitPrice >= 0) "=${it.quantity * it.unitPrice}火" else "=未知"
            "${it.name} x${it.quantity} $v"
        }
        tvList?.text = txt.ifEmpty { "等待掉落..." }
    }
    fun hide() {
        container?.let { try { wm?.removeView(it) } catch(_:Exception){}; container = null }
    }
}
EOF

# ========== 6. ScreenCaptureService (增强：颜色过滤 + 可配置间隔) ==========
cat > app/src/main/java/com/torchlight/auto/ScreenCaptureService.kt << 'EOF'
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
EOF

# ========== 7. MainActivity (增加自然日自动清零) ==========
cat > app/src/main/java/com/torchlight/auto/MainActivity.kt << 'EOF'
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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.torchlight.auto.data.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var pagerAdapter: PagerAdapter
    private var ocrReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null
    private val floatMgr by lazy { FloatWindowManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkDayReset()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabLayout = TabLayout(this)
        viewPager = ViewPager2(this)
        root.addView(tabLayout, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(viewPager, LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        setContentView(root)

        pagerAdapter = PagerAdapter(this)
        viewPager.adapter = pagerAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when(pos) { 0 -> "🎮监控"; 1 -> "📋价格表"; else -> "💰今日账单" }
        }.attach()

        registerReceivers()
        checkPermissions()
        DropRepository.listeners.add { runOnUiThread {
            floatMgr.update()
            (pagerAdapter.fragments[2] as? Page3Fragment)?.refresh()
        }}}
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
        if (!Settings.canDrawOverlays(this)) { toast("需要悬浮窗权限"); return }
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
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("data", data)
                putExtra("left", p1.cropL); putExtra("top", p1.cropT)
                putExtra("right", p1.cropR); putExtra("bottom", p1.cropB)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } else {
            toast("录屏权限被拒绝"); floatMgr.hide()
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy(); scope.cancel()
        try { ocrReceiver?.let { unregisterReceiver(it) } } catch(_:Exception){}
        try { debugReceiver?.let { unregisterReceiver(it) } } catch(_:Exception){}
        floatMgr.hide()
    }

    inner class PagerAdapter(act: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(act) {
        val fragments = listOf(Page1Fragment(), Page2Fragment(), Page3Fragment())
        override fun getItemCount() = 3
        override fun createFragment(pos: Int) = fragments[pos]
    }
}
EOF

# ========== 8. Page1Fragment (重写：颜色过滤 + OCR间隔滑块) ==========
cat > app/src/main/java/com/torchlight/auto/Page1Fragment.kt << 'EOF'
package com.torchlight.auto
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class Page1Fragment : Fragment() {
    lateinit var tvLog: TextView
    lateinit var sv: ScrollView
    var cropL = 0.55f; var cropT = 0.08f; var cropR = 0.95f; var cropB = 0.42f
    private val prefs by lazy { requireContext().getSharedPreferences("ocr_settings", Context.MODE_PRIVATE) }
    private val allColors = listOf("红色","金色","紫色","蓝色")
    private val colorChecks = mutableMapOf<String, CheckBox>()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        root.addView(TextView(context).apply { text = "🎮 监控台\n"; textSize = 18f })

        // OCR截图间隔
        root.addView(TextView(context).apply { text = "OCR截图间隔 (ms)"; textSize = 13f })
        val tvInterval = TextView(context).apply { textSize = 12f }
        val skInterval = SeekBar(context).apply { max = 400 }
        val savedInterval = prefs.getInt("ocr_interval", 350)
        skInterval.progress = savedInterval - 100
        tvInterval.text = "当前: ${savedInterval}ms"
        skInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 100 + progress
                tvInterval.text = "当前: ${v}ms"
                prefs.edit().putInt("ocr_interval", v).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(tvInterval)
        root.addView(skInterval)

        // 颜色过滤
        root.addView(TextView(context).apply { text = "\n🎨 只识别颜色 (勾选生效)："; textSize = 13f })
        val colorRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val savedColors = prefs.getStringSet("enabled_colors", allColors.toSet()) ?: allColors.toSet()
        for (color in allColors) {
            val cb = CheckBox(context).apply {
                text = color
                isChecked = color in savedColors
                setOnCheckedChangeListener { _, _ -> saveColors() }
            }
            colorChecks[color] = cb
            colorRow.addView(cb)
        }
        root.addView(colorRow)

        // 区域微调
        root.addView(TextView(context).apply { text = "\n📐 识别区域微调："; textSize = 13f })
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("↑") { cropT-=0.02f; cropB-=0.02f; showArea() })
        row1.addView(btn("↓") { cropT+=0.02f; cropB+=0.02f; showArea() })
        row1.addView(btn("←") { cropL-=0.02f; cropR-=0.02f; showArea() })
        row1.addView(btn("→") { cropL+=0.02f; cropR+=0.02f; showArea() })
        root.addView(row1)

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("放大") { cropL-=0.03f; cropT-=0.03f; cropR+=0.03f; cropB+=0.03f; clamp(); showArea() })
        row2.addView(btn("缩小") { cropL+=0.03f; cropT+=0.03f; cropR-=0.03f; cropB-=0.03f; clamp(); showArea() })
        row2.addView(btn("默认") { cropL=0.55f; cropT=0.08f; cropR=0.95f; cropB=0.42f; showArea() })
        root.addView(row2)

        val tvArea = TextView(context).apply { text = "区域: 右上55%~95%"; textSize = 11f; setPadding(0,4,0,8) }
        root.addView(tvArea)

        val btnStart = Button(context).apply {
            text = "▶ 开始录屏识别"
            setOnClickListener { (activity as MainActivity).startOCR() }
        }
        root.addView(btnStart)

        val btnStop = Button(context).apply {
            text = "⏹ 停止并清空"
            setOnClickListener { (activity as MainActivity).stopOCR() }
        }
        root.addView(btnStop)

        root.addView(TextView(context).apply { text = "\n📋 日志："; textSize = 14f })

        sv = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        tvLog = TextView(context).apply { text = "等待启动...\n"; textSize = 12f; setTextIsSelectable(true) }
        sv.addView(tvLog)
        root.addView(sv)

        return root
    }

    private fun saveColors() {
        val selected = colorChecks.filter { it.value.isChecked }.keys.toSet()
        prefs.edit().putStringSet("enabled_colors", selected).apply()
    }

    private fun btn(t: String, click: () -> Unit) = Button(context).apply {
        text = t; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click() }
    }

    private fun clamp() {
        cropL = cropL.coerceIn(0f,0.9f); cropT = cropT.coerceIn(0f,0.9f)
        cropR = cropR.coerceIn(0.1f,1f); cropB = cropB.coerceIn(0.1f,1f)
        if (cropR <= cropL) cropR = cropL + 0.1f
        if (cropB <= cropT) cropB = cropT + 0.1f
    }

    private fun showArea() {
        appendLog("📐 L=${(cropL*100).toInt()}% T=${(cropT*100).toInt()}% R=${(cropR*100).toInt()}% B=${(cropB*100).toInt()}%")
    }

    fun appendLog(msg: String) {
        activity?.runOnUiThread {
            tvLog.append("$msg\n")
            sv.post { sv.scrollTo(0, tvLog.bottom) }
        }
    }
}
EOF

# ========== 9. Page2Fragment (补全：从 spCo 续写) ==========
cat > app/src/main/java/com/torchlight/auto/Page2Fragment.kt << 'EOF'
package com.torchlight.auto
import android.app.AlertDialog
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.torchlight.auto.data.AppDatabase
import com.torchlight.auto.data.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Page2Fragment : Fragment() {
    private lateinit var container: LinearLayout
    private val colors = listOf("红色","金色","紫色","蓝色","白色","未知")

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = ScrollView(context)
        container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        root.addView(container)
        refreshList()
        return root
    }

    override fun onResume() { super.onResume(); refreshList() }

    fun refreshList() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).itemDao().getAll()
            }
            container.removeAllViews()
            container.addView(TextView(context).apply { text = "📋 价格表 (单位: 火)\n"; textSize = 18f })

            val btnAdd = Button(context).apply {
                text = "➕ 手动添加物品"
                setOnClickListener { showEditDialog(null) }
            }
            container.addView(btnAdd)

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeMiniBtn("全选") { setAllEnabled(true) })
            row.addView(makeMiniBtn("全不选") { setAllEnabled(false) })
            row.addView(makeMiniBtn("删未知") { deleteUnknown() })
            container.addView(row)

            for (item in items) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12,12,12,12)
                    setBackgroundColor(if (item.enabled) 0xFF1A1A2E.toInt() else 0xFF0F0F1A.toInt())
                }

                val cb = CheckBox(context).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            AppDatabase.getDatabase(requireContext()).itemDao()
                                .update(item.copy(enabled = checked))
                        }
                    }
                }
                card.addView(cb)

                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(12,0,0,0) }
                val nameColor = when(item.color) {
                    "红色" -> AndroidColor.parseColor("#FF4444")
                    "金色" -> AndroidColor.parseColor("#FFD700")
                    "紫色" -> AndroidColor.parseColor("#DDA0DD")
                    "蓝色" -> AndroidColor.parseColor("#87CEEB")
                    else -> AndroidColor.WHITE
                }
                info.addView(TextView(context).apply {
                    text = "${item.name} [${item.color}]"
                    setTextColor(nameColor); textSize = 15f
                })
                val priceText = if (item.price >= 0) "${item.price} 火" else "价格未知"
                info.addView(TextView(context).apply {
                    text = priceText; textSize = 13f; setTextColor(0xFFAAAAAA.toInt())
                })
                card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val btnEdit = Button(context).apply {
                    text = "编辑"; textSize = 11f
                    setOnClickListener { showEditDialog(item) }
                }
                card.addView(btnEdit)

                container.addView(card)
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                    setBackgroundColor(0xFF333333.toInt())
                })
            }
        }
    }

    private fun makeMiniBtn(t: String, click: () -> Unit) = Button(context).apply {
        text = t; textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click(); refreshList() }
    }

    private fun setAllEnabled(v: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.itemDao().getAll().forEach { db.itemDao().update(it.copy(enabled = v)) }
            launch(Dispatchers.Main) { refreshList() }
        }
    }

    private fun deleteUnknown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.itemDao().getAll().filter { it.price < 0 }.forEach { db.itemDao().delete(it) }
            launch(Dispatchers.Main) { refreshList() }
        }
    }

    private fun showEditDialog(item: ItemEntity?) {
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }

        val etName = EditText(ctx).apply { hint = "物品名称"; setText(item?.name ?: "") }
        layout.addView(etName)

        val etPrice = EditText(ctx).apply {
            hint = "价格（火）"; inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (item != null && item.price >= 0) item.price.toString() else "")
        }
        layout.addView(etPrice)

        val spColor = Spinner(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, colors)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spColor.adapter = adapter
        spColor.setSelection(colors.indexOf(item?.color ?: "未知"))
        layout.addView(spColor)

        AlertDialog.Builder(ctx)
            .setTitle(if (item == null) "添加物品" else "编辑物品")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val priceStr = etPrice.text.toString().trim()
                val price = if (priceStr.isEmpty()) -1f else priceStr.toFloat()
                val color = spColor.selectedItem.toString()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext())
                    if (item == null) {
                        db.itemDao().insert(ItemEntity(name = name, price = price, color = color, enabled = true))
                    } else {
                        db.itemDao().update(item.copy(name = name, price = price, color = color))
                    }
                    launch(Dispatchers.Main) { refreshList() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
EOF

# ========== 10. Page3Fragment (新建：今日账单 + 导出) ==========
cat > app/src/main/java/com/torchlight/auto/Page3Fragment.kt << 'EOF'
package com.torchlight.auto
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.torchlight.auto.data.DropRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class Page3Fragment : Fragment() {
    private lateinit var tvTotal: TextView
    private lateinit var dropsContainer: LinearLayout

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = ScrollView(context)
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }

        tvTotal = TextView(context).apply {
            text = "💰 今日总收入: 0 火"; textSize = 20f; setPadding(0,0,0,16)
        }
        main.addView(tvTotal)

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(btn("📤 导出") { exportData() })
        btnRow.addView(btn("🗑️ 清零") { clearData() })
        main.addView(btnRow)

        main.addView(TextView(context).apply { text = "\n📦 今日掉落："; textSize = 16f })

        dropsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        main.addView(dropsContainer)

        root.addView(main)
        refresh()
        return root
    }

    override fun onResume() { super.onResume(); refresh() }

    fun refresh() {
        tvTotal.text = "💰 今日总收入: ${DropRepository.totalFire} 火"
        dropsContainer.removeAllViews()
        if (DropRepository.todayDrops.isEmpty()) {
            dropsContainer.addView(TextView(context).apply { text = "暂无掉落"; setTextColor(0xFF888888.toInt()) })
            return
        }
        for (drop in DropRepository.todayDrops.sortedByDescending { it.quantity }) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8,8,8,8)
            }
            val totalText = if (drop.unitPrice >= 0) "=${drop.quantity * drop.unitPrice}火" else "未知"
            val nameView = TextView(context).apply {
                text = "${drop.name} [${drop.color}]"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            val qtyView = TextView(context).apply {
                text = "x${drop.quantity}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valView = TextView(context).apply {
                text = totalText
                textSize = 14f
                setTextColor(0xFFFFD700.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)
            row.addView(qtyView)
            row.addView(valView)
            dropsContainer.addView(row)
            dropsContainer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0xFF333333.toInt())
            })
        }
    }

    fun addDrop(name: String, price: Float, color: String) {
        refresh()
    }

    private fun btn(t: String, click: () -> Unit) = Button(context).apply {
        text = t; textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click() }
    }

    private fun clearData() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认清零")
            .setMessage("确定清空今日所有掉落记录吗？")
            .setPositiveButton("确定") { _, _ ->
                DropRepository.clear()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportData() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("火炬之光掉落记录 - $date")
        sb.appendLine("总收入: ${DropRepository.totalFire} 火")
        sb.appendLine("========================")
        for (drop in DropRepository.todayDrops) {
            val line = if (drop.unitPrice >= 0) {
                "${drop.name} [${drop.color}] x${drop.quantity} @${drop.unitPrice}火 = ${drop.quantity * drop.unitPrice}火"
            } else {
                "${drop.name} [${drop.color}] x${drop.quantity} @未知"
            }
            sb.appendLine(line)
        }
        try {
            val dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, "drops_$date.txt")
            file.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "导出掉落记录"))
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
EOF

echo ""
echo "✅ 全部文件已生成！"
echo "📍 项目路径: $(pwd)"
echo ""
echo "下一步："
echo "  1. git add ."
echo "  2. git commit -m 'feat: 完整OCR记账系统'"
echo "  3. git push"
echo ""
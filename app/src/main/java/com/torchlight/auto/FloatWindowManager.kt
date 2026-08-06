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

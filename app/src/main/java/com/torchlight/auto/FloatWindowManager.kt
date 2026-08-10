package com.torchlight.auto

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.torchlight.auto.data.DropRepository

class FloatWindowManager(private val ctx: Context) {
    private var wm: WindowManager? = null
    private var container: FrameLayout? = null
    private var tvTotal: TextView? = null
    private var tvList: TextView? = null
    private var tvLock: TextView? = null
    private var isLocked = false
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f

    fun show() {
        if (container != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            520, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 180 }

        container = FrameLayout(ctx).apply {
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 24, 24, 24)
            setOnTouchListener { _, event ->
                if (isLocked) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x; initialY = params!!.y
                        touchX = event.rawX; touchY = event.rawY; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = initialX + (event.rawX - touchX).toInt()
                        params!!.y = initialY + (event.rawY - touchY).toInt()
                        wm?.updateViewLayout(this, params); true
                    }
                    else -> false
                }
            }
        }
        tvLock = TextView(ctx).apply {
            text = "🔓"; textSize = 18f; setPadding(8, 4, 8, 4)
            setOnClickListener { isLocked = !isLocked; updateLockState() }
        }
        container?.addView(tvLock, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START })

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 44, 0, 0)
        }
        tvTotal = TextView(ctx).apply {
            text = "💰 今日收入: 0 火"; setTextColor(0xFFFFD700.toInt()); textSize = 18f
        }
        tvList = TextView(ctx).apply {
            text = "等待掉落..."; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; setPadding(0, 12, 0, 0)
        }
        content.addView(tvTotal)
        content.addView(tvList)
        container?.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        wm?.addView(container, params)
        update()
    }

    private fun updateLockState() {
        val p = params ?: return
        val view = container ?: return
        if (isLocked) {
            tvLock?.text = "🔒"
            view.setBackgroundColor(0x66000000.toInt())
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            tvLock?.text = "🔓"
            view.setBackgroundColor(0xAA000000.toInt())
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        wm?.updateViewLayout(view, p)
    }

    fun unlock() { if (isLocked) { isLocked = false; updateLockState() } }
    fun isLocked(): Boolean = isLocked

    fun update() {
        val current = DropRepository.currentMap
        val total = DropRepository.totalFire
        val mapCount = DropRepository.mapCount
        val totalTimeMin = DropRepository.totalTimeMs / 60000f
        val totalSpeed = if (totalTimeMin > 0) total / totalTimeMin else 0f
        val currentSpeed = current?.firePerMin ?: 0f
        val currentIncome = current?.income ?: 0f
        val currentDuration = current?.durationMs ?: 0
        val durationStr = formatDuration(currentDuration)

        tvTotal?.text = "💰 今日: ${total.toInt()}火 | 地图${mapCount}"
        tvList?.text = buildString {
            appendLine("⚡ 当前: ${currentIncome.toInt()}火 (${durationStr})")
            appendLine("📈 速度: ${currentSpeed.toInt()}/分 | 总计: ${totalSpeed.toInt()}/分")
            val recent = DropRepository.todayDrops.takeLast(3)
            if (recent.isNotEmpty()) {
                appendLine("─".repeat(20))
                for (drop in recent) {
                    val v = if (drop.unitPrice >= 0) "=${(drop.quantity * drop.unitPrice).toInt()}火" else ""
                    appendLine("${drop.name} x${drop.quantity} $v")
                }
            }
        }.trim()
    }

    private fun formatDuration(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        return "${m}m${s}s"
    }

    fun hide() {
        container?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            container = null; isLocked = false
        }
    }
}

package com.torchlight.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class RegionSelectActivity : AppCompatActivity() {

    private lateinit var overlayView: OverlayView
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var drawing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        overlayView = OverlayView(this)
        root.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val info = TextView(this).apply {
            text = "👆 手指按住拖动框选识别区域\n框内 = 识别范围，框外 = 忽略"
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(40, 120, 40, 0)
        }
        root.addView(info)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 0, 40, 80)
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.BOTTOM }
        root.addView(btnRow, params)

        btnRow.addView(Button(this).apply {
            text = "取消"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })

        btnRow.addView(Button(this).apply {
            text = "确认"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveAndExit() }
        })

        setContentView(root)
    }

    private fun saveAndExit() {
        if (!drawing) {
            finish()
            return
        }
        val w = overlayView.width.toFloat()
        val h = overlayView.height.toFloat()
        if (w == 0f || h == 0f) { finish(); return }

        val left = (minOf(startX, endX) / w).coerceIn(0f, 1f)
        val top = (minOf(startY, endY) / h).coerceIn(0f, 1f)
        val right = (maxOf(startX, endX) / w).coerceIn(0f, 1f)
        val bottom = (maxOf(startY, endY) / h).coerceIn(0f, 1f)

        getSharedPreferences("ocr_settings", Context.MODE_PRIVATE).edit().apply {
            putFloat("cropL", left)
            putFloat("cropT", top)
            putFloat("cropR", right)
            putFloat("cropB", bottom)
            apply()
        }
        finish()
    }

    inner class OverlayView(ctx: Context) : View(ctx) {
        private val paintRect = Paint().apply {
            color = Color.parseColor("#00FF00")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val paintClear = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val paintDim = Paint().apply {
            color = Color.parseColor("#AA000000")
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    endX = event.x
                    endY = event.y
                    drawing = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x
                    endY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    endX = event.x
                    endY = event.y
                    drawing = true
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!drawing) return

            val l = minOf(startX, endX)
            val t = minOf(startY, endY)
            val r = maxOf(startX, endX)
            val b = maxOf(startY, endY)

            // 全屏遮罩
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintDim)
            // 挖空选中区域
            canvas.drawRect(l, t, r, b, paintClear)
            // 画绿色边框
            canvas.drawRect(l, t, r, b, paintRect)
        }
    }
}

package com.torchlight.auto

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.maxOf
import kotlin.math.minOf

class RegionSelectorWindow(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(onConfirmed: (left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val hint = TextView(context).apply {
            text = "🎮 已检测到游戏主页面\n👆 请按住拖动框选【掉落物品提示】区域"
            textSize = 18f
            setTextColor(Color.WHITE)
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
            setPadding(40, 140, 40, 40)
        }
        root.addView(hint)

        val selector = SelectorView(context)
        root.addView(selector, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xCC000000.toInt())
        }
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = 180
        }

        btnBar.addView(Button(context).apply {
            text = "取消"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { hide() }
        })

        btnBar.addView(Button(context).apply {
            text = "✅ 确认区域"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (!selector.hasSelection) {
                    Toast.makeText(context, "请先框选区域", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onConfirmed(
                    selector.leftPct.coerceIn(0f, 1f),
                    selector.topPct.coerceIn(0f, 1f),
                    selector.rightPct.coerceIn(0f, 1f),
                    selector.bottomPct.coerceIn(0f, 1f)
                )
                hide()
            }
        })

        root.addView(btnBar, barParams)
        wm.addView(root, params)
        rootView = root
    }

    fun hide() {
        rootView?.let { wm.removeView(it) }
        rootView = null
    }

    private class SelectorView(ctx: Context) : View(ctx) {
        private var startX = 0f; private var startY = 0f
        private var endX = 0f; private var endY = 0f
        private var drawing = false
        var hasSelection = false
            private set

        val leftPct   get() = minOf(startX, endX) / width
        val topPct    get() = minOf(startY, endY) / height
        val rightPct  get() = maxOf(startX, endX) / width
        val bottomPct get() = maxOf(startY, endY) / height

        private val paintRect = Paint().apply {
            color = Color.parseColor("#00FF00")
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        private val paintDim = Paint().apply {
            color = Color.parseColor("#AA000000")
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    endX = event.x; endY = event.y
                    drawing = true; hasSelection = false
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x; endY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    endX = event.x; endY = event.y
                    drawing = true; hasSelection = true
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!drawing) return
            val l = minOf(startX, endX); val t = minOf(startY, endY)
            val r = maxOf(startX, endX); val b = maxOf(startY, endY)
            val path = Path().apply {
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRect(l, t, r, b, Path.Direction.CCW)
                fillType = Path.FillType.EVEN_ODD
            }
            canvas.drawPath(path, paintDim)
            canvas.drawRect(l, t, r, b, paintRect)
        }
    }
}

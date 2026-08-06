package com.torchlight.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class RegionSelectActivity : AppCompatActivity() {

    private lateinit var overlayView: OverlayView
    private lateinit var btnPick: Button
    private lateinit var tvInfo: TextView
    private var screenshotBitmap: Bitmap? = null
    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var drawing = false
    private var imgDrawX = 0f; private var imgDrawY = 0f
    private var imgDrawW = 0f; private var imgDrawH = 0f

    companion object { const val PICK_IMAGE = 1001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        overlayView = OverlayView(this)
        root.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        tvInfo = TextView(this).apply {
            text = "📸 上传截图后框选识别区域"
            textSize = 12f; setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding(30, 10, 30, 10)
            setBackgroundColor(0x88000000.toInt())
        }
        root.addView(tvInfo, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; topMargin = 10; leftMargin = 10 })

        btnPick = Button(this).apply {
            text = "📁 选择截图"; textSize = 12f
            setPadding(30, 10, 30, 10)
            setOnClickListener { pickImage() }
        }
        root.addView(btnPick, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; topMargin = 10 })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 20, 8)
            setBackgroundColor(0xCC000000.toInt())
        }
        root.addView(btnRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.BOTTOM })

        btnRow.addView(Button(this).apply {
            text = "取消"; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        btnRow.addView(Button(this).apply {
            text = "确认"; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveAndExit() }
        })

        setContentView(root)
    }

    private fun pickImage() {
        startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            val uri: Uri = data.data ?: return
            try {
                val input = contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(input)
                input?.close()
                if (bmp != null) {
                    screenshotBitmap = bmp
                    overlayView.setBitmap(bmp)
                    btnPick.visibility = View.GONE
                    tvInfo.text = "👆 手指按住拖动框选"
                    Toast.makeText(this, "截图已加载", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndExit() {
        if (!drawing || screenshotBitmap == null) {
            Toast.makeText(this, "请先框选区域", Toast.LENGTH_SHORT).show()
            return
        }
        val left = ((minOf(startX, endX) - imgDrawX) / imgDrawW).coerceIn(0f, 1f)
        val top = ((minOf(startY, endY) - imgDrawY) / imgDrawH).coerceIn(0f, 1f)
        val right = ((maxOf(startX, endX) - imgDrawX) / imgDrawW).coerceIn(0f, 1f)
        val bottom = ((maxOf(startY, endY) - imgDrawY) / imgDrawH).coerceIn(0f, 1f)
        getSharedPreferences("ocr_settings", Context.MODE_PRIVATE).edit().apply {
            putFloat("cropL", left); putFloat("cropT", top)
            putFloat("cropR", right); putFloat("cropB", bottom)
            apply()
        }
        Toast.makeText(this, "区域已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    inner class OverlayView(ctx: Context) : View(ctx) {
        private val paintRect = Paint().apply {
            color = Color.parseColor("#00FF00"); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val paintClear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val paintDim = Paint().apply { color = Color.parseColor("#AA000000") }
        private var bitmap: Bitmap? = null
        private val bmpPaint = Paint()
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        fun setBitmap(bmp: Bitmap) { bitmap = bmp; invalidate() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; endX = event.x; endY = event.y; drawing = true; invalidate() }
                MotionEvent.ACTION_MOVE -> { endX = event.x; endY = event.y; invalidate() }
                MotionEvent.ACTION_UP -> { endX = event.x; endY = event.y; drawing = true; invalidate() }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bitmap?.let { bmp ->
                val screenW = width.toFloat(); val screenH = height.toFloat()
                val bmpW = bmp.width.toFloat(); val bmpH = bmp.height.toFloat()

                // 🔥 关键修复：maxOf 让图片铺满屏幕，不留黑边
                val scale = maxOf(screenW / bmpW, screenH / bmpH)
                imgDrawW = bmpW * scale; imgDrawH = bmpH * scale
                imgDrawX = (screenW - imgDrawW) / 2; imgDrawY = (screenH - imgDrawH) / 2

                val matrix = Matrix()
                matrix.postScale(scale, scale)
                matrix.postTranslate(imgDrawX, imgDrawY)
                canvas.drawBitmap(bmp, matrix, bmpPaint)
            }
            if (!drawing) return
            val l = minOf(startX, endX); val t = minOf(startY, endY)
            val r = maxOf(startX, endX); val b = maxOf(startY, endY)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintDim)
            canvas.drawRect(l, t, r, b, paintClear)
            canvas.drawRect(l, t, r, b, paintRect)
        }
    }
}

package com.torchlight.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.InputStream

class RegionSelectActivity : AppCompatActivity() {

    private lateinit var overlayView: OverlayView
    private var screenshotBitmap: Bitmap? = null
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var drawing = false
    private var imgW = 0f
    private var imgH = 0f

    companion object {
        const val PICK_IMAGE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // 顶部提示
        val tvInfo = TextView(this).apply {
            text = "📸 请上传一张横屏游戏截图，然后在截图上框选识别区域"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(20, 40, 20, 20)
        }
        root.addView(tvInfo)

        // 上传按钮
        val btnPick = Button(this).apply {
            text = "📁 选择截图"
            setOnClickListener { pickImage() }
        }
        root.addView(btnPick)

        // 截图显示区域
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        root.addView(frame)

        overlayView = OverlayView(this)
        frame.addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // 底部按钮
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 10, 20, 40)
        }
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
        root.addView(btnRow)

        setContentView(root)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            val uri: Uri = data.data ?: return
            try {
                val input: InputStream? = contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(input)
                input?.close()
                if (bmp != null) {
                    // 如果图片是竖屏的，旋转90度变成横屏
                    screenshotBitmap = if (bmp.height > bmp.width) {
                        val matrix = Matrix()
                        matrix.postRotate(90f)
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    } else {
                        bmp
                    }
                    imgW = screenshotBitmap!!.width.toFloat()
                    imgH = screenshotBitmap!!.height.toFloat()
                    overlayView.setBitmap(screenshotBitmap!!)
                    Toast.makeText(this, "截图已加载，请在上面框选区域", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndExit() {
        if (!drawing || screenshotBitmap == null) {
            Toast.makeText(this, "请先上传截图并框选区域", Toast.LENGTH_SHORT).show()
            return
        }
        val viewW = overlayView.width.toFloat()
        val viewH = overlayView.height.toFloat()
        if (viewW == 0f || viewH == 0f) { finish(); return }

        // 将屏幕坐标转换为图片比例
        val left = (minOf(startX, endX) / viewW).coerceIn(0f, 1f)
        val top = (minOf(startY, endY) / viewH).coerceIn(0f, 1f)
        val right = (maxOf(startX, endX) / viewW).coerceIn(0f, 1f)
        val bottom = (maxOf(startY, endY) / viewH).coerceIn(0f, 1f)

        getSharedPreferences("ocr_settings", Context.MODE_PRIVATE).edit().apply {
            putFloat("cropL", left)
            putFloat("cropT", top)
            putFloat("cropR", right)
            putFloat("cropB", bottom)
            apply()
        }
        Toast.makeText(this, "区域已保存", Toast.LENGTH_SHORT).show()
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
        private var bitmap: Bitmap? = null
        private val bmpPaint = Paint()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setBitmap(bmp: Bitmap) {
            bitmap = bmp
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    endX = event.x; endY = event.y
                    drawing = true; invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x; endY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    endX = event.x; endY = event.y
                    drawing = true; invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 先画截图
            bitmap?.let { bmp ->
                val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                val bw = bmp.width * scale
                val bh = bmp.height * scale
                val bx = (width - bw) / 2
                val by = (height - bh) / 2
                val matrix = Matrix()
                matrix.postScale(scale, scale)
                matrix.postTranslate(bx, by)
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

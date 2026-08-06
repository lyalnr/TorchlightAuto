package com.torchlight.auto

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment

class Page1Fragment : Fragment() {
    lateinit var tvLog: TextView
    lateinit var sv: ScrollView
    var cropL = 0.55f
    var cropT = 0.08f
    var cropR = 0.95f
    var cropB = 0.42f
    private val prefs by lazy { requireContext().getSharedPreferences("ocr_settings", Context.MODE_PRIVATE) }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        root.addView(TextView(context).apply {
            text = "🎮 监控台\n"
            textSize = 18f
        })

        // === 截图间隔 ===
        root.addView(TextView(context).apply {
            text = "截图间隔 (ms)"
            textSize = 13f
        })
        val tvCap = TextView(context).apply { textSize = 12f }
        val skCap = SeekBar(context).apply { max = 400 }
        val savedCap = prefs.getInt("ocr_interval", 350)
        skCap.progress = savedCap - 100
        tvCap.text = "当前: ${savedCap}ms"
        skCap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                val v = 100 + p; tvCap.text = "当前: ${v}ms"
                prefs.edit().putInt("ocr_interval", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(tvCap)
        root.addView(skCap)

        // === 识别冷却间隔（防重复计数） ===
        root.addView(TextView(context).apply {
            text = "\n识别冷却 (ms)\n同一物品识别后多久不再重复计数"
            textSize = 13f
        })
        val tvCool = TextView(context).apply { textSize = 12f }
        val skCool = SeekBar(context).apply { max = 2900 }
        val savedCool = prefs.getInt("recognition_cooldown", 500)
        skCool.progress = savedCool - 100
        tvCool.text = "当前: ${savedCool}ms"
        skCool.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                val v = 100 + p; tvCool.text = "当前: ${v}ms"
                prefs.edit().putInt("recognition_cooldown", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(tvCool)
        root.addView(skCool)

        // === 区域输入框 ===
        root.addView(TextView(context).apply {
            text = "\n📐 识别区域 (%)："
            textSize = 13f
        })

        val etL = makeEt(cropL)
        val etT = makeEt(cropT)
        val etR = makeEt(cropR)
        val etB = makeEt(cropB)

        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(label("左")); row1.addView(etL)
        row1.addView(label("上")); row1.addView(etT)
        root.addView(row1)

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(label("右")); row2.addView(etR)
        row2.addView(label("下")); row2.addView(etB)
        root.addView(row2)

        val rowBtn = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        rowBtn.addView(Button(context).apply {
            text = "应用"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                cropL = parseEt(etL, cropL)
                cropT = parseEt(etT, cropT)
                cropR = parseEt(etR, cropR)
                cropB = parseEt(etB, cropB)
                clamp()
                showArea()
            }
        })
        rowBtn.addView(Button(context).apply {
            text = "默认"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                cropL = 0.55f; cropT = 0.08f; cropR = 0.95f; cropB = 0.42f
                etL.setText("55"); etT.setText("8")
                etR.setText("95"); etB.setText("42")
                showArea()
            }
        })
        root.addView(rowBtn)

        val tvArea = TextView(context).apply {
            text = "区域: 右上55%~95%"; textSize = 11f; setPadding(0, 4, 0, 8)
        }
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

        root.addView(TextView(context).apply {
            text = "\n📋 日志："; textSize = 14f
        })

        sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvLog = TextView(context).apply {
            text = "等待启动...\n"; textSize = 12f; setTextIsSelectable(true)
        }
        sv.addView(tvLog)
        root.addView(sv)

        return root
    }

    private fun label(t: String) = TextView(context).apply {
        text = " $t:"; textSize = 13f; setPadding(8, 16, 4, 0)
    }

    private fun makeEt(v: Float) = EditText(context).apply {
        setText((v * 100).toInt().toString())
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun parseEt(et: EditText, fallback: Float): Float {
        return try {
            (et.text.toString().toInt() / 100f).coerceIn(0f, 1f)
        } catch (_: Exception) { fallback }
    }

    private fun clamp() {
        cropL = cropL.coerceIn(0f, 0.9f); cropT = cropT.coerceIn(0f, 0.9f)
        cropR = cropR.coerceIn(0.1f, 1f); cropB = cropB.coerceIn(0.1f, 1f)
        if (cropR <= cropL) cropR = cropL + 0.05f
        if (cropB <= cropT) cropB = cropT + 0.05f
    }

    private fun showArea() {
        appendLog("📐 L=${(cropL * 100).toInt()}% T=${(cropT * 100).toInt()}% R=${(cropR * 100).toInt()}% B=${(cropB * 100).toInt()}%")
    }

    fun appendLog(msg: String) {
        activity?.runOnUiThread {
            tvLog.append("$msg\n")
            sv.post { sv.scrollTo(0, tvLog.bottom) }
        }
    }
}

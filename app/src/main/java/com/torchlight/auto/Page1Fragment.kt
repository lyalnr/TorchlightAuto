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

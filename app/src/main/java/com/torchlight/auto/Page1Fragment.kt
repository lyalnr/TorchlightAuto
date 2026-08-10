package com.torchlight.auto

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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

        root.addView(TextView(context).apply {
            text = "\n📐 识别区域："
            textSize = 13f
        })
        val tvArea = TextView(context).apply {
            text = "未设置，请点击下方按钮框选"
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }
        root.addView(tvArea)

        val btnSelect = TextView(context).apply {
            text = "📐 区域将在启动后自动框选"
            setTextColor(Color.GRAY)
        }
        root.addView(btnSelect)

        loadSavedArea(tvArea)

        val btnStart = Button(context).apply {
            text = "▶ 开始录屏识别"
            setOnClickListener {
                try {
                    (activity as MainActivity).startOCR()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "录屏启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnStart)

        val btnStop = Button(context).apply {
            text = "⏹ 停止并清空"
            setOnClickListener {
                try {
                    (activity as MainActivity).stopOCR()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "停止失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnStop)

        val btnUnlock = Button(context).apply {
            text = "🔓 解锁悬浮窗"
            setOnClickListener {
                try {
                    (activity as MainActivity).unlockFloatWindow()
                    Toast.makeText(requireContext(), "悬浮窗已解锁，可拖动", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "解锁失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnUnlock)

        root.addView(TextView(context).apply {
            text = "\n📄 日志监听模式（实验性）："
            textSize = 14f
        })

        val btnLogStart = Button(context).apply {
            text = "▶ 启动日志监听"
            setOnClickListener {
                try {
                    (activity as MainActivity).startLogMonitor()
                    appendLog("📄 日志监听已启动")
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnLogStart)

        val btnLogStop = Button(context).apply {
            text = "⏹ 停止日志监听"
            setOnClickListener {
                try {
                    (activity as MainActivity).stopLogMonitor()
                    appendLog("⏹ 日志监听已停止")
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "停止失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnLogStop)

        root.addView(TextView(context).apply {
            text = "\n⚠️ 需要授权访问 Android/data 目录"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })

        root.addView(TextView(context).apply {
            text = "\n📋 日志："
            textSize = 14f
        })

        sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvLog = TextView(context).apply {
            text = "等待启动...\n"
            textSize = 12f
            setTextIsSelectable(true)
        }
        sv.addView(tvLog)
        root.addView(sv)

        return root
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            val tvArea = (it as LinearLayout).getChildAt(8) as? TextView
            loadSavedArea(tvArea)
        }
    }

    private fun loadSavedArea(tv: TextView?) {
        cropL = prefs.getFloat("cropL", 0.55f)
        cropT = prefs.getFloat("cropT", 0.08f)
        cropR = prefs.getFloat("cropR", 0.95f)
        cropB = prefs.getFloat("cropB", 0.42f)
        tv?.text = "当前区域: 左${(cropL*100).toInt()}% 上${(cropT*100).toInt()}% 右${(cropR*100).toInt()}% 下${(cropB*100).toInt()}%"
    }

    fun appendLog(msg: String) {
        activity?.runOnUiThread {
            tvLog.append("$msg\n")
            sv.post { sv.scrollTo(0, tvLog.bottom) }
        }
    }
}

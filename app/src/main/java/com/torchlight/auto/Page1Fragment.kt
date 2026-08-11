package com.torchlight.auto

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import rikka.shizuku.Shizuku

class Page1Fragment : Fragment() {
    lateinit var tvLog: TextView
    lateinit var sv: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnFloat: Button
    private lateinit var tvShizuku: TextView

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        root.addView(TextView(context).apply {
            text = "🎮 火炬之光无限 - 日志掉落统计\n"
            textSize = 18f
        })

        // Shizuku 状态
        tvShizuku = TextView(context).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        root.addView(tvShizuku)
        updateShizukuStatus()

        root.addView(TextView(context).apply {
            text = "\n📄 日志监听模式（通过 Shizuku 读取游戏日志）"
            textSize = 14f
        })

        root.addView(TextView(context).apply {
            text = "1. 先安装并启动 Shizuku\n2. 在本应用授权 Shizuku\n3. 启动日志监听后进游戏\n"
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
        })

        btnStart = Button(context).apply {
            text = "▶ 启动日志监听"
            setOnClickListener {
                try {
                    (activity as MainActivity).showFloatWindow()
                    (activity as MainActivity).startLogMonitor()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnStart)

        btnStop = Button(context).apply {
            text = "⏹ 停止日志监听"
            setOnClickListener {
                try {
                    (activity as MainActivity).stopLogMonitor()
                    (activity as MainActivity).hideFloatWindow()
                    appendLog("⏹ 已停止")
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "停止失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnStop)

        btnFloat = Button(context).apply {
            text = "🔓 解锁悬浮窗"
            setOnClickListener {
                try {
                    (activity as MainActivity).showFloatWindow()
                    Toast.makeText(requireContext(), "悬浮窗已解锁，可拖动", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "解锁失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnFloat)

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
        updateShizukuStatus()
    }

    private fun updateShizukuStatus() {
        val status = when {
            !Shizuku.pingBinder() -> "❌ Shizuku 未运行"
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> "✅ Shizuku 已授权"
            else -> "⚠️ Shizuku 未授权（点击启动会请求权限）"
        }
        tvShizuku.text = "Shizuku 状态: $status"
    }

    fun appendLog(msg: String) {
        activity?.runOnUiThread {
            tvLog.append("$msg\n")
            sv.post { sv.scrollTo(0, tvLog.bottom) }
        }
    }
}

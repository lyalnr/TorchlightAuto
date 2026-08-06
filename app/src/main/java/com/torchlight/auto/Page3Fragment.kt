package com.torchlight.auto

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.torchlight.auto.data.DropRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class Page3Fragment : Fragment() {
    private lateinit var tvTotal: TextView
    private lateinit var dropsContainer: LinearLayout
    private var initialized = false

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = ScrollView(context)
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }

        tvTotal = TextView(context).apply {
            text = "💰 今日总收入: 0 火"; textSize = 20f; setPadding(0,0,0,16)
        }
        main.addView(tvTotal)

        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(btn("📤 导出") { exportData() })
        btnRow.addView(btn("🗑️ 清零") { clearData() })
        main.addView(btnRow)

        main.addView(TextView(context).apply { text = "\n📦 今日掉落："; textSize = 16f })

        dropsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        main.addView(dropsContainer)

        root.addView(main)
        initialized = true
        refresh()
        return root
    }

    override fun onResume() { super.onResume(); refresh() }

    fun refresh() {
        if (!initialized || !::tvTotal.isInitialized) return
        tvTotal.text = "💰 今日总收入: ${DropRepository.totalFire} 火"
        dropsContainer.removeAllViews()
        if (DropRepository.todayDrops.isEmpty()) {
            dropsContainer.addView(TextView(context).apply { text = "暂无掉落"; setTextColor(0xFF888888.toInt()) })
            return
        }
        for (drop in DropRepository.todayDrops.sortedByDescending { it.quantity }) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8,8,8,8)
            }
            val totalText = if (drop.unitPrice >= 0) "=${drop.quantity * drop.unitPrice}火" else "未知"
            val nameView = TextView(context).apply {
                text = "${drop.name} [${drop.color}]"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            val qtyView = TextView(context).apply {
                text = "x${drop.quantity}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valView = TextView(context).apply {
                text = totalText
                textSize = 14f
                setTextColor(0xFFFFD700.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)
            row.addView(qtyView)
            row.addView(valView)
            dropsContainer.addView(row)
            dropsContainer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0xFF333333.toInt())
            })
        }
    }

    fun addDrop(name: String, price: Float, color: String) {
        refresh()
    }

    private fun btn(t: String, click: () -> Unit) = Button(context).apply {
        text = t; textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click() }
    }

    private fun clearData() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认清零")
            .setMessage("确定清空今日所有掉落记录吗？")
            .setPositiveButton("确定") { _, _ ->
                DropRepository.clear()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportData() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("火炬之光掉落记录 - $date")
        sb.appendLine("总收入: ${DropRepository.totalFire} 火")
        sb.appendLine("========================")
        for (drop in DropRepository.todayDrops) {
            val line = if (drop.unitPrice >= 0) {
                "${drop.name} [${drop.color}] x${drop.quantity} @${drop.unitPrice}火 = ${drop.quantity * drop.unitPrice}火"
            } else {
                "${drop.name} [${drop.color}] x${drop.quantity} @未知"
            }
            sb.appendLine(line)
        }
        try {
            val dir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, "drops_$date.txt")
            file.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "导出掉落记录"))
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

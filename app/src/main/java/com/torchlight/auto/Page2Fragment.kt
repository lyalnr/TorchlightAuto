package com.torchlight.auto

import android.app.AlertDialog
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.torchlight.auto.data.AppDatabase
import com.torchlight.auto.data.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Page2Fragment : Fragment() {
    private lateinit var scrollRoot: ScrollView
    private lateinit var container: LinearLayout
    private val allColors = listOf("红色", "金色", "紫色", "蓝色")
    private var allItems: List<ItemEntity> = emptyList()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 标题
        root.addView(TextView(context).apply {
            text = "📋 价格表 (单位: 火)\n"
            textSize = 18f
        })

        // 搜索框
        val etSearch = EditText(context).apply {
            hint = "🔍 搜索物品..."
            setPadding(16, 16, 16, 16)
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList(s?.toString() ?: "")
            }
        })
        root.addView(etSearch)

        // 手动添加按钮
        val btnAdd = Button(context).apply {
            text = "➕ 手动添加物品"
            setOnClickListener { showEditDialog(null) }
        }
        root.addView(btnAdd)

        // 列表容器
        scrollRoot = ScrollView(context)
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollRoot.addView(container)
        root.addView(scrollRoot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        refreshList()
        return root
    }

    override fun onResume() { super.onResume(); refreshList() }

    fun refreshList(filter: String = "") {
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).itemDao().getAll()
            }
            val filtered = if (filter.isBlank()) allItems else allItems.filter {
                it.name.contains(filter, ignoreCase = true)
            }
            container.removeAllViews()

            if (filtered.isEmpty()) {
                container.addView(TextView(context).apply {
                    text = "暂无物品"
                    setTextColor(0xFF888888.toInt())
                    setPadding(16, 32, 16, 16)
                })
                return@launch
            }

            for (item in filtered) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(if (item.enabled) 0xFF1A1A2E.toInt() else 0xFF0F0F1A.toInt())
                }

                // 第一行：启用CheckBox + 名称 + 价格
                val row1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val cbEnable = CheckBox(context).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        updateItem(item.copy(enabled = checked))
                    }
                }
                row1.addView(cbEnable)

                val nameView = TextView(context).apply {
                    text = item.name
                    textSize = 16f
                    setTextColor(AndroidColor.WHITE)
                    setPadding(8, 0, 8, 0)
                }
                row1.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))

                // 价格输入框
                val etPrice = EditText(context).apply {
                    hint = "火"
                    textSize = 14f
                    setText(if (item.price >= 0) item.price.toString() else "")
                    setPadding(8, 8, 8, 8)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                etPrice.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val priceStr = etPrice.text.toString().trim()
                        val newPrice = if (priceStr.isEmpty()) -1f else priceStr.toFloat()
                        updateItem(item.copy(price = newPrice))
                    }
                }
                row1.addView(etPrice)

                // 编辑按钮
                val btnEdit = Button(context).apply {
                    text = "编辑"
                    textSize = 11f
                    setOnClickListener { showEditDialog(item) }
                }
                row1.addView(btnEdit)

                card.addView(row1)

                // 第二行：颜色勾选（该物品只识别勾选的颜色）
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                }
                row2.addView(TextView(context).apply {
                    text = "识别颜色: "
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                })

                val itemColors = item.enabledColors.split(",").toSet()
                for (color in allColors) {
                    val cbColor = CheckBox(context).apply {
                        text = color
                        textSize = 11f
                        isChecked = color in itemColors
                        setOnCheckedChangeListener { _, _ ->
                            val newColors = allColors.filter { c ->
                                val cb = (row2.getChildAt(allColors.indexOf(c) + 1) as? CheckBox)
                                cb?.isChecked == true
                            }.joinToString(",")
                            updateItem(item.copy(enabledColors = newColors))
                        }
                    }
                    row2.addView(cbColor)
                }
                card.addView(row2)

                container.addView(card)
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0xFF333333.toInt())
                })
            }
        }
    }

    private fun updateItem(item: ItemEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).itemDao().update(item)
        }
    }

    private fun showEditDialog(item: ItemEntity?) {
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etName = EditText(ctx).apply {
            hint = "物品名称"
            setText(item?.name ?: "")
        }
        layout.addView(etName)

        val etPrice = EditText(ctx).apply {
            hint = "价格（火）"
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (item != null && item.price >= 0) item.price.toString() else "")
        }
        layout.addView(etPrice)

        AlertDialog.Builder(ctx)
            .setTitle(if (item == null) "添加物品" else "编辑物品")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val priceStr = etPrice.text.toString().trim()
                val price = if (priceStr.isEmpty()) -1f else priceStr.toFloat()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext())
                    if (item == null) {
                        db.itemDao().insert(ItemEntity(name = name, price = price, enabled = true))
                    } else {
                        db.itemDao().update(item.copy(name = name, price = price))
                    }
                    launch(Dispatchers.Main) { refreshList() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

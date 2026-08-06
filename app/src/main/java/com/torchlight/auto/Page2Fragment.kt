package com.torchlight.auto
import android.app.AlertDialog
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.torchlight.auto.data.AppDatabase
import com.torchlight.auto.data.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Page2Fragment : Fragment() {
    private lateinit var container: LinearLayout
    private val colors = listOf("红色","金色","紫色","蓝色","白色","未知")

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View? {
        val root = ScrollView(context)
        container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        root.addView(container)
        refreshList()
        return root
    }

    override fun onResume() { super.onResume(); refreshList() }

    fun refreshList() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).itemDao().getAll()
            }
            container.removeAllViews()
            container.addView(TextView(context).apply { text = "📋 价格表 (单位: 火)\n"; textSize = 18f })

            val btnAdd = Button(context).apply {
                text = "➕ 手动添加物品"
                setOnClickListener { showEditDialog(null) }
            }
            container.addView(btnAdd)

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeMiniBtn("全选") { setAllEnabled(true) })
            row.addView(makeMiniBtn("全不选") { setAllEnabled(false) })
            row.addView(makeMiniBtn("删未知") { deleteUnknown() })
            container.addView(row)

            for (item in items) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12,12,12,12)
                    setBackgroundColor(if (item.enabled) 0xFF1A1A2E.toInt() else 0xFF0F0F1A.toInt())
                }

                val cb = CheckBox(context).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            AppDatabase.getDatabase(requireContext()).itemDao()
                                .update(item.copy(enabled = checked))
                        }
                    }
                }
                card.addView(cb)

                val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(12,0,0,0) }
                val nameColor = when(item.color) {
                    "红色" -> AndroidColor.parseColor("#FF4444")
                    "金色" -> AndroidColor.parseColor("#FFD700")
                    "紫色" -> AndroidColor.parseColor("#DDA0DD")
                    "蓝色" -> AndroidColor.parseColor("#87CEEB")
                    else -> AndroidColor.WHITE
                }
                info.addView(TextView(context).apply {
                    text = "${item.name} [${item.color}]"
                    setTextColor(nameColor); textSize = 15f
                })
                val priceText = if (item.price >= 0) "${item.price} 火" else "价格未知"
                info.addView(TextView(context).apply {
                    text = priceText; textSize = 13f; setTextColor(0xFFAAAAAA.toInt())
                })
                card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val btnEdit = Button(context).apply {
                    text = "编辑"; textSize = 11f
                    setOnClickListener { showEditDialog(item) }
                }
                card.addView(btnEdit)

                container.addView(card)
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
                    setBackgroundColor(0xFF333333.toInt())
                })
            }
        }
    }

    private fun makeMiniBtn(t: String, click: () -> Unit) = Button(context).apply {
        text = t; textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { click(); refreshList() }
    }

    private fun setAllEnabled(v: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.itemDao().getAll().forEach { db.itemDao().update(it.copy(enabled = v)) }
            launch(Dispatchers.Main) { refreshList() }
        }
    }

    private fun deleteUnknown() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            db.itemDao().getAll().filter { it.price < 0 }.forEach { db.itemDao().delete(it) }
            launch(Dispatchers.Main) { refreshList() }
        }
    }

    private fun showEditDialog(item: ItemEntity?) {
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }

        val etName = EditText(ctx).apply { hint = "物品名称"; setText(item?.name ?: "") }
        layout.addView(etName)

        val etPrice = EditText(ctx).apply {
            hint = "价格（火）"; inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (item != null && item.price >= 0) item.price.toString() else "")
        }
        layout.addView(etPrice)

        val spColor = Spinner(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, colors)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spColor.adapter = adapter
        spColor.setSelection(colors.indexOf(item?.color ?: "未知"))
        layout.addView(spColor)

        AlertDialog.Builder(ctx)
            .setTitle(if (item == null) "添加物品" else "编辑物品")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val priceStr = etPrice.text.toString().trim()
                val price = if (priceStr.isEmpty()) -1f else priceStr.toFloat()
                val color = spColor.selectedItem.toString()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext())
                    if (item == null) {
                        db.itemDao().insert(ItemEntity(name = name, price = price, color = color, enabled = true))
                    } else {
                        db.itemDao().update(item.copy(name = name, price = price, color = color))
                    }
                    launch(Dispatchers.Main) { refreshList() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

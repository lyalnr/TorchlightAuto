package com.torchlight.auto
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
    private val entries = mutableListOf<LogEntry>()
    fun addEntry(e: LogEntry) { entries.add(0, e); if(entries.size>100)entries.removeAt(entries.lastIndex); notifyItemInserted(0) }
    fun getTotalFire() = entries.sumOf { it.fireValue }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_2, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) { h.text1.text = "${entries[p].item} x${entries[p].quantity} +${entries[p].fireValue}火"; h.text2.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entries[p].timestamp)) + entries[p].rawLine.take(20) }
    override fun getItemCount() = entries.size
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val text1 = v.findViewById<TextView>(android.R.id.text1); val text2 = v.findViewById<TextView>(android.R.id.text2) }
}

package com.torchlight.auto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val entries = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addEntry(entry: LogEntry) {
        entries.add(0, entry)
        if (entries.size > 100) entries.removeAt(entries.lastIndex)
        notifyItemInserted(0)
    }

    fun getTotalFire(): Int {
        return entries.sumOf { it.fireValue }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val time = dateFormat.format(Date(entry.timestamp))
        holder.text1.text = "${entry.item} x${entry.quantity}  +${entry.fireValue}火"
        holder.text2.text = "$time  ${entry.rawLine.take(30)}"
    }

    override fun getItemCount() = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }
}

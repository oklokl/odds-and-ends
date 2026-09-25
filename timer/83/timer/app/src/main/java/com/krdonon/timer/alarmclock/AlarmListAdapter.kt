package com.krdonon.timer.alarmclock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.krdonon.timer.R
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/**
 * Alarm list adapter.
 *
 * IMPORTANT:
 * - This project’s item layout uses ids: tvTime, tvDays, swEnabled (and optional checkbox).
 * - Do NOT reference non-existing ids (e.g., timeText12h/daysText) because it breaks compilation.
 */
class AlarmListAdapter(
    private val items: MutableList<AlarmItem>,
    private val listener: Listener
) : RecyclerView.Adapter<AlarmListAdapter.VH>() {

    interface Listener {
        fun onItemClick(item: AlarmItem)
        fun onItemLongClick(item: AlarmItem): Boolean
        fun onEnabledChanged(item: AlarmItem, enabled: Boolean)
        fun onSelectionChanged(selectedCount: Int)
    }

    private val selectedIds = LinkedHashSet<Long>()
    var selectionMode: Boolean = false
        private set

    fun setData(newItems: List<AlarmItem>) {
        items.clear()
        items.addAll(newItems)
        selectedIds.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    fun selectedIds(): Set<Long> = selectedIds.toSet()
    fun selectedCount(): Int = selectedIds.size

    fun clearSelection(keepSelectionMode: Boolean = false) {
        selectedIds.clear()
        selectionMode = keepSelectionMode
        notifyDataSetChanged()
        listener.onSelectionChanged(0)
    }


    fun selectAll() {
        selectedIds.clear()
        items.forEach { selectedIds.add(it.id) }
        selectionMode = true
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    fun toggleSelection(item: AlarmItem) {
        if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
        selectionMode = selectedIds.isNotEmpty()
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        val timeText = formatTime12h(item.hour24, item.minute)
        holder.tvTime.text =
            if (item.label.isNotBlank()) "${item.label}  $timeText" else timeText

        holder.tvDays.text = formatDays(item.days)

        // Enabled switch
        holder.swEnabled.setOnCheckedChangeListener(null)
        holder.swEnabled.isChecked = item.enabled
        holder.swEnabled.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            listener.onEnabledChanged(item, checked)
        }

        // Optional checkbox (some layouts may have it). If not present, ignore.
        holder.cbSelect?.let { cb ->
            cb.visibility = if (selectionMode) View.VISIBLE else View.INVISIBLE
            cb.isChecked = selectedIds.contains(item.id)
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
            } else {
                listener.onItemClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            val handled = listener.onItemLongClick(item)
            // enter selection mode and select this item
            toggleSelection(item)
            handled
        }
    }

    private fun formatDays(days: BooleanArray): String {
        val names = listOf("일", "월", "화", "수", "목", "금", "토")
        val enabled = names.filterIndexed { idx, _ -> idx < days.size && days[idx] }
        return if (enabled.isEmpty()) "요일 없음" else enabled.joinToString(" ")
    }

    private fun formatTime12h(hour24: Int, minute: Int): String {
        val am = hour24 < 12
        val h12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
        val mm = String.format(Locale.getDefault(), "%02d", minute)
        return (if (am) "오전 " else "오후 ") + h12.toString() + ":" + mm
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val tvDays: TextView = v.findViewById(R.id.tvDays)
        val swEnabled: SwitchMaterial = v.findViewById(R.id.swEnabled)

        // Optional checkbox id could be "cbSelect" in some versions. If not, keep null.
        val cbSelect: android.widget.CheckBox? = runCatching { v.findViewById<android.widget.CheckBox>(R.id.cbSelect) }.getOrNull()
    }
}

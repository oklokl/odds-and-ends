package com.krdonon.timer.alarmclock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.krdonon.timer.R
import java.util.Locale

/**
 * RecyclerView adapter that renders:
 * - Group headers (with a master enable/disable switch)
 * - Alarm rows (selectable in selection mode)
 */
class AlarmGroupedAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onAlarmClick(item: AlarmItem)
        fun onAlarmLongClick(item: AlarmItem): Boolean
        fun onAlarmEnabledToggle(item: AlarmItem, enabled: Boolean)

        fun onGroupEnabledToggle(groupId: Long, enabled: Boolean)
    }

    sealed class Row {
        data class Header(
            val groupId: Long,
            val title: String,
            val count: Int,
            val allEnabled: Boolean
        ) : Row()

        data class AlarmRow(val item: AlarmItem) : Row()
    }

    private val rows = mutableListOf<Row>()

    // Selection
    var selectionMode: Boolean = false
        private set
    private val selectedIds = mutableSetOf<Long>()

    fun selectedCount(): Int = selectedIds.size
    fun selectedIds(): Set<Long> = selectedIds.toSet()

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
        listenerSelectionChanged()
    }

    fun toggleSelection(id: Long) {
        if (!selectionMode) return
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        notifyDataSetChanged()
        listenerSelectionChanged()
    }

    fun selectAllAlarms() {
        if (!selectionMode) selectionMode = true
        selectedIds.clear()
        rows.forEach {
            if (it is Row.AlarmRow) selectedIds.add(it.item.id)
        }
        notifyDataSetChanged()
        listenerSelectionChanged()
    }

    fun clearSelection(keepSelectionMode: Boolean = false) {
        selectedIds.clear()
        selectionMode = keepSelectionMode
        notifyDataSetChanged()
        listenerSelectionChanged()
    }

    // Data binding
    fun setData(alarms: List<AlarmItem>, groups: List<GroupItem>) {
        rows.clear()

        // Build group sections. Always show groups, even if empty.
        val alarmsByGroup = alarms.groupBy { it.groupId }
        for (g in groups.sortedBy { it.id }) {
            val list = alarmsByGroup[g.id].orEmpty().sortedBy { it.id }
            val allEnabled = list.isNotEmpty() && list.all { it.enabled }
            rows.add(Row.Header(g.id, g.name, list.size, allEnabled))
            list.forEach { rows.add(Row.AlarmRow(it)) }
        }

        // Ungrouped
        val ungrouped = alarmsByGroup[0L].orEmpty().sortedBy { it.id }
        if (ungrouped.isNotEmpty()) {
            val allEnabled = ungrouped.all { it.enabled }
            rows.add(Row.Header(0L, "기타", ungrouped.size, allEnabled))
            ungrouped.forEach { rows.add(Row.AlarmRow(it)) }
        }

        // If there are no groups and no ungrouped, show nothing (empty state handled by fragment)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> 0
            is Row.AlarmRow -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inflater.inflate(R.layout.item_group_header, parent, false))
            else -> AlarmVH(inflater.inflate(R.layout.item_alarm, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row)
            is Row.AlarmRow -> (holder as AlarmVH).bind(row.item)
        }
    }

    override fun getItemCount(): Int = rows.size

    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTitle: TextView = v.findViewById(R.id.tvGroupTitle)
        private val tvCount: TextView = v.findViewById(R.id.tvGroupCount)
        private val sw: SwitchMaterial = v.findViewById(R.id.swGroupEnabled)

        fun bind(h: Row.Header) {
            tvTitle.text = h.title
            tvCount.text = h.count.toString()

            sw.setOnCheckedChangeListener(null)
            sw.isChecked = h.allEnabled
            sw.isEnabled = h.count > 0
            sw.setOnCheckedChangeListener { _, isChecked ->
                listener.onGroupEnabledToggle(h.groupId, isChecked)
            }
        }
    }

    private inner class AlarmVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvTime: TextView = v.findViewById(R.id.tvTime)
        private val tvDays: TextView = v.findViewById(R.id.tvDays)
        private val swEnabled: SwitchMaterial = v.findViewById(R.id.swEnabled)
        private val cb: MaterialCheckBox = v.findViewById(R.id.cbSelect)

        fun bind(item: AlarmItem) {
            // Label + time
            val timeText = formatTime12h(item.hour24, item.minute)
            tvTime.text = if (item.label.isNotBlank()) "${item.label}  $timeText" else timeText

            tvDays.text = formatDays(item.days)

            cb.visibility = if (selectionMode) View.VISIBLE else View.GONE
            cb.isChecked = selectedIds.contains(item.id)

            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item.id)
                } else {
                    listener.onAlarmClick(item)
                }
            }

            itemView.setOnLongClickListener {
                val consumed = listener.onAlarmLongClick(item)
                if (consumed) {
                    setSelectionMode(true)
                    toggleSelection(item.id)
                }
                true
            }

            swEnabled.setOnCheckedChangeListener(null)
            swEnabled.isChecked = item.enabled
            swEnabled.setOnCheckedChangeListener { _, isChecked ->
                listener.onAlarmEnabledToggle(item, isChecked)
            }
        }
    }

    private fun formatTime12h(hour24: Int, minute: Int): String {
        val ampm = if (hour24 < 12) "오전" else "오후"
        val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
        return String.format(Locale.getDefault(), "%s %d:%02d", ampm, hour12, minute)
    }

    private fun formatDays(days: BooleanArray): String {
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        val selected = (0..6).filter { idx -> days.getOrNull(idx) == true }.map { names[it] }
        return if (selected.isEmpty()) "" else selected.joinToString(" ")
    }

    // Fragment can override by setting a callback through listenerSelectionChanged hook; kept simple here.
    private var onSelectionChanged: ((Int) -> Unit)? = null
    fun setOnSelectionChanged(cb: (Int) -> Unit) { onSelectionChanged = cb }
    private fun listenerSelectionChanged() { onSelectionChanged?.invoke(selectedIds.size) }
}

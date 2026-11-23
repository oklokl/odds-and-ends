package com.krdonon.timer.alarmclock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.krdonon.timer.R

class AlarmListFragment : Fragment() {

    private lateinit var store: AlarmStore
    private lateinit var groupStore: GroupStore
    private lateinit var adapter: AlarmGroupedAdapter

    private var actionMode: ActionMode? = null
    private var emptyView: View? = null
    private var recycler: RecyclerView? = null

    // When we clear selection intentionally (e.g., select-all toggle), keep the contextual UI.
    private var keepActionModeWhenSelectionBecomesZero: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_alarm_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        store = AlarmStore(requireContext())
        groupStore = GroupStore(requireContext())

        emptyView = view.findViewById(R.id.emptyView)
        recycler = view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
        }

        adapter = AlarmGroupedAdapter(
            listener = object : AlarmGroupedAdapter.Listener {
                override fun onAlarmClick(item: AlarmItem) {
                    if (adapter.selectionMode) {
                        adapter.toggleSelection(item.id)
                    } else {
                        startActivity(AlarmEditActivity.newIntent(requireContext(), item.id))
                    }
                }

                override fun onAlarmLongClick(item: AlarmItem): Boolean {
                    if (actionMode == null) {
                        actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity)
                            .startSupportActionMode(actionCallback)
                    }
                    return true
                }

                override fun onAlarmEnabledToggle(item: AlarmItem, enabled: Boolean) {
                    val updated = item.copy(enabled = enabled)
                    store.upsert(updated)
                    if (enabled) AlarmScheduler.scheduleNext(requireContext(), updated)
                    else AlarmScheduler.cancel(requireContext(), updated.id)
                    refresh()
                }

                override fun onGroupEnabledToggle(groupId: Long, enabled: Boolean) {
                    val alarms = store.getAll().filter { it.groupId == groupId }
                    if (alarms.isEmpty()) return

                    alarms.forEach { a ->
                        val updated = a.copy(enabled = enabled)
                        store.upsert(updated)
                        if (enabled) AlarmScheduler.scheduleNext(requireContext(), updated)
                        else AlarmScheduler.cancel(requireContext(), updated.id)
                    }
                    refresh()
                }
            }
        ).also { a ->
            a.setOnSelectionChanged { selectedCount ->
                onSelectionChanged(selectedCount)
            }
        }

        recycler?.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            startActivity(AlarmEditActivity.newIntent(requireContext(), null))
        }

        view.findViewById<FloatingActionButton>(R.id.fabGroups).setOnClickListener {
            showGroupManager()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val alarms = store.getAll().sortedWith(compareBy({ it.hour24 }, { it.minute }))
        val groups = groupStore.getAll()

        adapter.setData(alarms, groups)

        val hasRows = alarms.isNotEmpty() || groups.isNotEmpty()
        recycler?.visibility = if (hasRows) View.VISIBLE else View.GONE
        emptyView?.visibility = if (hasRows) View.GONE else View.VISIBLE
    }

    private fun onSelectionChanged(selectedCount: Int) {
        if (selectedCount == 0) {
            if (keepActionModeWhenSelectionBecomesZero) {
                keepActionModeWhenSelectionBecomesZero = false
                updateActionModeTitle()
                actionMode?.invalidate()
                return
            }
            actionMode?.finish()
        } else {
            updateActionModeTitle()
            actionMode?.invalidate()
        }
    }

    private fun updateActionModeTitle() {
        actionMode?.title = "${adapter.selectedCount()}개 선택됨"
    }

    private val actionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_alarm_selection, menu)
            adapter.setSelectionMode(true)
            updateActionModeTitle()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Toggle title between select all / clear all based on current selection.
            val selectAllItem = menu.findItem(R.id.action_select_all)
            val total = store.getAll().size
            val allSelected = total > 0 && adapter.selectedCount() == total
            selectAllItem?.title = if (allSelected) "전체 해제" else "전체 선택"
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_select_all -> {
                    val totalAlarmRows = store.getAll().size
                    val allSelected = totalAlarmRows > 0 && adapter.selectedCount() == totalAlarmRows
                    if (allSelected) {
                        keepActionModeWhenSelectionBecomesZero = true
                        adapter.clearSelection(keepSelectionMode = true)
                        updateActionModeTitle()
                        mode.invalidate()
                    } else {
                        adapter.selectAllAlarms()
                        updateActionModeTitle()
                        mode.invalidate()
                    }
                    true
                }

                R.id.action_move_group -> {
                    showMoveToGroupDialog()
                    true
                }

                R.id.action_delete -> {
                    confirmDeleteSelected()
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.setSelectionMode(false)
            actionMode = null
        }
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return

        AlertDialog.Builder(requireContext())
            .setTitle("삭제")
            .setMessage("선택한 알람을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                // Cancel scheduled alarms
                ids.forEach { AlarmScheduler.cancel(requireContext(), it) }
                store.delete(ids)
                actionMode?.finish()
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMoveToGroupDialog() {
        val selected = adapter.selectedIds()
        if (selected.isEmpty()) return

        val groups = groupStore.getAll().sortedBy { it.id }.toMutableList()
        val names = mutableListOf<String>()
        val ids = mutableListOf<Long>()

        // "Ungroup"
        names.add("기타 (그룹 해제)")
        ids.add(0L)

        groups.forEach {
            names.add(it.name)
            ids.add(it.id)
        }

        names.add("새 그룹 만들기…")
        ids.add(-1L)

        AlertDialog.Builder(requireContext())
            .setTitle("그룹으로 이동")
            .setItems(names.toTypedArray()) { _, which ->
                val targetId = ids[which]
                if (targetId == -1L) {
                    promptCreateGroup { newGroupId ->
                        moveSelectedToGroup(selected, newGroupId)
                    }
                } else {
                    moveSelectedToGroup(selected, targetId)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun moveSelectedToGroup(selectedIds: Set<Long>, groupId: Long) {
        val all = store.getAll()
        selectedIds.forEach { id ->
            val item = all.firstOrNull { it.id == id } ?: return@forEach
            store.upsert(item.copy(groupId = groupId))
        }
        actionMode?.finish()
        refresh()
    }

    private fun showGroupManager() {
        val groups = groupStore.getAll().sortedBy { it.id }

        val options = mutableListOf<String>()
        val ids = mutableListOf<Long>()

        groups.forEach {
            options.add(it.name)
            ids.add(it.id)
        }
        options.add("새 그룹 만들기…")
        ids.add(-1L)

        AlertDialog.Builder(requireContext())
            .setTitle("그룹 관리")
            .setItems(options.toTypedArray()) { _, which ->
                val gid = ids[which]
                if (gid == -1L) {
                    promptCreateGroup { refresh() }
                } else {
                    showGroupActions(gid)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showGroupActions(groupId: Long) {
        val g = groupStore.getById(groupId) ?: return
        val actions = arrayOf("이름 변경", "삭제")
        AlertDialog.Builder(requireContext())
            .setTitle(g.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> promptRenameGroup(g)
                    1 -> confirmDeleteGroup(g)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun promptCreateGroup(onCreated: (Long) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = "그룹 이름"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("새 그룹")
            .setView(input)
            .setPositiveButton("생성") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val id = groupStore.nextId()
                groupStore.upsert(GroupItem(id = id, name = name))
                onCreated(id)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun promptRenameGroup(group: GroupItem) {
        val input = EditText(requireContext()).apply {
            setText(group.name)
            setSelection(group.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("이름 변경")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                groupStore.upsert(group.copy(name = name))
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDeleteGroup(group: GroupItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("그룹 삭제")
            .setMessage("그룹 '${group.name}'을 삭제할까요?\n알람은 '기타'로 이동됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                // move alarms to ungrouped
                val all = store.getAll()
                all.filter { it.groupId == group.id }.forEach { a ->
                    store.upsert(a.copy(groupId = 0L))
                }
                groupStore.delete(group.id)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

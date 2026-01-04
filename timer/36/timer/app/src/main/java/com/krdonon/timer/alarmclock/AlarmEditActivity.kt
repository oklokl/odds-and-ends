package com.krdonon.timer.alarmclock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.krdonon.timer.R
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ScrollView

class AlarmEditActivity : AppCompatActivity() {

    private lateinit var store: AlarmStore

    private var alarmId: Long? = null
    private var original: AlarmItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_edit)

        // 버튼 네비게이션(3버튼)에서도 하단 저장/취소 영역이 시스템 네비게이션 바에 가리지 않도록 insets 적용
        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        store = AlarmStore(this)
        alarmId = intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }

        val tp = findViewById<TimePicker>(R.id.timePicker)
        tp.setIs24HourView(false)

        val dayGroup = findViewById<MaterialButtonToggleGroup>(R.id.dayGroup)
        val btnEveryWeek = findViewById<MaterialButton>(R.id.btnEveryWeek)
        val etLabel = findViewById<EditText>(R.id.etLabel)
        val swSound = findViewById<SwitchMaterial>(R.id.swSound)
        val swVibrate = findViewById<SwitchMaterial>(R.id.swVibrate)
        val swEnabled = findViewById<SwitchMaterial>(R.id.swEnabled)
        val rgSnoozeMin = findViewById<RadioGroup>(R.id.rgSnoozeMinutes)
        val rgSnoozeCount = findViewById<RadioGroup>(R.id.rgSnoozeCount)

        val dayIds = listOf(
            R.id.daySun,
            R.id.dayMon,
            R.id.dayTue,
            R.id.dayWed,
            R.id.dayThu,
            R.id.dayFri,
            R.id.daySat
        )

        fun updateEveryWeekUi() {
            val allChecked = dayIds.all { id -> dayGroup.checkedButtonIds.contains(id) }
            // 선택 상태를 시각적으로만 표현 (버튼 자체는 토글그룹에 속하지 않음)
            btnEveryWeek.isSelected = allChecked
            btnEveryWeek.alpha = if (allChecked) 1.0f else 0.6f
        }

        // 요일 토글이 바뀌면 '매주' 버튼 상태도 같이 갱신
        dayGroup.addOnButtonCheckedListener { _, _, _ ->
            updateEveryWeekUi()
        }

        // '매주' 버튼: 전체 선택/전체 해제 토글
        btnEveryWeek.setOnClickListener {
            val allChecked = dayIds.all { id -> dayGroup.checkedButtonIds.contains(id) }
            if (allChecked) {
                dayIds.forEach { id -> dayGroup.uncheck(id) }
            } else {
                dayIds.forEach { id -> dayGroup.check(id) }
            }
            updateEveryWeekUi()
        }

        original = alarmId?.let { store.getById(it) }
        original?.let { item ->
            tp.hour = item.hour24
            tp.minute = item.minute
            swSound.isChecked = item.soundEnabled
            swVibrate.isChecked = item.vibrateEnabled
            swEnabled.isChecked = item.enabled
            etLabel.setText(item.label)

            dayIds.forEachIndexed { idx, id ->
                if (item.days[idx]) dayGroup.check(id) else dayGroup.uncheck(id)
            }

            rgSnoozeMin.check(if (item.snoozeMinutes == 10) R.id.snooze10 else R.id.snooze5)
            rgSnoozeCount.check(if (item.snoozeCount == 5) R.id.snoozeCount5 else R.id.snoozeCount3)
        } ?: run {
            swSound.isChecked = true
            swVibrate.isChecked = true
            swEnabled.isChecked = true
            rgSnoozeMin.check(R.id.snooze5)
            rgSnoozeCount.check(R.id.snoozeCount3)
        }

        // 초기 상태 반영
        updateEveryWeekUi()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val days = BooleanArray(7) { false }
            dayIds.forEachIndexed { idx, id ->
                days[idx] = dayGroup.checkedButtonIds.contains(id)
            }

            val snoozeMinutes = if (rgSnoozeMin.checkedRadioButtonId == R.id.snooze10) 10 else 5
            val snoozeCount = if (rgSnoozeCount.checkedRadioButtonId == R.id.snoozeCount5) 5 else 3

            val id = alarmId ?: store.nextId()
            val item = AlarmItem(
                id = id,
                hour24 = tp.hour,
                minute = tp.minute,
                days = days,
                enabled = swEnabled.isChecked,
                label = etLabel.text?.toString()?.trim().orEmpty(),
                soundEnabled = swSound.isChecked,
                vibrateEnabled = swVibrate.isChecked,
                snoozeMinutes = snoozeMinutes,
                snoozeCount = snoozeCount,
                // 기존 알람 수정 시에는 기존 그룹 유지, 신규는 '기타(0L)'
                groupId = original?.groupId ?: 0L
            )

            store.upsert(item)
            if (item.enabled) AlarmScheduler.scheduleNext(this, item) else AlarmScheduler.cancel(this, item.id)
            finish()
        }

        findViewById<android.view.View>(R.id.btnCancel).setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_ID = "extra_alarm_id"

        fun newIntent(context: Context, alarmId: Long?): Intent =
            Intent(context, AlarmEditActivity::class.java).apply {
                if (alarmId != null) putExtra(EXTRA_ID, alarmId)
            }
    }
}
package com.krdonon.timer.alarmclock

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.krdonon.timer.alarm.AlarmSoundPrefs
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
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.widget.RadioButton
import android.widget.NumberPicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AlarmEditActivity : AppCompatActivity() {

    private lateinit var store: AlarmStore

    private var alarmId: Long? = null
    private var original: AlarmItem? = null

    private var customSnoozeMinutes: Int = 5
    private var lastSnoozeRadioId: Int = R.id.snooze5

    private lateinit var tvNowDateTime: TextView
    private val nowFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE a h:mm:ss", Locale.KOREAN)

    private val nowHandler: Handler = Handler(Looper.getMainLooper())
    private val nowTicker: Runnable = object : Runnable {
        override fun run() {
            updateNowDateTimeUi()
            nowHandler.postDelayed(this, 1000L)
        }
    }

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

        tvNowDateTime = findViewById(R.id.tvNowDateTime)
        updateNowDateTimeUi()

        val dayGroup = findViewById<MaterialButtonToggleGroup>(R.id.dayGroup)
        val btnCalendar = findViewById<MaterialButton>(R.id.btnCalendar)
        val btnEveryWeek = findViewById<MaterialButton>(R.id.btnEveryWeek)
        val etLabel = findViewById<EditText>(R.id.etLabel)
        val swSound = findViewById<SwitchMaterial>(R.id.swSound)
        val swVibrate = findViewById<SwitchMaterial>(R.id.swVibrate)
        val swEnabled = findViewById<SwitchMaterial>(R.id.swEnabled)
        val rgSnoozeMin = findViewById<RadioGroup>(R.id.rgSnoozeMinutes)
        val rgSnoozeCount = findViewById<RadioGroup>(R.id.rgSnoozeCount)
        val rbSnoozeCustom = findViewById<RadioButton>(R.id.snoozeCustom)

        // ---- 알람 소리 변경 (Downloads mp3 선택) ----
        val btnPickSound = findViewById<ImageButton>(R.id.btnPickSound)
        val tvChangeSound = findViewById<TextView>(R.id.tvChangeSound)
        val tvSelectedSound = findViewById<TextView>(R.id.tvSelectedSound)

        fun refreshSelectedSoundUi() {
            val uri = AlarmSoundPrefs.getReadableCustomUriOrNull(this)
            if (uri != null) {
                val name = AlarmSoundPrefs.getCustomName(this)
                    ?: AlarmSoundPrefs.resolveDisplayName(this, uri)
                    ?: uri.toString()
                tvSelectedSound.text = getString(R.string.alarm_sound_current_custom, name)
            } else {
                tvSelectedSound.text = getString(R.string.alarm_sound_current_default)
            }
        }

        // 샘플 mp3를 Downloads에 1회 설치 (API 29+) 후, 아직 사용자 선택이 없으면 샘플을 기본 선택
        val sampleUri = SampleSoundInstaller.ensureInstalled(this)
        if (AlarmSoundPrefs.getCustomUri(this) == null && sampleUri != null) {
            val name = AlarmSoundPrefs.resolveDisplayName(this, sampleUri) ?: "sample_alarm.mp3"
            AlarmSoundPrefs.setCustom(this, sampleUri, name)
        }
        refreshSelectedSoundUi()

        val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    AlarmSoundPrefs.persistReadPermission(this, uri)
                    val name = AlarmSoundPrefs.resolveDisplayName(this, uri)
                    AlarmSoundPrefs.setCustom(this, uri, name)
                    refreshSelectedSoundUi()
                    Toast.makeText(this, "알람 소리 설정됨", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun launchSoundPicker() {
            val initialUri = runCatching {
                // Downloads 폴더로 최대한 유도 (파일 선택 UI에 따라 무시될 수 있음)
                Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
            }.getOrNull()

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                if (initialUri != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
            pickSoundLauncher.launch(intent)
        }

        btnPickSound.setOnClickListener { launchSoundPicker() }
        tvChangeSound.setOnClickListener { launchSoundPicker() }

        val resetListener = android.view.View.OnLongClickListener {
            AlarmSoundPrefs.clearCustom(this)
            refreshSelectedSoundUi()
            Toast.makeText(this, "기본 소리로 복원", Toast.LENGTH_SHORT).show()
            true
        }
        btnPickSound.setOnLongClickListener(resetListener)
        tvChangeSound.setOnLongClickListener(resetListener)



        var suppressSnoozeUiCallback = true

        // 사용자 설정(1~60분) 선택 시 NumberPicker 다이얼로그 표시
        rgSnoozeMin.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSnoozeUiCallback) {
                lastSnoozeRadioId = checkedId
                return@setOnCheckedChangeListener
            }
            if (checkedId == R.id.snoozeCustom) {
                val picker = NumberPicker(this).apply {
                    minValue = 1
                    maxValue = 60
                    value = customSnoozeMinutes.coerceIn(1, 60)
                    wrapSelectorWheel = false
                }

                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.snooze_custom_title))
                    .setMessage(getString(R.string.snooze_custom_message))
                    .setView(picker)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        customSnoozeMinutes = picker.value.coerceIn(1, 60)
                        rbSnoozeCustom.text = getString(R.string.snooze_custom_fmt, customSnoozeMinutes)
                        // 사용자 설정을 그대로 유지
                        lastSnoozeRadioId = R.id.snoozeCustom
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        // 취소 시 이전 선택으로 복귀
                        rgSnoozeMin.check(lastSnoozeRadioId)
                    }
                    .setOnCancelListener {
                        rgSnoozeMin.check(lastSnoozeRadioId)
                    }
                    .show()
            } else {
                lastSnoozeRadioId = checkedId
            }
        }


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

        // '달력' 버튼: 상단 팝업(미니 달력) 표시
        btnCalendar.setOnClickListener {
            MiniCalendarDialogFragment.newInstance().show(supportFragmentManager, "mini_calendar")
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

            // 다시 울림(알람이 한 번 울리는 최대 지속시간) 분 선택
            when (item.snoozeMinutes) {
                5 -> rgSnoozeMin.check(R.id.snooze5)
                10 -> rgSnoozeMin.check(R.id.snooze10)
                15 -> rgSnoozeMin.check(R.id.snooze15)
                20 -> rgSnoozeMin.check(R.id.snooze20)
                30 -> rgSnoozeMin.check(R.id.snooze30)
                else -> {
                    customSnoozeMinutes = item.snoozeMinutes.coerceIn(1, 60)
                    val rbSnoozeCustom = findViewById<RadioButton>(R.id.snoozeCustom)

                    // ---- 알람 소리 변경 (Downloads mp3 선택) ----
                    val btnPickSound = findViewById<ImageButton>(R.id.btnPickSound)
                    val tvChangeSound = findViewById<TextView>(R.id.tvChangeSound)
                    val tvSelectedSound = findViewById<TextView>(R.id.tvSelectedSound)

                    fun refreshSelectedSoundUi() {
                        val uri = AlarmSoundPrefs.getReadableCustomUriOrNull(this)
                        if (uri != null) {
                            val name = AlarmSoundPrefs.getCustomName(this)
                                ?: AlarmSoundPrefs.resolveDisplayName(this, uri)
                                ?: uri.toString()
                            tvSelectedSound.text = getString(R.string.alarm_sound_current_custom, name)
                        } else {
                            tvSelectedSound.text = getString(R.string.alarm_sound_current_default)
                        }
                    }

                    // 샘플 mp3를 Downloads에 1회 설치 (API 29+) 후, 아직 사용자 선택이 없으면 샘플을 기본 선택
                    val sampleUri = SampleSoundInstaller.ensureInstalled(this)
                    if (AlarmSoundPrefs.getCustomUri(this) == null && sampleUri != null) {
                        val name = AlarmSoundPrefs.resolveDisplayName(this, sampleUri) ?: "sample_alarm.mp3"
                        AlarmSoundPrefs.setCustom(this, sampleUri, name)
                    }
                    refreshSelectedSoundUi()

                    val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        if (result.resultCode == Activity.RESULT_OK) {
                            val uri: Uri? = result.data?.data
                            if (uri != null) {
                                AlarmSoundPrefs.persistReadPermission(this, uri)
                                val name = AlarmSoundPrefs.resolveDisplayName(this, uri)
                                AlarmSoundPrefs.setCustom(this, uri, name)
                                refreshSelectedSoundUi()
                                Toast.makeText(this, "알람 소리 설정됨", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    fun launchSoundPicker() {
                        val initialUri = runCatching {
                            // Downloads 폴더로 최대한 유도 (파일 선택 UI에 따라 무시될 수 있음)
                            Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
                        }.getOrNull()

                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            if (initialUri != null) {
                                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                            }
                        }
                        pickSoundLauncher.launch(intent)
                    }

                    btnPickSound.setOnClickListener { launchSoundPicker() }
                    tvChangeSound.setOnClickListener { launchSoundPicker() }

                    val resetListener = android.view.View.OnLongClickListener {
                        AlarmSoundPrefs.clearCustom(this)
                        refreshSelectedSoundUi()
                        Toast.makeText(this, "기본 소리로 복원", Toast.LENGTH_SHORT).show()
                        true
                    }
                    btnPickSound.setOnLongClickListener(resetListener)
                    tvChangeSound.setOnLongClickListener(resetListener)



                    var suppressSnoozeUiCallback = true
                    rbSnoozeCustom.text = getString(R.string.snooze_custom_fmt, customSnoozeMinutes)
                    rgSnoozeMin.check(R.id.snoozeCustom)
                }
            }
            lastSnoozeRadioId = rgSnoozeMin.checkedRadioButtonId
            rgSnoozeCount.check(
                when {
                    // 과거/부분 적용 버전에서 1이 저장된 케이스도 "계속"으로 보이도록 처리
                    item.snoozeCount <= 1 -> R.id.snoozeCountForever
                    item.snoozeCount == 5 -> R.id.snoozeCount5
                    else -> R.id.snoozeCount3
                }
            )
        } ?: run {
            swSound.isChecked = true
            swVibrate.isChecked = true
            swEnabled.isChecked = true
            rgSnoozeMin.check(R.id.snooze5)
            lastSnoozeRadioId = R.id.snooze5
            customSnoozeMinutes = 5
            rgSnoozeCount.check(R.id.snoozeCount3)
        }

        suppressSnoozeUiCallback = false

        // 초기 상태 반영
        updateEveryWeekUi()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val days = BooleanArray(7) { false }
            dayIds.forEachIndexed { idx, id ->
                days[idx] = dayGroup.checkedButtonIds.contains(id)
            }

            val snoozeMinutes = when (rgSnoozeMin.checkedRadioButtonId) {
                R.id.snooze5 -> 5
                R.id.snooze10 -> 10
                R.id.snooze15 -> 15
                R.id.snooze20 -> 20
                R.id.snooze30 -> 30
                R.id.snoozeCustom -> customSnoozeMinutes.coerceIn(1, 60)
                else -> 5
            }
            val snoozeCount = when (rgSnoozeCount.checkedRadioButtonId) {
                R.id.snoozeCount5 -> 5
                R.id.snoozeCountForever -> -1
                else -> 3
            }

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


    override fun onResume() {
        super.onResume()
        // 화면이 보이는 동안 1초마다 갱신
        nowHandler.removeCallbacks(nowTicker)
        nowHandler.post(nowTicker)
    }

    override fun onPause() {
        super.onPause()
        nowHandler.removeCallbacks(nowTicker)
    }

    private fun updateNowDateTimeUi() {
        val now = ZonedDateTime.now()
        val formatted = now.format(nowFormatter)
        tvNowDateTime.text = getString(R.string.alarm_now_datetime, formatted)
    }

    companion object {
        private const val EXTRA_ID = "extra_alarm_id"

        fun newIntent(context: Context, alarmId: Long?): Intent =
            Intent(context, AlarmEditActivity::class.java).apply {
                if (alarmId != null) putExtra(EXTRA_ID, alarmId)
            }
    }
}
package com.krdonon.timer.alarmclock

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.krdonon.timer.AppColorTheme
import com.krdonon.timer.AppLanguagePrefs
import com.krdonon.timer.AppThemePrefs
import com.krdonon.timer.BaseActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.krdonon.timer.R
import android.widget.ScrollView
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import android.widget.RadioButton
import android.widget.NumberPicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AlarmEditActivity : BaseActivity() {

    private lateinit var store: AlarmStore

    private var alarmId: Long? = null
    private var original: AlarmItem? = null

    private var customSnoozeMinutes: Int = 5
    private var lastSnoozeRadioId: Int = R.id.snooze5

    private lateinit var tvNowDateTime: TextView
    private val nowFormatter: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern(
            getString(R.string.alarm_now_datetime_pattern),
            AppLanguagePrefs.getEffectiveLocale(this)
        )
    }

    private val nowHandler: Handler = Handler(Looper.getMainLooper())
    private val nowTicker: Runnable = object : Runnable {
        override fun run() {
            updateNowDateTimeUi()
            nowHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemePrefs.applyTheme(this)
        val barStyle = AppThemePrefs.systemBarStyle(this)
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle,
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_edit)

        // 상단 상태바 및 하단 네비게이션 바에 가리지 않도록 insets 적용
        val scrollRoot = findViewById<ScrollView>(R.id.scrollRoot)
        val baseTop = scrollRoot.paddingTop
        val baseBottom = scrollRoot.paddingBottom
        val baseLeft = scrollRoot.paddingLeft
        val baseRight = scrollRoot.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            insets
        }

        store = AlarmStore(this)
        alarmId = intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }

        val tp = findViewById<TimePicker>(R.id.timePicker)
        tp.setIs24HourView(false)

        tvNowDateTime = findViewById(R.id.tvNowDateTime)
        updateNowDateTimeUi()

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

        refreshSelectedSoundUi()

        val pickSoundLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    AlarmSoundPrefs.persistReadPermission(this, uri)
                    val name = AlarmSoundPrefs.resolveDisplayName(this, uri)
                    AlarmSoundPrefs.setCustom(this, uri, name)
                    refreshSelectedSoundUi()
                    Toast.makeText(this, getString(R.string.toast_alarm_sound_set), Toast.LENGTH_SHORT).show()
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
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "audio/mpeg",
                        "audio/mp3",
                        "audio/wav",
                        "audio/x-wav",
                        "audio/wave",
                        "audio/vnd.wave",
                        "audio/mp4",
                        "audio/m4a",
                        "audio/x-m4a",
                        "audio/aac",
                        "audio/*"
                    )
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
            pickSoundLauncher.launch(intent)
        }

        btnPickSound.setOnClickListener { launchSoundPicker() }
        tvChangeSound.setOnClickListener { launchSoundPicker() }

        val resetListener = android.view.View.OnLongClickListener {
            AlarmSoundPrefs.clearCustom(this)
            refreshSelectedSoundUi()
            Toast.makeText(this, getString(R.string.toast_alarm_sound_restored), Toast.LENGTH_SHORT).show()
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
                // 다이얼로그 오픈 직전의 선택(= 취소 시 복귀 대상)을 스냅샷으로 보관
                val previousId = lastSnoozeRadioId

                fun revertSelectionToPrevious() {
                    // programmatic check()로 listener가 다시 호출되면서 다이얼로그가 재표시되는 현상 방지
                    suppressSnoozeUiCallback = true
                    rgSnoozeMin.check(previousId)
                    suppressSnoozeUiCallback = false
                }

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
                        revertSelectionToPrevious()
                    }
                    .setOnCancelListener {
                        revertSelectionToPrevious()
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
        val dayButtons = dayIds.map { findViewById<MaterialButton>(it) }

        val selectedTheme = AppThemePrefs.getSelectedTheme(this)
        val isAmoled = (selectedTheme == AppColorTheme.AMOLED_BLACK)
        val isDark = AppThemePrefs.isDarkActive(this)
        val density = resources.displayMetrics.density

        val accentBg = if (isAmoled) Color.WHITE else AppThemePrefs.resolveColor(this, R.attr.appAccentPrimary)
        val accentFg = if (isAmoled) Color.BLACK else AppThemePrefs.resolveColor(this, R.attr.appOnAccentPrimary)
        val accentStroke = if (isAmoled) Color.WHITE else accentBg
        val cardBg = AppThemePrefs.resolveColor(this, R.attr.appSurfaceCard)
        val cardStroke = if (isAmoled) 0xFF666666.toInt() else AppThemePrefs.resolveColor(this, R.attr.appStrokeColor)
        val weekdayText = AppThemePrefs.resolveColor(this, R.attr.appTextSecondary)
        val sundayText = AppThemePrefs.resolveColor(this, R.attr.appDanger)
        val saturdayText = if (isDark) 0xFF7CB3FF.toInt() else 0xFF1D63B8.toInt()

        fun updateDayButtonUi(btn: MaterialButton, index: Int, checked: Boolean) {
            if (checked) {
                btn.backgroundTintList = ColorStateList.valueOf(accentBg)
                btn.strokeColor = ColorStateList.valueOf(accentStroke)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.setTextColor(accentFg)
                btn.typeface = Typeface.create(btn.typeface, Typeface.BOLD)
                btn.elevation = 4f * density
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(cardBg)
                btn.strokeColor = ColorStateList.valueOf(cardStroke)
                btn.strokeWidth = (1f * density).toInt()
                btn.setTextColor(
                    when (index) {
                        0 -> sundayText
                        6 -> saturdayText
                        else -> weekdayText
                    }
                )
                btn.typeface = Typeface.create(btn.typeface, Typeface.NORMAL)
                btn.elevation = 1f * density
            }
        }

        fun updateEveryWeekUi() {
            val allChecked = dayButtons.all { it.isChecked }
            btnEveryWeek.isSelected = allChecked
            btnEveryWeek.alpha = 1.0f
            if (allChecked) {
                btnEveryWeek.backgroundTintList = ColorStateList.valueOf(accentBg)
                btnEveryWeek.strokeColor = ColorStateList.valueOf(accentStroke)
                btnEveryWeek.strokeWidth = (1.5f * density).toInt()
                btnEveryWeek.setTextColor(accentFg)
            } else {
                btnEveryWeek.backgroundTintList = ColorStateList.valueOf(cardBg)
                btnEveryWeek.strokeColor = ColorStateList.valueOf(cardStroke)
                btnEveryWeek.strokeWidth = (1f * density).toInt()
                btnEveryWeek.setTextColor(AppThemePrefs.resolveColor(this, R.attr.appTextPrimary))
            }
        }

        dayButtons.forEachIndexed { idx, btn ->
            btn.addOnCheckedChangeListener { button, isChecked ->
                updateDayButtonUi(button, idx, isChecked)
                updateEveryWeekUi()
            }
            updateDayButtonUi(btn, idx, btn.isChecked)
        }

        // '매주' 버튼: 전체 선택/전체 해제 토글
        btnEveryWeek.setOnClickListener {
            val allChecked = dayButtons.all { it.isChecked }
            val next = !allChecked
            dayButtons.forEachIndexed { idx, btn ->
                btn.isChecked = next
                updateDayButtonUi(btn, idx, next)
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

            dayButtons.forEachIndexed { idx, btn ->
                val checked = item.days[idx]
                btn.isChecked = checked
                updateDayButtonUi(btn, idx, checked)
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
            val days = BooleanArray(7) { idx -> dayButtons[idx].isChecked }

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
package com.krdondon.thelordsprayer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var setSelectButton: Button
    private lateinit var keepScreenOnButton: Button
    private var isKeepScreenOn: Boolean = false

    // Age Signals 결과는 현재 Activity 메모리에서만 유지하며 영구 저장하지 않습니다.
    private var ageComplianceCategory: AgeComplianceCategory = AgeComplianceCategory.UNKNOWN
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MusicService.KEY_SELECTED_VOICE_SET) {
            updateSetSelectButtonLabel()
        }
    }

    // 안드로이드 13 (API 33) 이상을 위한 알림 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // 시스템 알림 권한 창과 Play 연령 공유 창이 겹치지 않도록
        // 알림 권한 요청이 끝난 뒤 Age Signals를 요청합니다.
        requestAgeSignalsCompliance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Activity가 새로 시작된 경우 한 번만 런타임 연령 신호를 확인합니다.
        // 화면 회전 같은 재생성에서 Play 공유 프롬프트가 불필요하게 반복되는 것을 피합니다.
        if (savedInstanceState == null) {
            askNotificationPermissionThenAgeSignals()
        }

        val playButton: Button = findViewById(R.id.playButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val voiceChangeButton: Button = findViewById(R.id.voiceChangeButton)
        setSelectButton = findViewById(R.id.setSelectButton)
        keepScreenOnButton = findViewById(R.id.keepScreenOnButton)

        // 앱 시작 시 현재 선택된 세트 및 화면 유지 상태 반영
        updateSetSelectButtonLabel()
        isKeepScreenOn = savedInstanceState?.getBoolean(KEY_KEEP_SCREEN_ON, false) ?: false
        applyKeepScreenOn(isKeepScreenOn, showToast = false)

        keepScreenOnButton.setOnClickListener {
            isKeepScreenOn = !isKeepScreenOn
            applyKeepScreenOn(isKeepScreenOn, showToast = true)
        }

        playButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY
            }
            startForegroundService(intent)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_STOP
            }
            startService(intent)
        }

        // 1) "음성 변경" 버튼: set0 -> set1 -> set2 ... 자동 순환
        voiceChangeButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_NEXT_VOICE
            }
            startService(intent)
            Toast.makeText(this, "음성을 변경했습니다.", Toast.LENGTH_SHORT).show()
        }

        // 2) set 버튼: 목록에서 특정 세트를 직접 선택
        setSelectButton.setOnClickListener {
            showVoiceSetPickerDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(MusicService.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateSetSelectButtonLabel()
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences(MusicService.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun updateSetSelectButtonLabel() {
        setSelectButton.text = loadSelectedVoiceSetLabel()
    }

    private fun loadSelectedVoiceSetLabel(): String {
        val prefs = getSharedPreferences(MusicService.PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(MusicService.KEY_SELECTED_VOICE_SET, "") ?: ""
        return saved.ifBlank { "기본" }
    }

    private fun listAvailableVoiceSets(): List<String> {
        val regex = Regex("^set\\d+$")
        val children = assets.list("sounds")?.asSequence().orEmpty()
        return children
            .filter { regex.matches(it) }
            .sortedBy { it.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
    }

    private fun showVoiceSetPickerDialog() {
        val sets = listAvailableVoiceSets()

        // 기본 음성(res/raw)도 선택 가능하게 제공
        val items = mutableListOf("기본").apply { addAll(sets) }

        val current = loadSelectedVoiceSetLabel()
        val checkedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("음성 세트 선택")
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                val chosen = items[which]

                val intent = Intent(this, MusicService::class.java).apply {
                    action = MusicService.ACTION_SELECT_VOICE_SET
                    putExtra(
                        MusicService.EXTRA_VOICE_SET_NAME,
                        if (chosen == "기본") "" else chosen,
                    )
                }
                startService(intent)

                // 선택 즉시 UI 반영
                setSelectButton.text = chosen

                Toast.makeText(this, "음성을 $chosen 로 변경했습니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // 알림 권한 요청이 필요하면 먼저 처리한 뒤 Age Signals를 요청합니다.
    private fun askNotificationPermissionThenAgeSignals() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestAgeSignalsCompliance()
    }

    private fun requestAgeSignalsCompliance() {
        ageSignalsCompliance.requestAndCheck(this) { category ->
            ageComplianceCategory = category
            when (category) {
                AgeComplianceCategory.MINOR -> Unit
                AgeComplianceCategory.ADULT -> Unit
                AgeComplianceCategory.UNKNOWN -> Unit
            }
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean, showToast: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnButton.text = getString(R.string.btn_release_screen_on)
            if (showToast) {
                Toast.makeText(this, "화면 켜짐 유지가 설정되었습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnButton.text = getString(R.string.btn_keep_screen_on)
            if (showToast) {
                Toast.makeText(this, "화면 켜짐 유지가 해제되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_KEEP_SCREEN_ON, isKeepScreenOn)
    }

    companion object {
        private const val KEY_KEEP_SCREEN_ON = "key_keep_screen_on"
    }
}

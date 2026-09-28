package com.krdonon.metronome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {

    private lateinit var flashSwitch: Switch
    private var isUpdatingSwitchProgrammatically = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        if (granted) {
            prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, true).apply()
            setSwitchChecked(true)
        } else {
            prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false).apply()
            setSwitchChecked(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        // 상단 박스 전체 클릭 → 뒤로
        val backContainer = findViewById<View>(R.id.backContainer)
        backContainer.setOnClickListener {
            finish()
        }

        // 화살표 아이콘 클릭 → 뒤로
        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener {
            finish()
        }

        // 강박(Strong Beat) 플래시 스위치
        flashSwitch = findViewById(R.id.switchFlashStrongBeat)

        // 저장된 값 불러오기 (권한이 실제로 있는지도 확인)
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val savedEnabled = prefs.getBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false)
        val actuallyEnabled = savedEnabled && hasPermission

        if (savedEnabled != actuallyEnabled) {
            prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, actuallyEnabled).apply()
        }
        setSwitchChecked(actuallyEnabled)

        flashSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener

            if (isChecked) {
                if (hasCameraPermission()) {
                    prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, true).apply()
                } else {
                    // 권한 요청 (결과 콜백에서 스위치 상태 및 설정 갱신)
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } else {
                prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false).apply()
            }
        }

        // ================================
        // 사운드 효과 출처 텍스트 구성
        // ================================
        val soundSourcesTextView = findViewById<TextView>(R.id.textSoundSources)
        val thanks = getString(R.string.sound_effect_thanks)
        val sources = resources.getStringArray(R.array.sound_effect_sources)

        val soundText = buildString {
            append(thanks)
            append("\n\n")
            sources.forEachIndexed { index, item ->
                append("${index + 1}. $item")
                if (index != sources.lastIndex) {
                    append("\n")
                }
            }
        }

        soundSourcesTextView.text = soundText
    }

    private fun setSwitchChecked(checked: Boolean) {
        isUpdatingSwitchProgrammatically = true
        flashSwitch.isChecked = checked
        isUpdatingSwitchProgrammatically = false
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}

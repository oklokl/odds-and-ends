package com.krdonon.metronome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Switch
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View

class SettingsActivity : ComponentActivity() {

    private lateinit var flashSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        // 상단 박스 전체 클릭 → 뒤로
        val backContainer = findViewById<View>(R.id.backContainer)
        backContainer.setOnClickListener {
            finish()
        }

        // 화살표 아이콘만 눌러도 뒤로 (자식도 동일 동작)
        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener {
            finish()
        }

        val flashSwitch = findViewById<Switch>(R.id.switchFlashStrongBeat)

        // 저장된 값 불러오기
        val enabled = prefs.getBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false)
        flashSwitch.isChecked = enabled

        flashSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 권한 확인 후 저장
                if (checkAndRequestFlashPermission()) {
                    prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, true).apply()
                } else {
                    // 권한이 아직 없으면 스위치 되돌림
                    flashSwitch.isChecked = false
                }
            } else {
                prefs.edit().putBoolean(Prefs.KEY_FLASH_STRONG_BEAT, false).apply()
            }
        }
    }

    /**
     * CAMERA 권한만 확인/요청합니다.
     * - 이미 허용되어 있으면 true
     * - 허용 안 되어 있으면 요청만 하고 false
     */
    private fun checkAndRequestFlashPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            return true
        }

        // 아직 권한이 없으면 요청
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            1001
        )
        // 지금은 결과를 바로 알 수 없으니 false 반환
        return false
    }
}

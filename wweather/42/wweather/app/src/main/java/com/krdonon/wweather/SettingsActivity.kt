package com.krdonon.wweather

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchAlert: SwitchMaterial
    private lateinit var switchCondition: SwitchMaterial
    private lateinit var rangeSlider: RangeSlider
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchAlert = findViewById(R.id.switchAlert)
        switchCondition = findViewById(R.id.switchCondition)
        rangeSlider = findViewById(R.id.rangeSlider)
        btnSave = findViewById(R.id.btnSave)

        // 실기기에서도 안전하게: 슬라이더 경계 먼저 고정
        initRangeSliderBounds()

        // 저장된 설정 불러오기 (값을 경계에 맞춰 보정)
        loadSettings()

        btnSave.setOnClickListener { saveSettings() }
    }

    private fun initRangeSliderBounds() {
        // 레이아웃에서도 지정했지만, 특정 기기/테마 조합에서 불일치 방지용으로 런타임에도 명시
        rangeSlider.valueFrom = 850f
        rangeSlider.valueTo = 1060f
        rangeSlider.stepSize = 1f

        // 값 쌍이 비어있으면 기본값 지정
        if (rangeSlider.values.isNullOrEmpty()) {
            rangeSlider.values = listOf(900f, 1040f)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        switchAlert.isChecked = prefs.getBoolean("alert", false)
        switchCondition.isChecked = prefs.getBoolean("condition", false)

        val min = prefs.getFloat("min", 900f)
        val max = prefs.getFloat("max", 1040f)

        // 현재 슬라이더 경계로 값 보정(범위 밖이면 크래시 방지)
        val from = rangeSlider.valueFrom
        val to = rangeSlider.valueTo
        val v1 = min.coerceIn(from, to)
        val v2 = max.coerceIn(from, to)

        val low = minOf(v1, v2)
        val high = maxOf(v1, v2)

        rangeSlider.values = listOf(low, high)
    }

    private fun saveSettings() {
        val values = rangeSlider.values
        val low = values.getOrNull(0) ?: 900f
        val high = values.getOrNull(1) ?: 1040f

        getSharedPreferences("settings", MODE_PRIVATE).edit().apply {
            putBoolean("alert", switchAlert.isChecked)
            putBoolean("condition", switchCondition.isChecked)
            putFloat("min", low)
            putFloat("max", high)
            apply()
        }

        // 알람 스케줄 재정렬 (있으면 호출, 없으면 무시)
        try {
            AlarmScheduler.reschedule(this)
        } catch (_: Throwable) { }

        Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
        finish()
    }
}

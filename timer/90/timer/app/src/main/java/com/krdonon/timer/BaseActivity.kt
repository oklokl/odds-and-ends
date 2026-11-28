package com.krdonon.timer

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * 시스템 글꼴 크기(fontScale) 설정 변경으로 타이머·숫자패드·버튼 텍스트가
 * 줄바꿈되거나 잘리는 현상을 방지하기 위해 모든 Activity의 기본 fontScale을 1.0f로 고정한다.
 * (densityDpi는 유지하여 해상도·디스플레이 크기·멀티윈도우 환경에 자연스럽게 대응한다.)
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val currentConfig = newBase.resources.configuration
        if (currentConfig.fontScale != 1.0f) {
            val fixedConfig = Configuration(currentConfig).apply {
                fontScale = 1.0f
            }
            super.attachBaseContext(newBase.createConfigurationContext(fixedConfig))
        } else {
            super.attachBaseContext(newBase)
        }
    }
}

package com.krdondon.thelordsprayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var setSelectButton: Button

    // Age Signals 결과는 현재 Activity 메모리에서만 유지하며 영구 저장하지 않습니다.
    private var ageComplianceCategory: AgeComplianceCategory = AgeComplianceCategory.UNKNOWN
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }

    // 안드로이드 13 (API 33) 이상을 위한 알림 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 권한이 허용됨
        } else {
            // 권한이 거부됨
        }

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
        val pauseButton: Button = findViewById(R.id.pauseButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val voiceChangeButton: Button = findViewById(R.id.voiceChangeButton)
        setSelectButton = findViewById(R.id.setSelectButton)

        // 앱 시작 시 현재 선택된 세트를 화면에 반영
        updateSetSelectButtonLabel()

        playButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY
            }
            // 포그라운드 서비스 시작
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        pauseButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PAUSE
            }
            startService(intent)
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

            // 서비스에서 SharedPreferences 갱신 후 화면도 따라가도록 약간 지연 후 라벨 갱신
            setSelectButton.postDelayed({ updateSetSelectButtonLabel() }, 120)

            Toast.makeText(this, "음성을 변경했습니다.", Toast.LENGTH_SHORT).show()
        }

        // 2) set 버튼: 목록에서 특정 세트를 직접 선택
        setSelectButton.setOnClickListener {
            showVoiceSetPickerDialog()
        }
    }

    private fun updateSetSelectButtonLabel() {
        setSelectButton.text = loadSelectedVoiceSetLabel()
    }

    private fun loadSelectedVoiceSetLabel(): String {
        val prefs = getSharedPreferences(MusicService.PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(MusicService.KEY_SELECTED_VOICE_SET, "") ?: ""
        return if (saved.isBlank()) "기본" else saved
    }

    private fun listAvailableVoiceSets(): List<String> {
        val regex = Regex("^set\\d+$")
        val children = assets.list("sounds")?.toList().orEmpty()
        return children
            .filter { regex.matches(it) }
            .sortedBy { it.removePrefix("set").toIntOrNull() ?: Int.MAX_VALUE }
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
                        if (chosen == "기본") "" else chosen
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAgeSignalsCompliance()
        }
    }

    private fun requestAgeSignalsCompliance() {
        ageSignalsCompliance.requestAndCheck(this) { category ->
            // 메모리에서만 보유합니다. 사용자 연령 신호를 파일/DB/SharedPreferences에 저장하지 않습니다.
            ageComplianceCategory = category

            // 현재 앱에는 연령별로 제한해야 할 광고/결제 기능이 없으므로
            // UI나 기존 기도 재생 기능은 변경하지 않습니다.
            when (category) {
                AgeComplianceCategory.MINOR -> Unit
                AgeComplianceCategory.ADULT -> Unit
                AgeComplianceCategory.UNKNOWN -> Unit
            }
        }
    }

    override fun onDestroy() {
        // 앱이 완전히 종료될 때 서비스를 중지하고 싶다면 아래 주석을 해제하세요.
        // 하지만 보통은 사용자가 직접 알림에서 정지하도록 둡니다.
        // val intent = Intent(this, MusicService::class.java)
        // stopService(intent)
        super.onDestroy()
    }
}

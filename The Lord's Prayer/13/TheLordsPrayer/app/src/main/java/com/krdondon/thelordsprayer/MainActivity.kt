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

    // 안드로이드 13 (API 33) 이상을 위한 알림 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 권한이 허용됨
        } else {
            // 권한이 거부됨
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        askNotificationPermission()

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

    // 알림 권한을 요청하는 함수
    private fun askNotificationPermission() {
        // 이 코드는 안드로이드 13 (TIRAMISU) 이상에서만 실행됩니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                // 권한 요청
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

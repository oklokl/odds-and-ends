package com.krdondon.thelordsprayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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

        voiceChangeButton.setOnClickListener {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_NEXT_VOICE
            }
            // 음성 세트만 변경하고, 재생 중이면 서비스에서 자동으로 새 음성으로 재시작합니다.
            startService(intent)
            Toast.makeText(this, "음성을 변경했습니다.", Toast.LENGTH_SHORT).show()
        }

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
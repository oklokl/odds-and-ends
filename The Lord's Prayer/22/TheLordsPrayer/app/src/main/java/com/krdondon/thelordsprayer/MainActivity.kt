package com.krdondon.thelordsprayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private lateinit var setSelectButton: Button
    private lateinit var keepScreenOnButton: Button
    private var isKeepScreenOn: Boolean = false
    private var activeToast: Toast? = null
    private var pendingVoiceChangeToast: Boolean = false

    // Age Signals 결과는 현재 Activity 메모리에서만 유지하며 영구 저장하지 않습니다.
    private var ageComplianceCategory: AgeComplianceCategory = AgeComplianceCategory.UNKNOWN
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VoiceSetStore.KEY_SELECTED_VOICE_SET) {
            val label = loadSelectedVoiceSetLabel()
            setSelectButton.text = label
            if (pendingVoiceChangeToast) {
                pendingVoiceChangeToast = false
                showToast("음성을 $label 로 변경했습니다.")
            }
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 상단 상태바, 하단 네비게이션바 및 디스플레이 컷아웃을 고려한 인셋 처리
        val prayerCard = findViewById<View>(R.id.prayerCard)
        val mainButtonLayout = findViewById<View>(R.id.mainButtonLayout)
        val cardBaseHorizontalMargin = 20.dpToPx(this)
        val cardBaseTopMargin = 16.dpToPx(this)
        val mainBaseHorizontalMargin = 24.dpToPx(this)
        val mainBaseBottomMargin = 24.dpToPx(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val safeBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )

            updateMarginsIfChanged(
                view = prayerCard,
                top = safeBars.top + cardBaseTopMargin,
                left = safeBars.left + cardBaseHorizontalMargin,
                right = safeBars.right + cardBaseHorizontalMargin,
            )
            updateMarginsIfChanged(
                view = mainButtonLayout,
                bottom = safeBars.bottom + mainBaseBottomMargin,
                left = safeBars.left + mainBaseHorizontalMargin,
                right = safeBars.right + mainBaseHorizontalMargin,
            )
            insets
        }

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
            ContextCompat.startForegroundService(this, intent)
        }

        stopButton.setOnClickListener {
            if (MusicService.isServiceRunning) {
                val intent = Intent(this, MusicService::class.java).apply {
                    action = MusicService.ACTION_STOP
                }
                startService(intent)
            }
        }

        // 1) "음성 변경" 버튼: set0 -> set1 -> set2 ... 자동 순환
        voiceChangeButton.setOnClickListener {
            if (MusicService.isServiceRunning) {
                pendingVoiceChangeToast = true
                val intent = Intent(this, MusicService::class.java).apply {
                    action = MusicService.ACTION_NEXT_VOICE
                }
                startService(intent)
            } else {
                val sets = VoiceSetStore.listAvailableVoiceSets(assets)
                val current = VoiceSetStore.loadSelectedVoiceSet(this)
                val next = VoiceSetStore.getNextVoiceSet(sets, current)
                VoiceSetStore.saveSelectedVoiceSet(this, next)
                val label = VoiceSetStore.formatVoiceLabel(next)
                setSelectButton.text = label
                showToast("음성을 $label 로 변경했습니다.")
            }
        }

        // 2) set 버튼: 목록에서 특정 세트를 직접 선택
        setSelectButton.setOnClickListener {
            showVoiceSetPickerDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(VoiceSetStore.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateSetSelectButtonLabel()
    }

    override fun onStop() {
        super.onStop()
        pendingVoiceChangeToast = false
        getSharedPreferences(VoiceSetStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun onDestroy() {
        activeToast?.cancel()
        activeToast = null
        super.onDestroy()
    }

    private fun updateSetSelectButtonLabel() {
        setSelectButton.text = loadSelectedVoiceSetLabel()
    }

    private fun loadSelectedVoiceSetLabel(): String {
        return VoiceSetStore.formatVoiceLabel(VoiceSetStore.loadSelectedVoiceSet(this))
    }

    private fun showVoiceSetPickerDialog() {
        val sets = VoiceSetStore.listAvailableVoiceSets(assets)

        // 기본 음성(res/raw)도 선택 가능하게 제공
        val items = buildList {
            add(VoiceSetStore.DEFAULT_VOICE_LABEL)
            addAll(sets)
        }

        val current = loadSelectedVoiceSetLabel()
        val checkedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle("음성 세트 선택")
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                val chosen = items[which]
                val chosenSetName = if (chosen == VoiceSetStore.DEFAULT_VOICE_LABEL) null else chosen

                if (MusicService.isServiceRunning) {
                    val intent = Intent(this, MusicService::class.java).apply {
                        action = MusicService.ACTION_SELECT_VOICE_SET
                        putExtra(MusicService.EXTRA_VOICE_SET_NAME, chosenSetName ?: "")
                    }
                    startService(intent)
                } else {
                    VoiceSetStore.saveSelectedVoiceSet(this, chosenSetName)
                }

                // 선택 즉시 UI 반영
                setSelectButton.text = chosen
                showToast("음성을 $chosen 로 변경했습니다.")
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
        val weakActivity = WeakReference(this)
        ageSignalsCompliance.requestAndCheck(this) { category ->
            val activity = weakActivity.get() ?: return@requestAndCheck
            if (activity.isFinishing || activity.isDestroyed) return@requestAndCheck

            activity.ageComplianceCategory = category
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
                showToast("화면 켜짐 유지가 설정되었습니다.")
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnButton.text = getString(R.string.btn_keep_screen_on)
            if (showToast) {
                showToast("화면 켜짐 유지가 해제되었습니다.")
            }
        }
    }

    private fun showToast(message: String) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun updateMarginsIfChanged(
        view: View,
        top: Int? = null,
        bottom: Int? = null,
        left: Int? = null,
        right: Int? = null,
    ) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (top != null && lp.topMargin != top) {
            lp.topMargin = top
            changed = true
        }
        if (bottom != null && lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            changed = true
        }
        if (left != null && lp.leftMargin != left) {
            lp.leftMargin = left
            changed = true
        }
        if (right != null && lp.rightMargin != right) {
            lp.rightMargin = right
            changed = true
        }
        if (changed) {
            view.layoutParams = lp
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_KEEP_SCREEN_ON, isKeepScreenOn)
    }

    companion object {
        private const val KEY_KEEP_SCREEN_ON = "key_keep_screen_on"

        private fun Int.dpToPx(context: Context): Int =
            (this * context.resources.displayMetrics.density).toInt()
    }
}

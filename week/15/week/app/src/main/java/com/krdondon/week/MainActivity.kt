package com.krdondon.week

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.krdondon.week.notification.StatusNotificationManager
import com.krdondon.week.notification.StatusNotificationPrefs
import com.krdondon.week.ui.theme.WeekTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WeekBattery"
    }

    // Compose 상태(화면 표시용)
    private var isBatteryExempt by mutableStateOf(false)
    private var isAmPmEnabled by mutableStateOf(false)
    private var isDayOfWeekEnabled by mutableStateOf(false)

    // 요청 화면을 띄웠다가 돌아온 경우를 판단
    private var pendingBatteryRequest: Boolean = false
    private var pendingNotificationAction: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingNotificationAction?.invoke()
            pendingNotificationAction = null
        } else {
            pendingNotificationAction = null
            Toast.makeText(this, "알림 권한을 허용해야 상단바에 표시할 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 15(API 35+)에서 지원 중단된 SHORT_EDGES 대신 ALWAYS 모드 적용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // (수동 동기화 유지) 앱 진입 시 위젯 강제 갱신
        TimeWidgetProvider.updateAllWidgets(this)

        // Google Play Age Signals API 0.0.4 런타임 확인.
        AgeSignalsCompliance.refresh(this)

        isBatteryExempt = checkBatteryExempt()
        isAmPmEnabled = StatusNotificationPrefs.isAmPmEnabled(this)
        isDayOfWeekEnabled = StatusNotificationPrefs.isDayOfWeekEnabled(this)

        // 활성화되어 있는 경우 최신 알림 및 스케줄러 동기화
        if (isAmPmEnabled || isDayOfWeekEnabled) {
            StatusNotificationManager.updateNotifications(this)
        }

        setContent {
            WeekTheme {
                var showBatteryDialog by remember { mutableStateOf(false) }

                LaunchedEffect(isBatteryExempt) {
                    if (!isBatteryExempt) {
                        showBatteryDialog = true
                    }
                }

                if (showBatteryDialog && !isBatteryExempt) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog = false },
                        title = { Text("배터리 최적화 제외") },
                        text = {
                            Text(
                                "위젯 갱신이 멈추는 현상을 줄이기 위해\n" +
                                        "배터리 최적화 제외를 권장합니다.\n\n" +
                                        "지금 설정하시겠습니까?"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showBatteryDialog = false
                                    requestBatteryOptimizationExemption()
                                }
                            ) { Text("설정하기") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryDialog = false }) {
                                Text("나중에")
                            }
                        }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                        isBatteryExempt = isBatteryExempt,
                        onRequestBatteryOptimization = {
                            requestBatteryOptimizationExemption()
                        },
                        isAmPmEnabled = isAmPmEnabled,
                        onToggleAmPm = {
                            toggleAmPm()
                        },
                        isDayOfWeekEnabled = isDayOfWeekEnabled,
                        onToggleDayOfWeek = {
                            toggleDayOfWeek()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // (수동 동기화 유지) 위젯 반복 탭 등으로 앱 재진입 시에도 강제 갱신
        TimeWidgetProvider.updateAllWidgets(this)
    }

    override fun onResume() {
        super.onResume()

        val now = checkBatteryExempt()
        isBatteryExempt = now

        isAmPmEnabled = StatusNotificationPrefs.isAmPmEnabled(this)
        isDayOfWeekEnabled = StatusNotificationPrefs.isDayOfWeekEnabled(this)
        if (isAmPmEnabled || isDayOfWeekEnabled) {
            StatusNotificationManager.updateNotifications(this)
        }

        if (pendingBatteryRequest) {
            pendingBatteryRequest = false
            Log.d(TAG, "onResume() after request, exempt=$now")

            if (now) {
                Toast.makeText(this, "배터리 최적화 제외가 설정되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "아직 제외가 적용되지 않았습니다. 팝업에서 '허용'을 눌러주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        val now = checkBatteryExempt()
        isBatteryExempt = now

        if (now) {
            Toast.makeText(this, "이미 배터리 최적화 제외 상태입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        pendingBatteryRequest = true

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }

        Log.d(TAG, "launch ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")

        val launched = safeStartActivity(intent)
        if (!launched) {
            pendingBatteryRequest = false
            Toast.makeText(this, "요청 화면을 열 수 없어 설정 화면으로 이동합니다.", Toast.LENGTH_LONG).show()
            openBatteryOptimizationSettings()
        }
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Log.d(TAG, "launch ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")

        val launched = safeStartActivity(intent)
        if (!launched) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            Log.d(TAG, "fallback ACTION_APPLICATION_DETAILS_SETTINGS")
            safeStartActivity(fallback)
        }
    }

    private fun checkAndRequestNotificationPermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pendingNotificationAction = onGranted
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            onGranted()
        }
    }

    private fun toggleAmPm() {
        if (isAmPmEnabled) {
            StatusNotificationPrefs.setAmPmEnabled(this, false)
            isAmPmEnabled = false
            StatusNotificationManager.updateNotifications(this)
        } else {
            checkAndRequestNotificationPermission {
                StatusNotificationPrefs.setAmPmEnabled(this, true)
                isAmPmEnabled = true
                StatusNotificationManager.updateNotifications(this)
            }
        }
    }

    private fun toggleDayOfWeek() {
        if (isDayOfWeekEnabled) {
            StatusNotificationPrefs.setDayOfWeekEnabled(this, false)
            isDayOfWeekEnabled = false
            StatusNotificationManager.updateNotifications(this)
        } else {
            checkAndRequestNotificationPermission {
                StatusNotificationPrefs.setDayOfWeekEnabled(this, true)
                isDayOfWeekEnabled = true
                StatusNotificationManager.updateNotifications(this)
            }
        }
    }

    private fun safeStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "ActivityNotFoundException: ${e.message}", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
            false
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isBatteryExempt: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    isAmPmEnabled: Boolean,
    onToggleAmPm: () -> Unit,
    isDayOfWeekEnabled: Boolean,
    onToggleDayOfWeek: () -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AM PM 요일 위젯",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "홈 화면에서 위젯을 추가하세요.\n\n1. 홈 화면 길게 누르기\n2. 위젯 선택\n3. 'AM PM 요일' 위젯 추가",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "배터리 최적화 제외 상태: " + if (isBatteryExempt) "설정됨" else "미설정",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRequestBatteryOptimization,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("배터리 최적화 제외 설정")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "버튼을 누르면 시스템 팝업이 뜹니다.\n(기기에 따라 '백그라운드 허용'처럼 보일 수 있습니다.)",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.status_bar_settings_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onToggleAmPm,
                modifier = Modifier.weight(1f),
                colors = if (isAmPmEnabled) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(stringResource(if (isAmPmEnabled) R.string.btn_am_pm_dismiss else R.string.btn_am_pm))
            }

            Button(
                onClick = onToggleDayOfWeek,
                modifier = Modifier.weight(1f),
                colors = if (isDayOfWeekEnabled) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(stringResource(if (isDayOfWeekEnabled) R.string.btn_day_of_week_dismiss else R.string.btn_day_of_week))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.status_bar_guide),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

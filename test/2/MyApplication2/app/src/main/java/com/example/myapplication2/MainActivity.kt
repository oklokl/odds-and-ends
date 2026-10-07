package com.example.myapplication2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.myapplication2.ui.theme.MyApplication2Theme
import java.util.Locale

class MainActivity : ComponentActivity() {

    var remainingSecondsState by mutableStateOf(10)
    var isRunningState by mutableStateOf(false)
    var isAlarmRingingState by mutableStateOf(false)

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingTimerService.BROADCAST_TICK) {
                remainingSecondsState = intent.getIntExtra(FloatingTimerService.EXTRA_REMAINING, 10)
                isRunningState = intent.getBooleanExtra(FloatingTimerService.EXTRA_RUNNING, false)
                isAlarmRingingState = intent.getBooleanExtra(FloatingTimerService.EXTRA_ALARM, false)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val filter = IntentFilter(FloatingTimerService.BROADCAST_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }

        setContent {
            MyApplication2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestPermission = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:$packageName".toUri()
                            )
                            startActivity(intent)
                        },
                        remainingSeconds = remainingSecondsState,
                        isRunning = isRunningState,
                        isAlarmRinging = isAlarmRingingState,
                        onStart = { duration ->
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_START
                                putExtra("EXTRA_DURATION", duration)
                            }
                            ContextCompat.startForegroundService(this, intent)
                        },
                        onPause = {
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_PAUSE
                            }
                            startService(intent)
                        },
                        onReset = { duration ->
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_RESET
                                putExtra("EXTRA_DURATION", duration)
                            }
                            startService(intent)
                        },
                        onStopAlarm = {
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_STOP_ALARM
                            }
                            startService(intent)
                        },
                        onShowOverlay = { duration ->
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_SHOW_OVERLAY
                                putExtra("EXTRA_DURATION", duration)
                            }
                            ContextCompat.startForegroundService(this, intent)
                        },
                        onStopService = {
                            val intent = Intent(this, FloatingTimerService::class.java).apply {
                                action = FloatingTimerService.ACTION_STOP_SERVICE
                            }
                            startService(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isRunningState && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingTimerService::class.java).apply {
                action = FloatingTimerService.ACTION_SHOW_OVERLAY
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, FloatingTimerService::class.java).apply {
            action = FloatingTimerService.ACTION_HIDE_OVERLAY
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(timerReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
    remainingSeconds: Int,
    isRunning: Boolean,
    isAlarmRinging: Boolean,
    onStart: (Int) -> Unit,
    onPause: () -> Unit,
    onReset: (Int) -> Unit,
    onStopAlarm: () -> Unit,
    onShowOverlay: (Int) -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    var minutesInput by remember { mutableStateOf("0") }
    var secondsInput by remember { mutableStateOf("10") }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Settings.canDrawOverlays(context)
            }
        }
        val lifecycleOwner = context as? LifecycleOwner
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val secs = remainingSeconds % 60
    val timeDisplay = if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "플로팅 카운트다운 타이머",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = timeDisplay,
            fontSize = 48.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        if (!hasPermission) {
            Text(
                text = "타이머를 다른 앱 위에 띄우려면 '다른 앱 위에 그리기' 권한이 필요합니다.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = onRequestPermission) {
                Text("권한 허용하러 가기")
            }
        } else {
            if (isAlarmRinging) {
                Button(
                    onClick = { onStopAlarm() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("알람 끄기 (확인)", fontSize = 18.sp, color = Color.White)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = {
                            minutesInput = it.filter { c -> c.isDigit() }
                            val m = minutesInput.toIntOrNull() ?: 0
                            val s = secondsInput.toIntOrNull() ?: 0
                            val total = (m * 60) + s
                            if (!isRunning) onReset(if (total > 0) total else 10)
                        },
                        label = { Text("분") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(":", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = secondsInput,
                        onValueChange = {
                            secondsInput = it.filter { c -> c.isDigit() }
                            val m = minutesInput.toIntOrNull() ?: 0
                            val s = secondsInput.toIntOrNull() ?: 0
                            val total = (m * 60) + s
                            if (!isRunning) onReset(if (total > 0) total else 10)
                        },
                        label = { Text("초") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        val currentMins = minutesInput.toIntOrNull() ?: 0
                        minutesInput = (currentMins + 1).toString()
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onReset(if (total > 0) total else 10)
                    }) { Text("+1분") }

                    Button(onClick = {
                        val currentMins = minutesInput.toIntOrNull() ?: 0
                        minutesInput = (currentMins + 3).toString()
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onReset(if (total > 0) total else 10)
                    }) { Text("+3분") }

                    Button(onClick = {
                        val currentMins = minutesInput.toIntOrNull() ?: 0
                        minutesInput = (currentMins + 5).toString()
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onReset(if (total > 0) total else 10)
                    }) { Text("+5분") }

                    Button(onClick = {
                        val currentSecs = secondsInput.toIntOrNull() ?: 0
                        val totalSecs = currentSecs + 10
                        val extraMins = totalSecs / 60
                        val remainingSecs = totalSecs % 60
                        minutesInput = ((minutesInput.toIntOrNull() ?: 0) + extraMins).toString()
                        secondsInput = remainingSecs.toString()
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onReset(if (total > 0) total else 10)
                    }) { Text("+10초") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        val duration = if (total > 0) total else 10
                        if (isRunning) {
                            onPause()
                        } else {
                            onStart(duration)
                        }
                    }) {
                        Text(if (isRunning) "일시중지" else "시작")
                    }

                    Button(onClick = {
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onReset(if (total > 0) total else 10)
                    }) {
                        Text("리셋")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val m = minutesInput.toIntOrNull() ?: 0
                        val s = secondsInput.toIntOrNull() ?: 0
                        val total = (m * 60) + s
                        onShowOverlay(if (total > 0) total else remainingSeconds)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("지금 플로팅 타이머로 띄우기")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onStopService() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("플로팅 타이머 끄기")
                }
            }
        }
    }
}

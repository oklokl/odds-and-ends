package com.krdondon.week

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.krdondon.week.ui.theme.WeekTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WeekBattery"
        private const val KEY_PENDING_BATTERY_REQUEST = "key_pending_battery_request"
    }

    // Compose 상태(화면 표시용)
    private var isBatteryExempt by mutableStateOf(false)
    private var widgetBackground by mutableStateOf(WidgetBackground.SOLID_MINT)

    // 요청 화면을 띄웠다가 돌아온 경우를 판단
    private var pendingBatteryRequest: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingBatteryRequest =
            savedInstanceState?.getBoolean(KEY_PENDING_BATTERY_REQUEST, false) ?: false

        if (savedInstanceState == null) {
            // (수동 동기화 유지) 앱 최초 진입 시 위젯 강제 갱신
            TimeWidgetProvider.updateAllWidgets(this)

            // Google Play Age Signals API 0.0.4 런타임 확인.
            // 접근 허용 상태를 먼저 확인하고, 공유된 경우에만 실제 연령 구간을 조회한다.
            // 현재 앱에는 광고/인앱 결제/연령 제한 기능이 없으므로 결과는 메모리에서만 유지한다.
            AgeSignalsCompliance.refresh(this)
        }

        isBatteryExempt = checkBatteryExempt()
        widgetBackground = WidgetBackground.getSelected(this)

        setContent {
            WeekTheme {
                var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
                var hasPromptedBatteryDialog by rememberSaveable { mutableStateOf(false) }

                // "배터리 제외가 안 되어 있으면" 진입 시 한 번 안내 팝업 띄움
                // - 이전 설치/백업 복원 때문에 '첫 설치인데도 안 뜨는' 문제를 피하기 위해
                // - 화면 회전 등 구성 변경 시에는 다시 뜨지 않도록 rememberSaveable 사용
                LaunchedEffect(isBatteryExempt) {
                    if (!isBatteryExempt && !hasPromptedBatteryDialog) {
                        hasPromptedBatteryDialog = true
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = innerPadding,
                        isBatteryExempt = isBatteryExempt,
                        widgetBackground = widgetBackground,
                        onCycleWidgetBackground = {
                            widgetBackground = WidgetBackground.cycleNext(this@MainActivity)
                            TimeWidgetProvider.updateAllWidgets(this@MainActivity)
                        },
                        onRequestBatteryOptimization = {
                            requestBatteryOptimizationExemption()
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_BATTERY_REQUEST, pendingBatteryRequest)
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
        widgetBackground = WidgetBackground.getSelected(this)

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
        val pm = getSystemService(PowerManager::class.java) ?: return false
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

        // 이 인텐트를 실행하면 기기/OS에 따라
        // "백그라운드에서 항상 실행 허용" 같은 문구의 시스템 팝업이 뜹니다.
        // (그 팝업이 곧 '배터리 최적화 제외' 요청임)
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
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
        // 배터리 최적화 제외 목록 화면 (기기별로 지원 여부가 다를 수 있음)
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Log.d(TAG, "launch ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")

        val launched = safeStartActivity(intent)
        if (!launched) {
            // 최후 fallback: 앱 상세 화면
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
            Log.d(TAG, "fallback ACTION_APPLICATION_DETAILS_SETTINGS")
            safeStartActivity(fallback)
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
    contentPadding: PaddingValues = PaddingValues(0.dp),
    isBatteryExempt: Boolean,
    widgetBackground: WidgetBackground = WidgetBackground.SOLID_MINT,
    onCycleWidgetBackground: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit
) {
    Column(
        modifier = modifier
            .consumeWindowInsets(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AM PM 요일 위젯",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "홈 화면에서 위젯을 추가하세요.\n\n1. 홈 화면 길게 누르기\n2. 위젯 선택\n3. 'AM PM 요일' 위젯 추가",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .widthIn(min = 176.dp, max = 240.dp)
                .heightIn(min = 92.dp)
                .clip(RoundedCornerShape(18.dp))
                .paint(
                    painter = painterResource(id = widgetBackground.drawableRes),
                    contentScale = ContentScale.FillBounds
                )
                .clearAndSetSemantics {
                    contentDescription = "위젯 배경 미리보기: ${widgetBackground.label}"
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "오전 12:00",
                    color = Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0x33000000))
                )
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "11.22 ",
                        color = Color.Black,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "일",
                        color = Color(0xFFB71C1C),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "위젯 배경: ${widgetBackground.label} (${widgetBackground.ordinal + 1}/${WidgetBackground.entries.size})",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onCycleWidgetBackground,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
        ) {
            Text("위젯 배경 변경")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "배터리 최적화 제외 상태: " + if (isBatteryExempt) "설정됨" else "미설정",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onRequestBatteryOptimization,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
        ) {
            Text("배터리 최적화 제외 설정")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "버튼을 누르면 시스템 팝업이 뜹니다.\n(기기에 따라 '백그라운드 허용'처럼 보일 수 있습니다.)",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showSystemUi = true, name = "Main Screen (Light)")
@Composable
private fun MainScreenLightPreview() {
    WeekTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            MainScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                isBatteryExempt = false,
                widgetBackground = WidgetBackground.MINT_ORIGINAL,
                onCycleWidgetBackground = {},
                onRequestBatteryOptimization = {}
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Main Screen (Dark)")
@Composable
private fun MainScreenDarkPreview() {
    WeekTheme(darkTheme = true, dynamicColor = false) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            MainScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                isBatteryExempt = true,
                widgetBackground = WidgetBackground.MINT_SOFT_WAVES,
                onCycleWidgetBackground = {},
                onRequestBatteryOptimization = {}
            )
        }
    }
}

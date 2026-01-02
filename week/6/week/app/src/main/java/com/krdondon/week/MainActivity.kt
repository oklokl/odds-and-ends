package com.krdondon.week

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krdondon.week.ui.theme.WeekTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 위젯에서 앱으로 진입했을 때(또는 앱을 열었을 때) 한 번 더 위젯을 강제로 갱신한다.
        // 런처가 TextClock 갱신을 놓치는 경우(간헐적)에도, 이 시점에 RemoteViews를 재적용하여
        // 위젯이 최신 시간/날짜/요일로 동기화되도록 한다.
        TimeWidgetProvider.updateAllWidgets(this)

        // 서비스 시작

        setContent {
            WeekTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestBatteryOptimization = {
                            requestBatteryOptimizationExemption()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 위젯을 반복 탭할 때도 항상 수동 갱신이 수행되도록 한다.
        TimeWidgetProvider.updateAllWidgets(this)
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onRequestBatteryOptimization: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
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

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestBatteryOptimization,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("배터리 최적화 제외 설정")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "위젯이 정확하게 작동하려면\n배터리 최적화를 제외해주세요.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
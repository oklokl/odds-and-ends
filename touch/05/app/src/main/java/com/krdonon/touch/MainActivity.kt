package com.krdonon.touch

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krdonon.touch.ui.theme.TouchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TouchTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = Color(0xFF4CAF50)
                ) {
                    TouchGameScreen()
                }
            }
        }
    }
}

data class TouchTarget(
    val id: Long,
    val x: Float,
    val y: Float
)

@Composable
fun TouchGameScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentTime by remember { mutableStateOf("오전 00:00:00:000") }
    var touchCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    var isGamePaused by remember { mutableStateOf(false) }
    var touchTargets by remember { mutableStateOf<List<TouchTarget>>(emptyList()) }
    var isBgmEnabled by remember { mutableStateOf(true) } // 배경음악 on/off 상태

    // 배경음악 MediaPlayer 초기화
    val bgmPlayer = remember {
        try {
            MediaPlayer.create(context, R.raw.sstouch).apply {
                setVolume(0.5f, 0.5f) // 50% 음량 고정 (시스템 볼륨과 별개)
                isLooping = true // 반복 재생
                // 초기에는 자동 시작 (스위치가 on 상태이므로)
                start()
            }
        } catch (_: Exception) {
            null // 파일이 없으면 null
        }
    }

    // 터치 효과음 SoundPool 초기화
    val soundPool = remember {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    val soundId = remember {
        try {
            soundPool.load(context, R.raw.sound, 1)
        } catch (_: Exception) {
            -1
        }
    }

    // 배경음악 on/off 제어
    LaunchedEffect(isBgmEnabled) {
        if (isBgmEnabled) {
            bgmPlayer?.start() // 배경음악 재생
        } else {
            bgmPlayer?.pause() // 배경음악 일시정지
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("a hh:mm:ss", Locale.KOREAN)
            val timeBase = dateFormat.format(calendar.time)
            val milliseconds = calendar.get(Calendar.MILLISECOND)
            // 밀리초를 4자리로 표시 (0000~9999)
            currentTime = "$timeBase:${String.format(Locale.US, "%04d", milliseconds * 10)}"
            delay(100)
        }
    }

    LaunchedEffect(isGamePaused) {
        while (!isGamePaused) {
            val randomDelay = Random.nextLong(2000, 4001)
            delay(randomDelay)

            if (!isGamePaused) {
                val newTarget = TouchTarget(
                    id = System.currentTimeMillis(),
                    x = Random.nextFloat(),
                    y = Random.nextFloat()
                )
                touchTargets = touchTargets + newTarget

                val targetId = newTarget.id
                coroutineScope.launch {
                    delay(3000)
                    if (touchTargets.any { it.id == targetId }) {
                        touchTargets = touchTargets.filter { it.id != targetId }
                        failCount++
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .padding(bottom = 38.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFFB3E5FC),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "현재 시간",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = currentTime,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            // 배경음악 on/off 스위치
            Switch(
                checked = isBgmEnabled,
                onCheckedChange = { isBgmEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF4CAF50),
                    checkedTrackColor = Color(0xFFA5D6A7),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InfoBox(
                title = "버튼 누른 횟수",
                value = touchCount.toString(),
                backgroundColor = Color(0xFFFFEB3B),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            InfoBox(
                title = "실패 횟수",
                value = failCount.toString(),
                backgroundColor = Color.White,
                modifier = Modifier.weight(1f)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = Color(0xFFB2FBA5),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            val gameAreaWidthPx = this.maxWidth.value - 96f
            val gameAreaHeightPx = this.maxHeight.value - 96f

            Text(
                text = "터치 하는 곳 둥근 원을 터치 하세요",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            touchTargets.forEach { target ->
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.touch),
                        contentDescription = "터치 타겟",
                        modifier = Modifier
                            .size(80.dp)
                            .offset(
                                x = (target.x * gameAreaWidthPx).dp,
                                y = (target.y * gameAreaHeightPx).dp
                            )
                            .clickable {
                                touchCount++
                                touchTargets = touchTargets.filter { it.id != target.id }

                                if (soundId != -1) {
                                    soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
                                }
                            },
                        contentScale = ContentScale.Inside
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isGamePaused = !isGamePaused },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                )
            ) {
                Text(
                    text = if (isGamePaused) "재생" else "일시 정지",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    touchCount = 0
                    failCount = 0
                    touchTargets = emptyList()
                    isGamePaused = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Text(
                    text = "초기화",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 배경음악 정리
            bgmPlayer?.stop()
            bgmPlayer?.release()
            // 효과음 정리
            soundPool.release()
        }
    }
}

@Composable
fun InfoBox(
    title: String,
    value: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
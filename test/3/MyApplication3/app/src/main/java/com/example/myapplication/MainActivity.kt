package com.example.myapplication

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var soundPool: SoundPool? = null
    private var soundId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        try {
            val afd = assets.openFd("pushpop_click.wav")
            soundId = soundPool?.load(afd, 1) ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PushPopGameScreen(
                        modifier = Modifier.padding(innerPadding),
                        onPlaySound = { pitch ->
                            if (soundId != 0) {
                                soundPool?.play(soundId, 1f, 1f, 1, 0, pitch)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }
}

data class ButtonTheme(
    val name: String,
    val raisedLight: String, val raisedMid1: String, val raisedMid2: String, val raisedDark: String,
    val pressedBody: String, val pressedDark: String
)

val buttonThemes = listOf(
    ButtonTheme("Red", "#ff8d92", "#ff4f5a", "#f32336", "#b80d24", "#d92e35", "#760b18"),
    ButtonTheme("Orange", "#ffd08b", "#ff9a24", "#f07808", "#a84300", "#e0851e", "#7a3800"),
    ButtonTheme("Yellow", "#fff6a2", "#ffe84a", "#ffca08", "#9b7200", "#e5b800", "#7a5600"),
    ButtonTheme("Green", "#b5ff9b", "#4de052", "#1caf34", "#08651d", "#22b830", "#044211"),
    ButtonTheme("Cyan", "#9cf0ff", "#31caff", "#009bdc", "#00507d", "#1296cb", "#003554"),
    ButtonTheme("Purple", "#e0a8ff", "#a65cff", "#7628e4", "#3b087d", "#9045df", "#270454"),
    ButtonTheme("Pink", "#ffc0e5", "#ff7ac5", "#e83b98", "#8d1555", "#d62d85", "#5c0c37"),
    ButtonTheme("Teal", "#a1fff1", "#35e4d1", "#00b9ad", "#00615e", "#00a196", "#003d3a"),
    ButtonTheme("Lilac", "#f2caff", "#c28aff", "#9252df", "#4d227d", "#803cce", "#311454")
)

@Composable
fun rememberSvgPainter(assetName: String, theme: ButtonTheme?, isPressed: Boolean): Painter {
    val context = LocalContext.current
    val picture = remember(assetName, theme, isPressed) {
        try {
            val fileName = if (isPressed) "pushpop_button_pressed.svg" else "pushpop_button.svg"
            val inputStream = try {
                context.assets.open(fileName)
            } catch (_: Exception) {
                MainActivity::class.java.classLoader?.getResourceAsStream("assets/$fileName")
                    ?: MainActivity::class.java.classLoader?.getResourceAsStream(fileName)
            }
            var svgText = inputStream?.bufferedReader()?.use { it.readText() } ?: return@remember null
            if (theme != null) {
                if (!isPressed) {
                    svgText = svgText
                        .replace("#ff8d92", theme.raisedLight, ignoreCase = true)
                        .replace("#ff4f5a", theme.raisedMid1, ignoreCase = true)
                        .replace("#f32336", theme.raisedMid2, ignoreCase = true)
                        .replace("#b80d24", theme.raisedDark, ignoreCase = true)
                } else {
                    svgText = svgText
                        .replace("#d92e35", theme.pressedBody, ignoreCase = true)
                        .replace("#760b18", theme.pressedDark, ignoreCase = true)
                }
            }
            val svg = SVG.getFromString(svgText)
            svg.renderToPicture()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    return remember(picture) {
        object : Painter() {
            override val intrinsicSize: Size
                get() = if (picture != null && picture.width > 0 && picture.height > 0) {
                    Size(picture.width.toFloat(), picture.height.toFloat())
                } else {
                    Size.Unspecified
                }

            override fun DrawScope.onDraw() {
                if (picture != null && picture.width > 0 && picture.height > 0) {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.scale(size.width / picture.width.toFloat(), size.height / picture.height.toFloat())
                        canvas.nativeCanvas.drawPicture(picture)
                        canvas.restore()
                    }
                }
            }
        }
    }
}

@Composable
fun rememberBoardPainter(): Painter {
    val context = LocalContext.current
    val picture = remember {
        try {
            val inputStream = try {
                context.assets.open("pushpop_board.svg")
            } catch (_: Exception) {
                MainActivity::class.java.classLoader?.getResourceAsStream("assets/pushpop_board.svg")
                    ?: MainActivity::class.java.classLoader?.getResourceAsStream("pushpop_board.svg")
            }
            val svgText = inputStream?.bufferedReader()?.use { it.readText() } ?: return@remember null
            val svg = SVG.getFromString(svgText)
            svg.renderToPicture()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    return remember(picture) {
        object : Painter() {
            override val intrinsicSize: Size
                get() = if (picture != null && picture.width > 0 && picture.height > 0) {
                    Size(picture.width.toFloat(), picture.height.toFloat())
                } else {
                    Size.Unspecified
                }

            override fun DrawScope.onDraw() {
                if (picture != null && picture.width > 0 && picture.height > 0) {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.scale(size.width / picture.width.toFloat(), size.height / picture.height.toFloat())
                        canvas.nativeCanvas.drawPicture(picture)
                        canvas.restore()
                    }
                }
            }
        }
    }
}

@Composable
fun PushPopGameScreen(
    modifier: Modifier = Modifier,
    onPlaySound: (Float) -> Unit
) {
    var selectedMode by remember { mutableStateOf(0) } // 0: Seesaw Mode, 1: All-Clear Mode
    val buttonStates = remember { mutableStateListOf(false, false, false, false, false, false, false, false, false) }
    var allClearCelebrating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val boardPainter = rememberBoardPainter()

    val coords = listOf(
        Pair(0.25625f, 0.290625f), Pair(0.5f, 0.290625f), Pair(0.74375f, 0.290625f),
        Pair(0.25625f, 0.5f),      Pair(0.5f, 0.5f),      Pair(0.74375f, 0.5f),
        Pair(0.25625f, 0.709375f), Pair(0.5f, 0.709375f), Pair(0.74375f, 0.709375f)
    )

    fun handleButtonClick(index: Int) {
        if (allClearCelebrating) return

        if (selectedMode == 0) {
            onPlaySound(1.0f)
            val currentState = buttonStates[index]
            buttonStates[index] = !currentState

            val oppositeIndex = 8 - index
            if (oppositeIndex != index) {
                buttonStates[oppositeIndex] = !buttonStates[index]
            }
            val neighborIndex = (index + 1) % 9
            if (neighborIndex != index && neighborIndex != oppositeIndex) {
                buttonStates[neighborIndex] = !buttonStates[index]
            }
        } else {
            if (!buttonStates[index]) {
                onPlaySound(1.0f)
                buttonStates[index] = true

                if (buttonStates.all { it }) {
                    allClearCelebrating = true
                    onPlaySound(1.3f)

                    scope.launch {
                        delay(700L)
                        buttonStates.fill(false)
                        allClearCelebrating = false
                    }
                }
            }
        }
    }

    val pressedCount = buttonStates.count { it }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header & Separated Mode Buttons
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "힐링 피젯 토이 (PushPop)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    Button(
                        onClick = {
                            selectedMode = 0
                            buttonStates.fill(false)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedMode == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (selectedMode == 0) 4.dp else 1.dp)
                    ) {
                        Text(
                            text = "🎮 시소·오뚝이 모드",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            selectedMode = 1
                            buttonStates.fill(false)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedMode == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (selectedMode == 1) 4.dp else 1.dp)
                    ) {
                        Text(
                            text = "🎯 올클리어 모드",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Center Board & Buttons with BoxWithConstraints for exact proportional sizing
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(960f / 1600f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                val boardWidth = maxWidth
                val boardHeight = maxHeight

                // Board Background
                androidx.compose.foundation.Image(
                    painter = boardPainter,
                    contentDescription = "Game Board",
                    modifier = Modifier.fillMaxSize()
                )

                // 9 Interactive Buttons precisely aligned to board wells (adjusted for drop shadow offset)
                coords.forEachIndexed { index, (xPct, yPct) ->
                    val isPressed = buttonStates[index]
                    val theme = buttonThemes[index]
                    val painter = rememberSvgPainter(
                        assetName = if (isPressed) "pushpop_button_pressed.svg" else "pushpop_button.svg",
                        theme = theme,
                        isPressed = isPressed
                    )

                    val scaleAnim by animateFloatAsState(
                        targetValue = if (isPressed) 0.88f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "buttonScale_$index"
                    )

                    // Proportional button size and exact center alignment to board wells
                    val btnSize = boardWidth * (188f / 960f)

                    Box(
                        modifier = Modifier
                            .size(btnSize)
                            .align(Alignment.TopStart)
                            .offset(
                                x = boardWidth * xPct - btnSize / 2,
                                y = boardHeight * yPct - btnSize / 2
                            )
                            .scale(scaleAnim)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                handleButtonClick(index)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painter,
                            contentDescription = "Button ${index + 1}",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Celebration Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = allClearCelebrating,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✨ ALL CLEAR! ✨",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Bottom Status / Guidance Text
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedMode == 0) {
                        Text(
                            text = "💡 버튼을 눌러 시소처럼 연속적인 리듬을 느껴보세요!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "🎯 올클리어 진행상황: $pressedCount / 9",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { pressedCount / 9f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun PushPopGamePreview() {
    MyApplicationTheme {
        PushPopGameScreen(onPlaySound = {})
    }
}

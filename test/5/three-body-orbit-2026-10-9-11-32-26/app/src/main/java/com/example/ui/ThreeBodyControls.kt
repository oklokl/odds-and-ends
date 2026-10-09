package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.OrbitPreset
import com.example.model.SystemEra
import com.example.model.TelemetryData
import com.example.model.TouchMode
import com.example.physics.ThreeBodySimulation
import kotlin.math.roundToInt

@Composable
fun TopStatusBar(
    era: SystemEra,
    isPaused: Boolean,
    showTelemetry: Boolean,
    onToggleTelemetry: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // System Era Status Pill
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xCC0E1222),
            border = androidx.compose.foundation.BorderStroke(1.dp, era.badgeColor.copy(alpha = 0.6f)),
            modifier = Modifier.testTag("era_badge")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(era.badgeColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = era.koreanTitle,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = era.description,
                        color = Color(0xFFB0B7C3),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Telemetry toggle
            IconButton(
                onClick = onToggleTelemetry,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (showTelemetry) Color(0x4400E5FF) else Color(0x331E293B))
                    .testTag("telemetry_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "원격 측정 (Telemetry)",
                    tint = if (showTelemetry) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Pause / Play
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x331E293B))
                    .testTag("pause_play_button")
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "재생" else "일시정지",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Reset
            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x331E293B))
                    .testTag("reset_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "초기화",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Tuning Settings
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x331E293B))
                    .testTag("tuning_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "물리 파라미터 조절",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TelemetryOverlay(
    telemetry: TelemetryData,
    stars: List<com.example.model.CelestialStar>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xD90C1021)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3300E5FF))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "삼체 실시간 궤도 원격 측정 (HUD)",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "평균 속도: ${telemetry.avgSpeed.roundToInt()} km/s",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chaos Index meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "혼돈 지수 (리야푸노프 섭동)",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Text(
                    text = "${(telemetry.chaosIndex * 100).roundToInt()}%",
                    color = if (telemetry.chaosIndex > 0.5f) Color(0xFFFF5252) else Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { telemetry.chaosIndex },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (telemetry.chaosIndex > 0.5f) Color(0xFFFF5252) else Color(0xFF00E676),
                trackColor = Color(0x33FFFFFF)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Inter-stellar distances
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DistanceColumn("d(A-B)", telemetry.distanceAB, Color(0xFF00E5FF))
                DistanceColumn("d(B-C)", telemetry.distanceBC, Color(0xFFFF2A85))
                DistanceColumn("d(C-A)", telemetry.distanceCA, Color(0xFFFFB300))
            }
        }
    }
}

@Composable
private fun DistanceColumn(label: String, dist: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "${dist.roundToInt()} AU",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BottomControlsDock(
    touchMode: TouchMode,
    onChangeTouchMode: (TouchMode) -> Unit,
    activePreset: OrbitPreset,
    onSelectPreset: (OrbitPreset) -> Unit,
    onInjectChaos: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xEA0B0E1E),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3338BDF8))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Preset Pills Scroll Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OrbitPreset.values().forEach { preset ->
                    val isSelected = preset == activePreset
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Text(
                                text = preset.koreanTitle,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.25f),
                            selectedLabelColor = Color(0xFF00E5FF),
                            containerColor = Color(0x331E293B),
                            labelColor = Color(0xFF94A3B8)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) Color(0xFF00E5FF) else Color(0x22FFFFFF)
                        )
                    )
                }
            }

            // Mode Row & Primary Chaos Impulse Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Touch Mode Selector
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TouchMode.values().forEach { mode ->
                        val isModeSelected = mode == touchMode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isModeSelected) Color(0xFF00E5FF) else Color(0x22334155))
                                .clickable { onChangeTouchMode(mode) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("touch_mode_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = when (mode) {
                                    TouchMode.IMPULSE -> "충격파"
                                    TouchMode.WELL -> "왜곡원"
                                    TouchMode.SLING -> "별 던지기"
                                },
                                color = if (isModeSelected) Color(0xFF040610) else Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Primary Chaos Injection Trigger
                Button(
                    onClick = onInjectChaos,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF2A85)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("inject_chaos_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "혼돈 주입",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningBottomSheet(
    simulation: ThreeBodySimulation,
    showFieldLines: Boolean,
    onToggleFieldLines: (Boolean) -> Unit,
    enableHaptics: Boolean,
    onToggleHaptics: (Boolean) -> Unit,
    enableSound: Boolean,
    onToggleSound: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var gravity by remember { mutableStateOf(simulation.gravityG) }
    var turbulence by remember { mutableStateOf(simulation.chaosTurbulence) }
    var speed by remember { mutableStateOf(simulation.simulationSpeed) }
    var trailLength by remember { mutableStateOf(simulation.maxTrailPoints.toFloat()) }
    var showLore by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E1326),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "물리 파라미터 및 천체 튜닝",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showLore = !showLore }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "삼체 세계관 안내",
                        tint = Color(0xFF00E5FF)
                    )
                }
            }

            AnimatedVisibility(visible = showLore) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x3300E5FF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "소설 & 영화 '삼체(Three-Body)' 속 천체역학",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "세 개의 항성이 서로의 중력으로 얽힌 다중성계는 해석적 일반해를 구할 수 없는 고전적 '삼체 문제'입니다. 아주 미세한 충격이나 파동에도 궤도가 극단적으로 분기하여 행성 삼체(Trisolaris)의 문명은 '안석기(규칙적)'와 '난세기(예측 불가능한 빙하기/화염기)'를 반복하게 됩니다. 본 앱은 사인/코사인 조화 난류와 중력 섭동을 결합해 이 매혹적인 혼돈의 춤을 구현했습니다.",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Gravity Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "중력 상수 (G)", color = Color.White, fontSize = 13.sp)
                    Text(
                        text = "${gravity.roundToInt()}",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = gravity,
                    onValueChange = {
                        gravity = it
                        simulation.gravityG = it
                    },
                    valueRange = 800f..4500f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    )
                )
            }

            // Chaos Turbulence Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "사인/코사인 카오스 파동 세기 (Turbulence)", color = Color.White, fontSize = 13.sp)
                    Text(
                        text = "${turbulence.roundToInt()}",
                        color = Color(0xFFFF2A85),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = turbulence,
                    onValueChange = {
                        turbulence = it
                        simulation.chaosTurbulence = it
                    },
                    valueRange = 0f..120f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF2A85),
                        activeTrackColor = Color(0xFFFF2A85)
                    )
                )
            }

            // Speed Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "시뮬레이션 재생 속도", color = Color.White, fontSize = 13.sp)
                    Text(
                        text = "${String.format("%.2f", speed)}x",
                        color = Color(0xFFFFB300),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        simulation.simulationSpeed = it
                    },
                    valueRange = 0.25f..2.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFB300),
                        activeTrackColor = Color(0xFFFFB300)
                    )
                )
            }

            // Trail Length Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "궤적 잔상 길이 (Trail Length)", color = Color.White, fontSize = 13.sp)
                    Text(
                        text = "${trailLength.roundToInt()}",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = trailLength,
                    onValueChange = {
                        trailLength = it
                        simulation.maxTrailPoints = it.toInt()
                    },
                    valueRange = 30f..260f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    )
                )
            }

            // Field Lines Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "중력장 텐션 라인 표시", color = Color.White, fontSize = 14.sp)
                    Text(
                        text = "세 별 간의 인력선 및 질량 중심(Barycenter) 표시",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = showFieldLines,
                    onCheckedChange = onToggleFieldLines,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF00E5FF)
                    )
                )
            }

            // Sound Effects Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "별 클릭 크리스탈 사운드", color = Color.White, fontSize = 14.sp)
                    Text(
                        text = "별(항성) 및 화면 터치 시 크리스탈 효과음 재생",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = enableSound,
                    onCheckedChange = onToggleSound,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF00E5FF)
                    )
                )
            }

            // Haptics Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "햅틱 진동 피드백", color = Color.White, fontSize = 14.sp)
                    Text(
                        text = "화면 터치 충격파 및 별들의 초근접 스윙바이 시 진동",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = enableHaptics,
                    onCheckedChange = onToggleHaptics,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF00E5FF)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "완료", color = Color.White)
            }
        }
    }
}

package com.krdondon.quickpush.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krdondon.quickpush.model.GameMode
import com.krdondon.quickpush.ui.components.BunnyQuickPushConsole
import com.krdondon.quickpush.ui.components.GameTopBar
import com.krdondon.quickpush.ui.components.ScoreStatusCard
import com.krdondon.quickpush.ui.dialogs.ConfirmResetDialog
import com.krdondon.quickpush.ui.dialogs.LevelUpDialog
import com.krdondon.quickpush.ui.dialogs.ModeSelectorDialog
import com.krdondon.quickpush.ui.dialogs.PauseDialog
import com.krdondon.quickpush.ui.dialogs.PatternFailDialog
import com.krdondon.quickpush.ui.dialogs.SettingsDialog
import com.krdondon.quickpush.ui.dialogs.TimeAttackResultDialog
import com.krdondon.quickpush.ui.theme.PastelBackground
import com.krdondon.quickpush.ui.theme.PastelPinkPrimary
import com.krdondon.quickpush.ui.theme.TextPrimary

@Composable
fun PushPopGameScreen(
  viewModel: PushPopGameViewModel,
  modifier: Modifier = Modifier
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600

  var showModeSelector by remember { mutableStateOf(false) }
  var isSideMenuVisible by remember { mutableStateOf(false) }
  var isTopMenuCollapsed by remember { mutableStateOf(false) }

  BackHandler(enabled = !uiState.isPaused && !uiState.showSettingsDialog) {
    if (isLandscape && isSideMenuVisible) {
      isSideMenuVisible = false
    } else {
      viewModel.pauseGame()
    }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    modifier = modifier.fillMaxSize(),
    containerColor = PastelBackground
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .background(PastelBackground)
    ) {
      if (isLandscape) {
        // --- Landscape Full-Screen Layout ---
        // 1. Center Game Console: 화면 전체 공간을 활용하여 모든 버튼이 한눈에 가득 차게 배치
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp, horizontal = 12.dp),
          contentAlignment = Alignment.Center
        ) {
          BunnyQuickPushConsole(
            bubbles = uiState.bubbles,
            gridDimension = uiState.gridDimension,
            consoleColor = uiState.consoleColor,
            isSoundEnabled = uiState.isSoundEnabled,
            isGlowEnabled = uiState.isGlowEnabled,
            isWaveEnabled = uiState.isWaveEnabled,
            isBorderSparkleEnabled = uiState.isBorderSparkleEnabled,
            animationIntensity = uiState.animationIntensity,
            floatingScores = uiState.floatingScores,
            particles = uiState.particles,
            onBubbleClick = { id, x, y -> viewModel.onBubbleClicked(id, x, y) },
            onModeButtonClick = { showModeSelector = true },
            onPowerButtonClick = { viewModel.resetGame() },
            onVolumeButtonClick = { viewModel.toggleSound(!uiState.isSoundEnabled) },
            onBackPushClick = { viewModel.onBackPushClicked() },
            onGridAvailableSizeChanged = { w, h ->
              viewModel.updateGridDimensions(w, h, isLandscape = true, isTablet = isTablet)
            }
          )
        }

        // 2. 좌측 상단 플로팅 토글 칩: [ ☰ 점수 & 메뉴 ] 버튼 (접기/펼치기)
        Card(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 14.dp, top = 8.dp)
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clickable { isSideMenuVisible = !isSideMenuVisible },
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = if (isSideMenuVisible) PastelPinkPrimary else Color.White),
          elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = if (isSideMenuVisible) Icons.Default.ChevronLeft else Icons.Default.Menu,
              contentDescription = "메뉴 토글",
              tint = if (isSideMenuVisible) Color.White else PastelPinkPrimary,
              modifier = Modifier.size(17.dp)
            )
            Text(
              text = if (isSideMenuVisible) "접기" else "점수 & 메뉴",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = if (isSideMenuVisible) Color.White else TextPrimary
            )
          }
        }

        // 3. 슬라이드 사이드 메뉴 패널 (부드럽게 밀어서 나타나고 숨겨지는 반응형 오버레이)
        AnimatedVisibility(
          visible = isSideMenuVisible,
          enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
          exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
          modifier = Modifier.align(Alignment.TopStart)
        ) {
          Card(
            modifier = Modifier
              .width(320.dp)
              .fillMaxHeight()
              .padding(start = 8.dp, top = 44.dp, bottom = 8.dp)
              .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x35000000), spotColor = Color(0x35000000)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
          ) {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              GameTopBar(
                currentMode = uiState.gameMode,
                isSoundEnabled = uiState.isSoundEnabled,
                onToggleSound = { viewModel.toggleSound(!uiState.isSoundEnabled) },
                onOpenModeSelector = { showModeSelector = true },
                onPause = { viewModel.pauseGame() },
                onReset = { viewModel.resetGame() },
                onOpenSettings = { viewModel.showSettings(true) }
              )

              val progress = if (uiState.levelGoalPops > 0) {
                val litRemaining = uiState.bubbles.count { it.isLit }
                (uiState.levelGoalPops - litRemaining).toFloat() / uiState.levelGoalPops.toFloat()
              } else 0f

              ScoreStatusCard(
                gameMode = uiState.gameMode,
                score = uiState.score,
                touches = uiState.sessionTouches,
                currentLevel = uiState.currentLevel,
                levelProgress = progress,
                comboCount = uiState.comboCount,
                comboMultiplier = uiState.comboMultiplier,
                timeRemainingSec = uiState.timeRemainingSec,
                freePlayElapsedSec = uiState.freePlayElapsedSec,
                patternRound = uiState.patternRound,
                isPatternShowing = uiState.isPatternShowing
              )
            }
          }
        }
      } else {
        // --- Portrait Layout: TopBar & ScoreCard -> Bunny Quick Push Console in Center ---
        Column(
          modifier = Modifier
            .fillMaxSize()
            .widthIn(max = if (isTablet) 720.dp else 560.dp)
            .align(Alignment.Center)
            .padding(horizontal = 6.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          // 상단 메뉴 영역 (접기/펼치기 가능)
          Column(modifier = Modifier.fillMaxWidth()) {
            AnimatedVisibility(
              visible = !isTopMenuCollapsed,
              enter = androidx.compose.animation.expandVertically() + fadeIn(),
              exit = androidx.compose.animation.shrinkVertically() + fadeOut()
            ) {
              Column(modifier = Modifier.fillMaxWidth()) {
                GameTopBar(
                  currentMode = uiState.gameMode,
                  isSoundEnabled = uiState.isSoundEnabled,
                  onToggleSound = { viewModel.toggleSound(!uiState.isSoundEnabled) },
                  onOpenModeSelector = { showModeSelector = true },
                  onPause = { viewModel.pauseGame() },
                  onReset = { viewModel.resetGame() },
                  onOpenSettings = { viewModel.showSettings(true) }
                )

                val progress = if (uiState.levelGoalPops > 0) {
                  val litRemaining = uiState.bubbles.count { it.isLit }
                  (uiState.levelGoalPops - litRemaining).toFloat() / uiState.levelGoalPops.toFloat()
                } else 0f

                ScoreStatusCard(
                  gameMode = uiState.gameMode,
                  score = uiState.score,
                  touches = uiState.sessionTouches,
                  currentLevel = uiState.currentLevel,
                  levelProgress = progress,
                  comboCount = uiState.comboCount,
                  comboMultiplier = uiState.comboMultiplier,
                  timeRemainingSec = uiState.timeRemainingSec,
                  freePlayElapsedSec = uiState.freePlayElapsedSec,
                  patternRound = uiState.patternRound,
                  isPatternShowing = uiState.isPatternShowing
                )
              }
            }

            // 상단 메뉴 접기 / 펼치기 핸들 바 (클릭 시 토끼 귀가 최상단으로 시원하게 올라감)
            Card(
              modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 2.dp, bottom = 2.dp)
                .clickable { isTopMenuCollapsed = !isTopMenuCollapsed }
                .shadow(4.dp, RoundedCornerShape(16.dp)),
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(
                  imageVector = if (isTopMenuCollapsed) Icons.Default.Menu else Icons.Default.ChevronLeft,
                  contentDescription = "상단 메뉴 접기/펼치기",
                  tint = PastelPinkPrimary,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = if (isTopMenuCollapsed) "메뉴 펼치기 (점수 ${uiState.score} · Stage ${uiState.currentLevel})" else "상단 메뉴 접기 (화면 확대)",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = TextPrimary
                )
              }
            }
          }

          // Center: Bunny Electronic Console (상단 접을 시 남은 화면 전체를 채우며 버튼이 유연하게 확대)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            BunnyQuickPushConsole(
              bubbles = uiState.bubbles,
              gridDimension = uiState.gridDimension,
              consoleColor = uiState.consoleColor,
              isSoundEnabled = uiState.isSoundEnabled,
              isGlowEnabled = uiState.isGlowEnabled,
              isWaveEnabled = uiState.isWaveEnabled,
              isBorderSparkleEnabled = uiState.isBorderSparkleEnabled,
              animationIntensity = uiState.animationIntensity,
              floatingScores = uiState.floatingScores,
              particles = uiState.particles,
              onBubbleClick = { id, x, y -> viewModel.onBubbleClicked(id, x, y) },
              onModeButtonClick = { showModeSelector = true },
              onPowerButtonClick = { viewModel.resetGame() },
              onVolumeButtonClick = { viewModel.toggleSound(!uiState.isSoundEnabled) },
              onBackPushClick = { viewModel.onBackPushClicked() },
              onGridAvailableSizeChanged = { w, h ->
                viewModel.updateGridDimensions(w, h, isLandscape = false, isTablet = isTablet)
              }
            )
          }
        }
      }

      // 화면 테두리 3D 비네팅 / 소프트 테두리 그림자 (메인화면 입체감 연출)
      ScreenEdgeShadowOverlay()
    }
  }

  // Dialogs
  if (uiState.showLevelUpDialog) {
    LevelUpDialog(
      currentLevel = uiState.currentLevel,
      nextGoal = uiState.levelGoalPops,
      onDismiss = { viewModel.dismissLevelUpDialog() }
    )
  }

  if (uiState.showTimeAttackResultDialog) {
    TimeAttackResultDialog(
      uiState = uiState,
      onRetry = {
        viewModel.dismissTimeAttackDialog()
        viewModel.resetGame()
      },
      onDismiss = { viewModel.dismissTimeAttackDialog() }
    )
  }

  if (uiState.showPauseDialog) {
    PauseDialog(
      onResume = { viewModel.resumeGame() },
      onReset = {
        viewModel.resumeGame()
        viewModel.resetGame()
      },
      onOpenSettings = {
        viewModel.resumeGame()
        viewModel.showSettings(true)
      }
    )
  }

  if (showModeSelector) {
    ModeSelectorDialog(
      currentMode = uiState.gameMode,
      timeDuration = uiState.timeAttackDuration,
      onSelectMode = { mode -> viewModel.setGameMode(mode) },
      onSelectDuration = { dur -> viewModel.setTimeAttackDuration(dur) },
      onDismiss = { showModeSelector = false }
    )
  }

  if (uiState.showSettingsDialog) {
    SettingsDialog(
      uiState = uiState,
      onDismiss = { viewModel.showSettings(false) },
      onToggleSound = { viewModel.toggleSound(it) },
      onSetSoundStyle = { viewModel.setSoundStyle(it) },
      onToggleHaptic = { viewModel.toggleHaptic(it) },
      onToggleGlow = { viewModel.toggleGlow(it) },
      onToggleWave = { viewModel.toggleWave(it) },
      onToggleSparkle = { viewModel.toggleSparkle(it) },
      onSetAnimIntensity = { viewModel.setAnimationIntensity(it) },
      onSetBubbleTheme = { viewModel.setBubbleTheme(it) },
      onSetConsoleColor = { viewModel.setConsoleColor(it) },
      onToggleSiliconeMode = { viewModel.toggleSiliconeMode(it) },
      onRequestResetData = { viewModel.showConfirmReset(true) }
    )
  }

  if (uiState.showConfirmResetDialog) {
    ConfirmResetDialog(
      onConfirm = { viewModel.resetAllDataConfirmed() },
      onDismiss = { viewModel.showConfirmReset(false) }
    )
  }

  if (uiState.showPatternFailDialog) {
    PatternFailDialog(
      onRetry = { viewModel.retryPattern() },
      onDismiss = { viewModel.setGameMode(GameMode.FREE_PLAY) }
    )
  }
}

/**
 * 화면 테두리 주변에 은은한 3D 내부 그림자(비네팅)를 둘러 입체감을 살리는 오버레이
 */
@Composable
private fun ScreenEdgeShadowOverlay() {
  Canvas(
    modifier = Modifier.fillMaxSize()
  ) {
    val w = size.width
    val h = size.height
    val shadowDepth = 16f
    val shadowColor = Color(0x1A4A2835)

    // Top edge shadow
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(shadowColor, Color.Transparent),
        startY = 0f,
        endY = shadowDepth
      ),
      size = Size(w, shadowDepth)
    )

    // Bottom edge shadow
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, shadowColor),
        startY = h - shadowDepth,
        endY = h
      ),
      topLeft = Offset(0f, h - shadowDepth),
      size = Size(w, shadowDepth)
    )

    // Left edge shadow
    drawRect(
      brush = Brush.horizontalGradient(
        colors = listOf(shadowColor, Color.Transparent),
        startX = 0f,
        endX = shadowDepth
      ),
      size = Size(shadowDepth, h)
    )

    // Right edge shadow
    drawRect(
      brush = Brush.horizontalGradient(
        colors = listOf(Color.Transparent, shadowColor),
        startX = w - shadowDepth,
        endX = w
      ),
      topLeft = Offset(w - shadowDepth, 0f),
      size = Size(shadowDepth, h)
    )
  }
}

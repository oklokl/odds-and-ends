package com.krdondon.quickpush.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.krdondon.quickpush.audio.SoundManager
import com.krdondon.quickpush.data.GamePreferences
import com.krdondon.quickpush.haptics.HapticManager
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleItem
import com.krdondon.quickpush.model.BubbleTheme
import com.krdondon.quickpush.model.ConsoleColor
import com.krdondon.quickpush.model.FloatingScore
import com.krdondon.quickpush.model.GameMode
import com.krdondon.quickpush.model.GridCalculator
import com.krdondon.quickpush.model.GridDimension
import com.krdondon.quickpush.model.Particle
import com.krdondon.quickpush.model.SoundStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class PushPopUiState(
  val gameMode: GameMode = GameMode.LEVEL_CHALLENGE, // Breakthrough mode by default
  val consoleColor: ConsoleColor = ConsoleColor.PINK_BUNNY,
  val score: Int = 0,
  val sessionTouches: Int = 0,
  val currentLevel: Int = 1,
  val levelGoalPops: Int = 3,
  val levelCurrentPops: Int = 0,
  val comboCount: Int = 0,
  val comboMultiplier: Float = 1.0f,
  val timeRemainingSec: Int = 60,
  val timeAttackDuration: Int = 60,
  val freePlayElapsedSec: Long = 0L,
  val isPaused: Boolean = false,
  val isGameOver: Boolean = false,
  val bubbles: List<BubbleItem> = emptyList(),
  val gridDimension: GridDimension = GridDimension(4, 3, 76f, 10f),
  val theme: BubbleTheme = BubbleTheme.VIVID_RAINBOW,
  val soundStyle: SoundStyle = SoundStyle.CLASSIC_POP,
  val isMusicEnabled: Boolean = true,
  val isSoundEnabled: Boolean = true,
  val isHapticEnabled: Boolean = true,
  val isGlowEnabled: Boolean = true,
  val isWaveEnabled: Boolean = true,
  val isBorderSparkleEnabled: Boolean = true,
  val animationIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
  val isToggleMode: Boolean = true,
  val floatingScores: List<FloatingScore> = emptyList(),
  val particles: List<Particle> = emptyList(),
  // Difficulty & Gauge
  val difficulty: com.krdondon.quickpush.model.GameDifficulty = com.krdondon.quickpush.model.GameDifficulty.BEGINNER,
  val gaugeFraction: Float = 1.0f,
  val showDifficultyDialog: Boolean = false,
  val showStageTimeoutDialog: Boolean = false,
  val showLevelUpBanner: Boolean = false,
  val levelUpBannerMessage: String = "",
  // Dialogs
  val showLevelUpDialog: Boolean = false,
  val showTimeAttackResultDialog: Boolean = false,
  val showPauseDialog: Boolean = false,
  val showSettingsDialog: Boolean = false,
  val showConfirmResetDialog: Boolean = false,
  val showPatternFailDialog: Boolean = false,
  // Pattern / Memory Mode
  val isPatternShowing: Boolean = false,
  val patternRound: Int = 1,
  val highlightedBubbleId: Int? = null,
  // High scores & stats
  val bestTimeAttack30: Int = 0,
  val bestTimeAttack60: Int = 0,
  val bestTimeAttack120: Int = 0,
  val maxLevelReached: Int = 1,
  val lifetimePops: Long = 0L
)

class PushPopGameViewModel(application: Application) : AndroidViewModel(application) {

  private val prefs = GamePreferences(application)
  val soundManager = SoundManager(application)
  val hapticManager = HapticManager(application)
  val bgmPlayer = com.krdondon.quickpush.audio.BgmPlayer(application)

  private val _uiState = MutableStateFlow(PushPopUiState())
  val uiState: StateFlow<PushPopUiState> = _uiState.asStateFlow()

  private var timerJob: Job? = null
  private var stageTimerJob: Job? = null
  private var patternJob: Job? = null
  private var infinitePopJob: Job? = null
  private var stageDurationMs: Long = 12000L
  private var stageRemainingMs: Long = 12000L
  private var lastTapTimestamp = 0L
  private var lastInfiniteTapTimestamp = 0L
  private val infiniteRecentlyPoppedIds = mutableListOf<Int>()
  private var patternSequence: MutableList<Int> = mutableListOf()
  private var userPatternStep = 0

  init {
    loadSettingsFromPrefs()
    // Initial standard 3x3 grid (9 bubbles) - 화면 내 완전 가시성 보장
    rebuildBubbles(rows = 3, cols = 3, preserveState = false)

    if (prefs.isGameSuspended) {
      // 50분 등 장시간 백그라운드 후 OS에 의해 프로세스가 재시작된 경우:
      // 게임을 바로 시작하지 않고, 이전 진행 상황을 복원한 후 [게임 일시정지] 팝업으로 안전 대기!
      val savedModeName = prefs.suspendedGameMode
      val savedMode = try {
        GameMode.valueOf(savedModeName)
      } catch (_: Exception) {
        GameMode.LEVEL_CHALLENGE
      }
      val savedLevel = prefs.suspendedLevel
      val savedScore = prefs.suspendedScore
      val savedRemaining = prefs.suspendedRemainingMs

      stageDurationMs = calculateStageDurationMs(savedLevel, prefs.difficulty)
      stageRemainingMs = if (savedRemaining > 0) savedRemaining else stageDurationMs
      val fraction = (stageRemainingMs.toFloat() / stageDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

      _uiState.update {
        it.copy(
          gameMode = savedMode,
          currentLevel = savedLevel,
          score = savedScore,
          gaugeFraction = fraction,
          isPaused = true,
          showPauseDialog = true
        )
      }
      // ★ 2중 안전장치: 모드별 버블판을 완전히 깨끗하게 재구성하여 스피드 3개 불빛 등 100% 정상 동기화
      sanitizeAndSetupModeBubbles(savedMode, savedLevel)
    } else {
      startActiveModeTimers()
      startStageForLevel(1)
      bgmPlayer.playCurrentTrack()
    }
  }

  private fun loadSettingsFromPrefs() {
    val musicEnabled = prefs.isMusicEnabled
    val soundEnabled = prefs.isSoundEnabled
    val soundSt = prefs.soundStyle
    val hapticEnabled = prefs.isHapticEnabled

    bgmPlayer.isMusicEnabled = musicEnabled
    soundManager.isSoundEnabled = soundEnabled
    soundManager.soundStyle = soundSt
    hapticManager.isHapticEnabled = hapticEnabled

    _uiState.update {
      it.copy(
        consoleColor = prefs.consoleColor,
        isMusicEnabled = musicEnabled,
        isSoundEnabled = soundEnabled,
        soundStyle = soundSt,
        isHapticEnabled = hapticEnabled,
        isGlowEnabled = prefs.isGlowEnabled,
        isWaveEnabled = prefs.isWaveEnabled,
        isBorderSparkleEnabled = prefs.isBorderSparkleEnabled,
        animationIntensity = prefs.animationIntensity,
        theme = prefs.bubbleTheme,
        isToggleMode = prefs.isToggleMode,
        difficulty = prefs.difficulty,
        bestTimeAttack30 = prefs.getTimeAttackHighScore(30),
        bestTimeAttack60 = prefs.getTimeAttackHighScore(60),
        bestTimeAttack120 = prefs.getTimeAttackHighScore(120),
        maxLevelReached = prefs.maxLevelReached,
        lifetimePops = prefs.lifetimePops
      )
    }
  }

  fun updateGridDimensions(widthDp: Float, heightDp: Float, isLandscape: Boolean, isTablet: Boolean) {
    if (widthDp <= 0 || heightDp <= 0) return
    val newGrid = GridCalculator.calculateGridLayout(widthDp, heightDp, isLandscape, isTablet)
    val curGrid = _uiState.value.gridDimension

    if (newGrid.rows != curGrid.rows || newGrid.cols != curGrid.cols || newGrid.bubbleSizeDp != curGrid.bubbleSizeDp) {
      _uiState.update { it.copy(gridDimension = newGrid) }
      rebuildBubbles(newGrid.rows, newGrid.cols, preserveState = true)

      if (_uiState.value.gameMode == GameMode.LEVEL_CHALLENGE) {
        val litCount = _uiState.value.bubbles.count { it.isLit }
        if (litCount == 0) {
          startStageForLevel(_uiState.value.currentLevel)
        }
      }
    }
  }

  private fun rebuildBubbles(rows: Int, cols: Int, preserveState: Boolean = false) {
    val currentBubblesMap = if (preserveState) _uiState.value.bubbles.associateBy { it.id } else emptyMap()
    val theme = _uiState.value.theme
    val colors = theme.colors
    val totalColors = colors.size

    val greenLed = Color(0xFF00E676)
    val redLed = Color(0xFFFF2A6D)

    val newBubbles = ArrayList<BubbleItem>(rows * cols)
    var index = 0
    for (r in 0 until rows) {
      for (c in 0 until cols) {
        val color = colors[(r + c) % totalColors]
        val existing = currentBubblesMap[index]
        newBubbles.add(
          BubbleItem(
            id = index,
            row = r,
            col = c,
            baseColor = color,
            glowColor = color,
            isPopped = existing?.isPopped ?: false,
            isHighlighted = existing?.isHighlighted ?: false,
            isLit = existing?.isLit ?: false,
            ledColor = existing?.ledColor ?: (if (index % 2 == 0) greenLed else redLed),
            hasCarrotIcon = true
          )
        )
        index++
      }
    }
    _uiState.update { it.copy(bubbles = newBubbles) }
  }

  fun onBubbleClicked(bubbleId: Int, clickX: Float = 0f, clickY: Float = 0f) {
    val state = _uiState.value
    if (state.isPaused || state.isGameOver) return

    val targetBubble = state.bubbles.find { it.id == bubbleId } ?: return

    soundManager.playBubblePop()
    hapticManager.playBubblePopHaptic()

    val now = SystemClock.elapsedRealtime()
    val isCombo = (now - lastTapTimestamp) <= 1200L
    lastTapTimestamp = now

    val newCombo = if (isCombo) (state.comboCount + 1).coerceAtMost(50) else 1
    val multiplier = when {
      newCombo >= 20 -> 3.0f
      newCombo >= 10 -> 2.5f
      newCombo >= 5 -> 2.0f
      newCombo >= 2 -> 1.5f
      else -> 1.0f
    }
    val earnedPoints = (15 * multiplier).toInt()

    val newLifetime = state.lifetimePops + 1
    prefs.lifetimePops = newLifetime

    emitFloatingScore(earnedPoints, newCombo, clickX, clickY)
    emitParticles(clickX, clickY)

    when (state.gameMode) {
      GameMode.LEVEL_CHALLENGE -> handleBreakthroughBubbleTap(bubbleId, targetBubble, earnedPoints, newCombo, multiplier, newLifetime)
      GameMode.TIME_ATTACK -> handleTimeAttackBubbleTap(bubbleId, targetBubble, earnedPoints, newCombo, multiplier, newLifetime)
      GameMode.FREE_PLAY -> handleFreePlayBubbleTap(bubbleId, earnedPoints, newCombo, multiplier, newLifetime)
      GameMode.INFINITE_POP -> handleInfinitePopBubbleTap(bubbleId, earnedPoints, newCombo, multiplier, newLifetime)
      GameMode.PATTERN_FOLLOW -> handlePatternBubbleTap(bubbleId, earnedPoints, newCombo, multiplier, newLifetime)
    }
  }

  /**
   * 돌파 모드 (Breakthrough Stage Mode):
   * When user pushes a lit bubble, its light goes OFF and it pops down.
   * When all lit bubbles in the stage are popped, immediate stage clear celebration!
   */
  private fun handleBreakthroughBubbleTap(
    bubbleId: Int,
    targetBubble: BubbleItem,
    points: Int,
    combo: Int,
    multiplier: Float,
    lifetime: Long
  ) {
    val bonusForLit = if (targetBubble.isLit) 30 else 5
    val visibleCount = _uiState.value.gridDimension.rows * _uiState.value.gridDimension.cols

    var isStageCleared = false
    _uiState.update { s ->
      val updatedBubbles = s.bubbles.map { b ->
        if (b.id == bubbleId) {
          b.copy(isPopped = true, isLit = false)
        } else b
      }

      val remainingLitCount = updatedBubbles.take(visibleCount).count { it.isLit }
      isStageCleared = (remainingLitCount == 0)

      s.copy(
        score = s.score + points + bonusForLit,
        sessionTouches = s.sessionTouches + 1,
        comboCount = combo,
        comboMultiplier = multiplier,
        lifetimePops = lifetime,
        bubbles = updatedBubbles
      )
    }

    // Check if stage was cleared!
    if (isStageCleared) {
      triggerStageClearCelebration()
    }
  }

  private fun triggerStageClearCelebration() {
    stageTimerJob?.cancel()
    val nextLevel = _uiState.value.currentLevel + 1
    if (nextLevel > prefs.maxLevelReached) {
      prefs.maxLevelReached = nextLevel
    }

    // Immediate clear fanfare sound & haptic!
    soundManager.playLevelUp()
    hapticManager.playCelebrationHaptic()

    val isMilestone = (nextLevel > 1 && (nextLevel - 1) % 5 == 0)
    _uiState.update {
      it.copy(
        score = it.score + 100, // Stage clear bonus points!
        currentLevel = nextLevel,
        maxLevelReached = prefs.maxLevelReached,
        showLevelUpDialog = false, // 화면 중앙 모달 대신
        showLevelUpBanner = isMilestone, // 상단 배너로 표시
        levelUpBannerMessage = if (isMilestone) "★ ${nextLevel - 1}단계 돌파 완료! ★" else ""
      )
    }

    // 2초 후 상단 배너 자동 소멸 (화면 중앙 및 버블판을 절대 가리지 않음)
    if (isMilestone) {
      viewModelScope.launch {
        delay(2000)
        _uiState.update { it.copy(showLevelUpBanner = false) }
      }
    }

    // Auto snap bubbles back out and start next stage
    viewModelScope.launch {
      delay(280)
      soundManager.playSnapBack()
      startStageForLevel(nextLevel)
    }
  }

  /**
   * Called when player presses the big "BACK PUSH (뒷면 푸시)" plate on the console bottom!
   * Pops all bubbles back out with a crisp mechanical snap sound and haptic vibration.
   */
  fun onBackPushClicked() {
    soundManager.playSnapBack()
    hapticManager.playBubblePopHaptic()

    val state = _uiState.value
    if (state.gameMode == GameMode.LEVEL_CHALLENGE) {
      val visibleCount = state.gridDimension.rows * state.gridDimension.cols
      val remainingLit = state.bubbles.take(visibleCount).count { it.isLit }
      if (remainingLit == 0) {
        // Clear stage and move next
        triggerStageClearCelebration()
      } else {
        // Reset bubbles popped state while preserving lights
        _uiState.update { s ->
          s.copy(bubbles = s.bubbles.map { it.copy(isPopped = false) })
        }
      }
    } else {
      // In Free Play / other modes: snap all bubbles back up
      _uiState.update { s ->
        s.copy(bubbles = s.bubbles.map { it.copy(isPopped = false) })
      }
    }
  }

  private fun calculateStageDurationMs(level: Int, diff: com.krdondon.quickpush.model.GameDifficulty): Long {
    val sec = when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> {
        // 초보자: 1단계 12초에서 시작, 레벨마다 0.15초씩 미세 감소, 최소 7.0초 유지 (유치원생도 여유있게 가능)
        (12.0f - (level - 1) * 0.15f).coerceAtLeast(7.0f)
      }
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> {
        // 중급자: 1단계 7.5초에서 시작, 레벨마다 0.15초씩 미세 감소, 최소 4.5초 유지 (중학생 수준)
        (7.5f - (level - 1) * 0.15f).coerceAtLeast(4.5f)
      }
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> {
        // 고수: 1단계 4.5초에서 시작, 레벨마다 0.12초씩 미세 감소, 최소 2.5초 유지 (대학생 수준 초고속)
        (4.5f - (level - 1) * 0.12f).coerceAtLeast(2.5f)
      }
    }
    return (sec * 1000).toLong()
  }

  /**
   * 모드별 2중 안전장치 & 클린 동기화 함수:
   * 복구 시, 모드 전환 시, 장시간 절전 복귀 시 버블판 전체를 완전 무결하게 새로 클린하고
   * 해당 모드의 정상 상태(스피드 3개 불빛, 돌파 레벨 목표 불빛 등)로 완벽하게 재싱크합니다.
   */
  private fun sanitizeAndSetupModeBubbles(mode: GameMode, level: Int) {
    val rows = _uiState.value.gridDimension.rows
    val cols = _uiState.value.gridDimension.cols
    val visibleCount = (rows * cols).coerceAtLeast(4)

    if (_uiState.value.bubbles.size != visibleCount) {
      rebuildBubbles(rows, cols, preserveState = false)
    }

    when (mode) {
      GameMode.LEVEL_CHALLENGE -> {
        setupStageBubblesOnly(level)
      }
      GameMode.TIME_ATTACK -> {
        val greenLed = Color(0xFF00E676)
        val redLed = Color(0xFFFF2A6D)
        val initialLit = (0 until visibleCount).shuffled().take(3).toSet()
        _uiState.update { s ->
          s.copy(
            bubbles = s.bubbles.take(visibleCount).mapIndexed { idx, b ->
              b.copy(
                isPopped = false,
                isLit = idx in initialLit,
                ledColor = if (idx % 2 == 0) greenLed else redLed
              )
            }
          )
        }
      }
      GameMode.PATTERN_FOLLOW, GameMode.FREE_PLAY, GameMode.INFINITE_POP -> {
        _uiState.update { s ->
          s.copy(
            bubbles = s.bubbles.take(visibleCount).map { b ->
              b.copy(isPopped = false, isLit = false, isHighlighted = false)
            }
          )
        }
      }
    }
  }

  /**
   * 스피드 모드 2중 안전장치:
   * 불빛이 1개만 켜지거나 0개가 되어 조작이 잠기는 버그를 100% 방지하기 위해
   * 항상 정확히 3개의 불빛이 점등되어 있도록 실시간 자가 치유합니다.
   */
  private fun ensureTimeAttackBubblesHealthy() {
    val state = _uiState.value
    if (state.gameMode != GameMode.TIME_ATTACK) return

    val litCount = state.bubbles.count { it.isLit }
    if (litCount < 3) {
      val greenLed = Color(0xFF00E676)
      val redLed = Color(0xFFFF2A6D)
      val currentLitIds = state.bubbles.filter { it.isLit }.map { it.id }.toMutableSet()
      val unlitBubbles = state.bubbles.filter { !it.isLit }.shuffled()
      val need = 3 - litCount
      val toLit = unlitBubbles.take(need).map { it.id }.toSet()
      currentLitIds.addAll(toLit)

      _uiState.update { s ->
        s.copy(
          bubbles = s.bubbles.map { b ->
            if (b.id in currentLitIds) {
              b.copy(isLit = true, isPopped = false, ledColor = if (b.id % 2 == 0) greenLed else redLed)
            } else {
              b.copy(isLit = false, isPopped = false)
            }
          }
        )
      }
    }
  }

  private fun setupStageBubblesOnly(level: Int) {
    val rows = _uiState.value.gridDimension.rows
    val cols = _uiState.value.gridDimension.cols
    val visibleCount = (rows * cols).coerceAtLeast(4)

    if (_uiState.value.bubbles.size != visibleCount) {
      rebuildBubbles(rows, cols, preserveState = false)
    }

    val diff = _uiState.value.difficulty
    val litCount = when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> {
        (2 + ((level - 1) / 3)).coerceIn(2, 5.coerceAtMost(visibleCount - 1))
      }
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> {
        (3 + ((level - 1) / 2)).coerceIn(3, 7.coerceAtMost(visibleCount - 1))
      }
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> {
        (4 + ((level - 1) / 2)).coerceIn(4, 8.coerceAtMost(visibleCount - 1))
      }
    }

    val greenLed = Color(0xFF00E676)
    val redLed = Color(0xFFFF2A6D)
    val shuffledIndices = (0 until visibleCount).shuffled().take(litCount).toSet()

    _uiState.update { s ->
      s.copy(
        currentLevel = level,
        levelGoalPops = litCount,
        showStageTimeoutDialog = false,
        bubbles = s.bubbles.take(visibleCount).mapIndexed { index, b ->
          val isLit = index in shuffledIndices
          b.copy(
            isPopped = false,
            isLit = isLit,
            ledColor = if (index % 2 == 0) greenLed else redLed
          )
        }
      )
    }
  }

  private fun startStageForLevel(level: Int) {
    stageTimerJob?.cancel()
    setupStageBubblesOnly(level)
    startStageTimer(level)
  }

  private fun startStageTimer(level: Int) {
    stageTimerJob?.cancel()
    if (_uiState.value.gameMode != GameMode.LEVEL_CHALLENGE) return

    val durationMs = calculateStageDurationMs(level, _uiState.value.difficulty)
    stageDurationMs = durationMs
    stageRemainingMs = durationMs
    _uiState.update { it.copy(gaugeFraction = 1.0f) }
    runStageTimerLoop()
  }

  private fun resumeStageTimer() {
    stageTimerJob?.cancel()
    if (_uiState.value.gameMode != GameMode.LEVEL_CHALLENGE) return
    val fraction = (stageRemainingMs.toFloat() / stageDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    _uiState.update { it.copy(gaugeFraction = fraction) }
    runStageTimerLoop()
  }

  private fun runStageTimerLoop() {
    stageTimerJob = viewModelScope.launch {
      val intervalMs = 100L
      while (stageRemainingMs > 0L) {
        delay(intervalMs)
        val state = _uiState.value
        // 모드가 바뀌었거나 게임이 종료되었으면 즉시 루프 탈출
        if (state.gameMode != GameMode.LEVEL_CHALLENGE) break

        if (!state.isPaused && !state.isGameOver && !state.showPauseDialog && !state.showStageTimeoutDialog && !state.showDifficultyDialog) {
          stageRemainingMs = (stageRemainingMs - intervalMs).coerceAtLeast(0L)
          val fraction = (stageRemainingMs.toFloat() / stageDurationMs).coerceIn(0f, 1f)
          _uiState.update { it.copy(gaugeFraction = fraction) }

          // 마지막 2초 이내일 때 비프음 (0.5초 간격)
          if (stageRemainingMs in 1L..2000L && (stageRemainingMs % 500L < intervalMs)) {
            soundManager.playTimerTick()
          }
        }
      }

      // 시간 종료! (GAME OVER)
      val curState = _uiState.value
      if (curState.gameMode == GameMode.LEVEL_CHALLENGE && !curState.isPaused && !curState.isGameOver && !curState.showStageTimeoutDialog) {
        soundManager.playFailure()
        hapticManager.playFailureHaptic()
        _uiState.update {
          it.copy(
            gaugeFraction = 0f,
            showStageTimeoutDialog = true
          )
        }
      }
    }
  }

  private fun handleTimeAttackBubbleTap(
    bubbleId: Int,
    targetBubble: BubbleItem,
    points: Int,
    combo: Int,
    multiplier: Float,
    lifetime: Long
  ) {
    val greenLed = Color(0xFF00E676)
    val redLed = Color(0xFFFF2A6D)

    _uiState.update { s ->
      val unlitIndices = s.bubbles.filter { it.id != bubbleId && !it.isLit }.map { it.id }
      val nextLitId = unlitIndices.randomOrNull()

      val updatedBubbles = s.bubbles.map { b ->
        when (b.id) {
          bubbleId -> b.copy(isPopped = false, isLit = false)
          nextLitId -> b.copy(isLit = true, ledColor = if (Random.nextBoolean()) greenLed else redLed)
          else -> b
        }
      }

      // 2중 안전장치: 혹시 불빛이 3개 미만으로 떨어졌다면 즉시 3개로 자동 보충! (불빛 1개 버그 원천 봉쇄)
      val currentLits = updatedBubbles.count { it.isLit }
      val finalBubbles = if (currentLits < 3) {
        val remainingToLit = updatedBubbles.filter { !it.isLit }.shuffled().take(3 - currentLits).map { it.id }.toSet()
        updatedBubbles.map { b ->
          if (b.id in remainingToLit) b.copy(isLit = true, ledColor = if (b.id % 2 == 0) greenLed else redLed) else b
        }
      } else {
        updatedBubbles
      }

      s.copy(
        score = s.score + points + if (targetBubble.isLit) 35 else 5,
        sessionTouches = s.sessionTouches + 1,
        comboCount = combo,
        comboMultiplier = multiplier,
        lifetimePops = lifetime,
        bubbles = finalBubbles
      )
    }
  }

  private fun handleFreePlayBubbleTap(
    bubbleId: Int,
    points: Int,
    combo: Int,
    multiplier: Float,
    lifetime: Long
  ) {
    val oldScore = _uiState.value.score
    val newScore = oldScore + points
    // 4000점 단위 (버블을 100~150회 이상 한참 동안 실컷 누른 뒤 보람 있게 출력!)
    val oldMilestone = oldScore / 4000
    val newMilestone = newScore / 4000

    _uiState.update { s ->
      val updatedBubbles = s.bubbles.map { b ->
        if (b.id == bubbleId) {
          if (s.isToggleMode) b.copy(isPopped = !b.isPopped) else b.copy(isPopped = true)
        } else b
      }

      val allPopped = s.isToggleMode && updatedBubbles.isNotEmpty() && updatedBubbles.all { it.isPopped }

      s.copy(
        score = newScore,
        sessionTouches = s.sessionTouches + 1,
        comboCount = combo,
        comboMultiplier = multiplier,
        lifetimePops = lifetime,
        bubbles = if (allPopped) {
          soundManager.playSuccess()
          updatedBubbles.map { it.copy(isPopped = false) }
        } else updatedBubbles
      )
    }

    // 4000점 단위 돌파 시에만 토끼 얼굴 영역에 2초 축하 배너 표시 (느긋하고 평온한 힐링 보장)
    if (newMilestone > oldMilestone && newMilestone > 0) {
      soundManager.playLevelUp()
      hapticManager.playCelebrationHaptic()
      val milestonePoints = newMilestone * 4000
      val celebrationTitle = when (milestonePoints) {
        4000 -> "★ 4000점 달성! 자유 힐링 마스터! ★"
        8000 -> "★ 8000점 돌파! 평온한 힐링 레전드! ★"
        else -> "★ ${milestonePoints}점 돌파! 끝없는 자유 힐링! ★"
      }
      _uiState.update {
        it.copy(
          showLevelUpBanner = true,
          levelUpBannerMessage = celebrationTitle
        )
      }
      viewModelScope.launch {
        delay(2000)
        _uiState.update { it.copy(showLevelUpBanner = false) }
      }
    }
  }

  /**
   * 무한 퐁퐁 모드 (선풍기 버튼식 반응형 힐링 팝):
   * - 4~5개 정도 누른 상태가 될 때까지는 누른 버블들이 쏙 들어간 채 안정적으로 유지! (타격감 & 누른 의미 보장)
   * - 임계값 이상 눌려있을 때 다른 곳을 누르면, 방금 누른 자리는 쏙 들어가고 이전에 눌려있던 다른 곳에서 1~3개씩 퐁!
   * - 초보자: 4개 이상 눌린 상태에서 다른 버블 2개씩 퐁퐁!
   * - 중급자: 4개 이상 눌린 상태에서 450ms 딜레이 후 다른 버블 2~3개씩
   * - 고수: 5~6개 이상(반절 이상) 눌린 상태에서 뒤늦게 600ms 뒤 다른 버블 3~4개씩 퐁퐁퐁퐁!
   */
  private fun handleInfinitePopBubbleTap(
    bubbleId: Int,
    points: Int,
    combo: Int,
    multiplier: Float,
    lifetime: Long
  ) {
    val oldScore = _uiState.value.score
    val newScore = oldScore + points
    // 4000점 단위 (버블을 100~150회 이상 한참 동안 실컷 누른 뒤 보람 있게 출력!)
    val oldMilestone = oldScore / 4000
    val newMilestone = newScore / 4000
    lastInfiniteTapTimestamp = SystemClock.elapsedRealtime()

    // 1. 방금 누른 버블은 무조건 쏙 들어간 상태(isPopped = true)로 만듦
    _uiState.update { s ->
      val updated = s.bubbles.map { b ->
        if (b.id == bubbleId) b.copy(isPopped = true) else b
      }
      s.copy(
        score = newScore,
        sessionTouches = s.sessionTouches + 1,
        comboCount = combo,
        comboMultiplier = multiplier,
        lifetimePops = lifetime,
        bubbles = updated
      )
    }

    infiniteRecentlyPoppedIds.remove(bubbleId)
    infiniteRecentlyPoppedIds.add(bubbleId)

    // 4000점 단위 돌파 시에만 토끼 얼굴 영역에 2초 축하 배너 표시 (느긋하고 평온한 힐링 보장)
    if (newMilestone > oldMilestone && newMilestone > 0) {
      soundManager.playLevelUp()
      hapticManager.playCelebrationHaptic()
      val milestonePoints = newMilestone * 4000
      val celebrationTitle = when (milestonePoints) {
        4000 -> "★ 4000점 달성! 힐링 퐁퐁 마스터! ★"
        8000 -> "★ 8000점 돌파! 퐁퐁 레전드! ★"
        else -> "★ ${milestonePoints}점 돌파! 끝없는 힐링! ★"
      }
      _uiState.update {
        it.copy(
          showLevelUpBanner = true,
          levelUpBannerMessage = celebrationTitle
        )
      }
      viewModelScope.launch {
        delay(2000)
        _uiState.update { it.copy(showLevelUpBanner = false) }
      }
    }

    // 2. 선풍기 버튼식 메커니즘:
    // 4~5개 정도 누른 상태가 될 때까지는 누른 버블들이 쏙 들어간 채로 그대로 유지!
    val currentPopped = _uiState.value.bubbles.filter { it.isPopped }
    val totalPoppedCount = currentPopped.size
    val diff = _uiState.value.difficulty

    // 난이도별 최소 유지 버블 개수 (이 개수 이상 눌려있어야 다른 버블이 올라오기 시작)
    val triggerThreshold = when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> 4   // 초보자: 4개 이상 눌렸을 때
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> 4 // 중급자: 4개 이상 눌렸을 때
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> 5       // 고수: 5개 이상(반절 이상) 눌렸을 때
    }

    // 아직 충분히 누르지 않았다면 누른 상태를 그대로 유지하고 바로 복구하지 않음!
    if (totalPoppedCount < triggerThreshold) {
      return
    }

    // 방금 누른 버블(bubbleId)은 절대로 즉시 올라오지 않음!
    // 이전에 눌렸던 다른 버블들 중에서만 복원 후보 선정
    val eligibleCandidates = currentPopped.filter { it.id != bubbleId }
    if (eligibleCandidates.isEmpty()) return

    // 난이도별 복원 개수 및 딜레이
    val (delayMs, restoreCount) = when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> {
        // 초보자: 4개 이상 눌린 상태에서 다른 버블 2개씩 올라옴!
        Pair(380L, 2)
      }
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> {
        // 중급자: 450ms 딜레이 후 2~3개 올라옴
        val count = if (totalPoppedCount >= 6) 3 else 2
        Pair(450L, count)
      }
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> {
        // 고수: 600ms 뒤늦게 묵직하게 3~4개 올라옴!
        val count = if (totalPoppedCount >= 7) 4 else 3
        Pair(600L, count)
      }
    }

    viewModelScope.launch {
      delay(delayMs)
      if (_uiState.value.gameMode != GameMode.INFINITE_POP) return@launch

      val state = _uiState.value
      // 방금 누른 bubbleId 제외한 다른 눌린 버블들
      val otherPopped = state.bubbles.filter { it.id != bubbleId && it.isPopped }
      if (otherPopped.isNotEmpty()) {
        val toRestore = otherPopped.shuffled().take(restoreCount).map { it.id }.toSet()
        _uiState.update { s ->
          s.copy(
            bubbles = s.bubbles.map { b ->
              if (b.id in toRestore) b.copy(isPopped = false) else b
            }
          )
        }
        toRestore.forEach { infiniteRecentlyPoppedIds.remove(it) }
        soundManager.playSnapBack()
      }
    }
  }

  /**
   * 유휴 상태 안전 타이머:
   * 사용자가 누르지 않고 4초 이상 가만히 있을 때만, 버블이 5개 이상 눌려있으면 1~2개 살포시 올라옴
   */
  private fun startInfinitePopLoop() {
    infinitePopJob?.cancel()
    if (_uiState.value.gameMode != GameMode.INFINITE_POP) return

    _uiState.update { it.copy(gaugeFraction = 1.0f) }

    infinitePopJob = viewModelScope.launch {
      while (true) {
        delay(4000L) // 4초 동안 아무 터치도 없을 때만
        val state = _uiState.value
        if (state.gameMode != GameMode.INFINITE_POP) break

        val now = SystemClock.elapsedRealtime()
        if (now - lastInfiniteTapTimestamp >= 4000L) {
          if (!state.isPaused && !state.isGameOver && !state.showPauseDialog && !state.showSettingsDialog && !state.showDifficultyDialog) {
            val poppedBubbles = state.bubbles.filter { it.isPopped }
            if (poppedBubbles.size >= 5) {
              val restoreCount = if (state.difficulty == com.krdondon.quickpush.model.GameDifficulty.MASTER) 3 else 2
              val toRestore = poppedBubbles.shuffled().take(restoreCount).map { it.id }.toSet()
              _uiState.update { s ->
                s.copy(
                  bubbles = s.bubbles.map { b ->
                    if (b.id in toRestore) b.copy(isPopped = false) else b
                  }
                )
              }
              toRestore.forEach { infiniteRecentlyPoppedIds.remove(it) }
              soundManager.playSnapBack()
            }
          }
        }
      }
    }
  }

  private fun handlePatternBubbleTap(
    bubbleId: Int,
    points: Int,
    combo: Int,
    multiplier: Float,
    lifetime: Long
  ) {
    val state = _uiState.value
    if (state.isPatternShowing || patternSequence.isEmpty()) return

    val expectedBubbleId = patternSequence.getOrNull(userPatternStep)
    if (expectedBubbleId == bubbleId) {
      userPatternStep++
      _uiState.update {
        it.copy(
          score = it.score + points + 20,
          sessionTouches = it.sessionTouches + 1,
          comboCount = combo,
          comboMultiplier = multiplier,
          lifetimePops = lifetime
        )
      }

      if (userPatternStep >= patternSequence.size) {
        stageTimerJob?.cancel()
        val completedRound = state.patternRound
        val isMilestone = (completedRound > 0 && completedRound % 5 == 0) // 5단계 돌파 시 배너 + 보너스 점수!
        val bonus = if (isMilestone) 150 else 50
        val nextRound = completedRound + 1

        if (isMilestone) {
          soundManager.playLevelUp()
          hapticManager.playCelebrationHaptic()
          _uiState.update {
            it.copy(
              score = it.score + bonus,
              patternRound = nextRound,
              showLevelUpBanner = true,
              levelUpBannerMessage = "★ 기억력 ${completedRound}단계 돌파 완료! ★"
            )
          }
          viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(showLevelUpBanner = false) }
          }
        } else {
          soundManager.playSuccess()
          hapticManager.playCelebrationHaptic()
          _uiState.update {
            it.copy(
              score = it.score + bonus,
              patternRound = nextRound
            )
          }
        }

        viewModelScope.launch {
          delay(800)
          startPatternRound(nextRound)
        }
      }
    } else {
      stageTimerJob?.cancel()
      soundManager.playFailure()
      hapticManager.playFailureHaptic()
      _uiState.update { it.copy(showPatternFailDialog = true) }
    }
  }

  private fun calculatePatternLength(round: Int, diff: com.krdondon.quickpush.model.GameDifficulty): Int {
    return when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> {
        // 초보자: 1~3단계 2개, 4~7단계 3개, 8~11단계 4개, 12단계 이상 5개 (유치원생도 차분히 힐링 가능!)
        when {
          round <= 3 -> 2
          round <= 7 -> 3
          round <= 11 -> 4
          else -> 5
        }
      }
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> {
        // 중급자: 1~2단계 3개, 3~5단계 4개, 6~8단계 5개, 9단계 이상 6개 (중학생 수준)
        when {
          round <= 2 -> 3
          round <= 5 -> 4
          round <= 8 -> 5
          else -> 6
        }
      }
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> {
        // 고수: 1~2단계 4개, 3~5단계 5개, 6~8단계 6개, 9단계 이상 7~9개 (대학생 수준)
        when {
          round <= 2 -> 4
          round <= 5 -> 5
          round <= 8 -> 6
          else -> (6 + ((round - 8) / 3)).coerceIn(6, 9)
        }
      }
    }
  }

  private fun startPatternRound(round: Int) {
    patternJob?.cancel()
    stageTimerJob?.cancel()
    patternJob = viewModelScope.launch {
      val bubbles = _uiState.value.bubbles
      if (bubbles.isEmpty()) return@launch

      val diff = _uiState.value.difficulty
      val sequenceLength = calculatePatternLength(round, diff)
      patternSequence.clear()
      userPatternStep = 0

      for (i in 0 until sequenceLength) {
        val randomBubble = bubbles.random().id
        patternSequence.add(randomBubble)
      }

      _uiState.update { it.copy(isPatternShowing = true, gaugeFraction = 1.0f) }

      // 난이도별 불빛 보여주는 시간 (초보자는 충분히 외울 수 있게 천천히, 고수는 빠르고 날렵하게)
      val (onDelayMs, offDelayMs) = when (diff) {
        com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> Pair(750L, 300L)     // 초보자: 750ms 아주 넉넉하고 편안함
        com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> Pair(480L, 200L) // 중급자: 480ms 보통
        com.krdondon.quickpush.model.GameDifficulty.MASTER -> Pair(330L, 120L)       // 고수: 330ms 초스피디
      }

      // 효과음이 SoundPool에 완전히 준비될 때까지 안전하게 대기 (최대 600ms)
      soundManager.awaitPopSoundsReady(600L)
      delay(400)

      for (id in patternSequence) {
        _uiState.update { s ->
          s.copy(
            highlightedBubbleId = id,
            bubbles = s.bubbles.map { b -> b.copy(isHighlighted = b.id == id, isLit = b.id == id) }
          )
        }
        // 시연 효과음은 최우선순위(priority=3)로 재생하여 보이스 드랍 방지
        soundManager.playBubblePop(priority = 3)
        delay(onDelayMs)
        _uiState.update { s ->
          s.copy(
            highlightedBubbleId = null,
            bubbles = s.bubbles.map { b -> b.copy(isHighlighted = false, isLit = false) }
          )
        }
        delay(offDelayMs)
      }

      _uiState.update { it.copy(isPatternShowing = false) }

      // 자동 시연 완료 후 신호음 + "내 차례! 시작!" 상단 팝업 배너 표시
      soundManager.playSuccess()
      hapticManager.playBubblePopHaptic()
      _uiState.update {
        it.copy(
          showLevelUpBanner = true,
          levelUpBannerMessage = "★ START! 기억한 순서대로 누르세요! ★"
        )
      }
      viewModelScope.launch {
        delay(1500)
        _uiState.update { it.copy(showLevelUpBanner = false) }
      }

      // 사용자가 신호음을 듣고 팝업을 인지할 수 있도록 0.6초간 여유 숨고르기 후 입력 타이머 시작
      delay(600)
      startPatternInputTimer(sequenceLength, diff)
    }
  }

  private fun startPatternInputTimer(sequenceLength: Int, diff: com.krdondon.quickpush.model.GameDifficulty) {
    stageTimerJob?.cancel()
    if (_uiState.value.gameMode != GameMode.PATTERN_FOLLOW) return

    val secPerStep = when (diff) {
      com.krdondon.quickpush.model.GameDifficulty.BEGINNER -> 4.0f     // 초보자: 1개당 4초 (충분히 느긋함)
      com.krdondon.quickpush.model.GameDifficulty.INTERMEDIATE -> 2.5f // 중급자: 1개당 2.5초
      com.krdondon.quickpush.model.GameDifficulty.MASTER -> 1.8f       // 고수: 1개당 1.8초
    }
    val totalTimeMs = ((sequenceLength * secPerStep).coerceAtLeast(6.0f) * 1000).toLong()
    stageDurationMs = totalTimeMs
    stageRemainingMs = totalTimeMs
    _uiState.update { it.copy(gaugeFraction = 1.0f) }

    stageTimerJob = viewModelScope.launch {
      val intervalMs = 100L
      while (stageRemainingMs > 0L) {
        delay(intervalMs)
        val state = _uiState.value
        if (state.gameMode != GameMode.PATTERN_FOLLOW || state.isPatternShowing) break

        if (!state.isPaused && !state.isGameOver && !state.showPauseDialog && !state.showPatternFailDialog && !state.showDifficultyDialog) {
          stageRemainingMs = (stageRemainingMs - intervalMs).coerceAtLeast(0L)
          val fraction = (stageRemainingMs.toFloat() / stageDurationMs).coerceIn(0f, 1f)
          _uiState.update { it.copy(gaugeFraction = fraction) }

          // 마지막 2초 이내일 때 게이지 종료 임박 비프음 재생 (돌파 모드와 동일)
          if (stageRemainingMs in 1L..2000L && (stageRemainingMs % 500L < intervalMs)) {
            soundManager.playTimerTick()
          }
        }
      }

      val curState = _uiState.value
      if (curState.gameMode == GameMode.PATTERN_FOLLOW && !curState.isPatternShowing && !curState.isPaused && !curState.isGameOver && !curState.showPatternFailDialog) {
        soundManager.playFailure()
        hapticManager.playFailureHaptic()
        _uiState.update {
          it.copy(
            gaugeFraction = 0f,
            showPatternFailDialog = true
          )
        }
      }
    }
  }

  fun retryPattern() {
    _uiState.update { it.copy(showPatternFailDialog = false) }
    startPatternRound(_uiState.value.patternRound)
  }

  fun setGameMode(mode: GameMode) {
    timerJob?.cancel()
    stageTimerJob?.cancel()
    patternJob?.cancel()
    infinitePopJob?.cancel()
    patternSequence.clear()
    userPatternStep = 0

    _uiState.update {
      it.copy(
        gameMode = mode,
        score = 0,
        sessionTouches = 0,
        comboCount = 0,
        comboMultiplier = 1.0f,
        timeRemainingSec = it.timeAttackDuration,
        freePlayElapsedSec = 0L,
        gaugeFraction = 1.0f,
        isPaused = false,
        isGameOver = false,
        showLevelUpDialog = false,
        showLevelUpBanner = false,
        showTimeAttackResultDialog = false,
        showPatternFailDialog = false,
        showStageTimeoutDialog = false,
        isPatternShowing = false,
        highlightedBubbleId = null,
        bubbles = it.bubbles.map { b -> b.copy(isPopped = false, isHighlighted = false, isLit = false) }
      )
    }

    startActiveModeTimers()

    when (mode) {
      GameMode.LEVEL_CHALLENGE -> startStageForLevel(1)
      GameMode.PATTERN_FOLLOW -> startPatternRound(1)
      GameMode.TIME_ATTACK -> {
        val greenLed = Color(0xFF00E676)
        _uiState.update { s ->
          val initialLit = (0 until s.bubbles.size).shuffled().take(3).toSet()
          s.copy(bubbles = s.bubbles.mapIndexed { idx, b -> b.copy(isLit = idx in initialLit, ledColor = greenLed) })
        }
      }
      GameMode.FREE_PLAY -> {}
      GameMode.INFINITE_POP -> startInfinitePopLoop()
    }
  }

  fun setConsoleColor(color: ConsoleColor) {
    prefs.consoleColor = color
    _uiState.update { it.copy(consoleColor = color) }
  }

  fun setTimeAttackDuration(seconds: Int) {
    _uiState.update {
      it.copy(
        timeAttackDuration = seconds,
        timeRemainingSec = seconds,
        gaugeFraction = 1.0f
      )
    }
    resetGame()
  }

  private fun startActiveModeTimers() {
    timerJob?.cancel()
    timerJob = viewModelScope.launch {
      while (true) {
        delay(1000L) // 스피드 모드는 설정한 30초/60초/120초에 정직하게 1초씩 카운트다운
        val state = _uiState.value
        if (!state.isPaused && !state.isGameOver && !state.showPauseDialog && !state.showTimeAttackResultDialog && !state.showStageTimeoutDialog && !state.showDifficultyDialog) {
          when (state.gameMode) {
            GameMode.TIME_ATTACK -> {
              val remaining = state.timeRemainingSec - 1
              val fraction = (remaining.toFloat() / state.timeAttackDuration).coerceIn(0f, 1f)
              if (remaining <= 5 && remaining > 0) {
                soundManager.playTimerTick()
              }
              if (remaining <= 0) {
                soundManager.playTimerFinish()
                hapticManager.playCelebrationHaptic()
                prefs.setTimeAttackHighScore(state.timeAttackDuration, state.score)
                val newHigh = prefs.getTimeAttackHighScore(state.timeAttackDuration)
                _uiState.update {
                  it.copy(
                    timeRemainingSec = 0,
                    gaugeFraction = 0f,
                    isGameOver = true,
                    showPauseDialog = false,
                    showTimeAttackResultDialog = true,
                    bestTimeAttack30 = if (it.timeAttackDuration == 30) newHigh else it.bestTimeAttack30,
                    bestTimeAttack60 = if (it.timeAttackDuration == 60) newHigh else it.bestTimeAttack60,
                    bestTimeAttack120 = if (it.timeAttackDuration == 120) newHigh else it.bestTimeAttack120
                  )
                }
              } else {
                _uiState.update { it.copy(timeRemainingSec = remaining, gaugeFraction = fraction) }
              }
            }
            GameMode.FREE_PLAY -> {
              _uiState.update { it.copy(freePlayElapsedSec = it.freePlayElapsedSec + 1, gaugeFraction = 1.0f) }
            }
            else -> {}
          }
        }
      }
    }
  }

  fun pauseGame() {
    val state = _uiState.value
    // 이미 결과 다이얼로그나 설정, 레벨업 등의 다이얼로그가 떠 있거나 게임 오버인 경우 중복으로 pauseDialog를 띄우지 않음
    if (state.isGameOver || state.showTimeAttackResultDialog || state.showPatternFailDialog || state.showLevelUpDialog || state.showSettingsDialog || state.showStageTimeoutDialog || state.showDifficultyDialog) {
      _uiState.update { it.copy(isPaused = true) }
      return
    }
    _uiState.update { it.copy(isPaused = true, showPauseDialog = true) }
  }

  fun resumeGame() {
    _uiState.update { it.copy(isPaused = false, showPauseDialog = false) }
    prefs.clearSuspendedGame()
    bgmPlayer.resume()

    val state = _uiState.value
    // 2중 안전장치: 스피드 모드 복귀 시 불빛 3개 상태 재확인
    if (state.gameMode == GameMode.TIME_ATTACK) {
      ensureTimeAttackBubblesHealthy()
    }

    when (state.gameMode) {
      GameMode.LEVEL_CHALLENGE -> resumeStageTimer()
      GameMode.TIME_ATTACK, GameMode.FREE_PLAY -> startActiveModeTimers()
      GameMode.INFINITE_POP -> startInfinitePopLoop()
      GameMode.PATTERN_FOLLOW -> startPatternRound(state.patternRound)
    }
  }

  fun resetGame() {
    prefs.clearSuspendedGame()
    timerJob?.cancel()
    stageTimerJob?.cancel()
    patternJob?.cancel()
    infinitePopJob?.cancel()
    patternSequence.clear()
    userPatternStep = 0

    soundManager.playSnapBack()
    hapticManager.playBubblePopHaptic()

    _uiState.update {
      it.copy(
        score = 0,
        sessionTouches = 0,
        currentLevel = 1,
        comboCount = 0,
        comboMultiplier = 1.0f,
        timeRemainingSec = it.timeAttackDuration,
        freePlayElapsedSec = 0L,
        gaugeFraction = 1.0f,
        isPaused = false,
        isGameOver = false,
        showLevelUpDialog = false,
        showTimeAttackResultDialog = false,
        showPauseDialog = false,
        showPatternFailDialog = false,
        isPatternShowing = false,
        highlightedBubbleId = null,
        patternRound = 1,
        bubbles = it.bubbles.map { b -> b.copy(isPopped = false, isHighlighted = false, isLit = false) }
      )
    }

    startActiveModeTimers()

    when (_uiState.value.gameMode) {
      GameMode.LEVEL_CHALLENGE -> startStageForLevel(1)
      GameMode.PATTERN_FOLLOW -> startPatternRound(1)
      GameMode.TIME_ATTACK -> {
        val greenLed = Color(0xFF00E676)
        _uiState.update { s ->
          val initialLit = (0 until s.bubbles.size).shuffled().take(3).toSet()
          s.copy(bubbles = s.bubbles.mapIndexed { idx, b -> b.copy(isLit = idx in initialLit, ledColor = greenLed) })
        }
      }
      GameMode.FREE_PLAY -> {}
      GameMode.INFINITE_POP -> startInfinitePopLoop()
    }
  }

  private fun emitFloatingScore(points: Int, combo: Int, x: Float, y: Float) {
    val id = System.currentTimeMillis() + Random.nextLong(1000)
    val text = if (combo >= 2) "+$points (Combo x$combo!)" else "+$points"
    val color = Color(0xFFFF2A6D)

    val item = FloatingScore(id, text, x, y, color)
    _uiState.update { s -> s.copy(floatingScores = (s.floatingScores + item).takeLast(12)) }

    viewModelScope.launch {
      delay(700)
      _uiState.update { s -> s.copy(floatingScores = s.floatingScores.filterNot { it.id == id }) }
    }
  }

  private fun emitParticles(x: Float, y: Float) {
    val baseId = System.currentTimeMillis()
    val sparklePalette = listOf(
      Color(0xFFFFD700), // Gold Star
      Color(0xFFFFFFFF), // Pure White
      Color(0xFFFF2A6D), // Vivid Pink
      Color(0xFF00E676), // Mint Sparkle
      Color(0xFF00D2FF), // Sky Cyan
      Color(0xFFFFB703)  // Warm Gold
    )

    // Emit 10 glittering star particles radiating outwards around the tapped bubble
    val newParticles = (0 until 10).map { i ->
      val angle = (i * 36f + Random.nextFloat() * 20f) * (Math.PI / 180f).toFloat()
      val dist = 24f + Random.nextFloat() * 48f
      Particle(
        id = baseId + i,
        x = x + kotlin.math.cos(angle) * dist,
        y = y + kotlin.math.sin(angle) * dist,
        vx = kotlin.math.cos(angle) * 35f,
        vy = kotlin.math.sin(angle) * 35f,
        size = 6.5f + Random.nextFloat() * 5.5f,
        color = sparklePalette[i % sparklePalette.size]
      )
    }

    _uiState.update { s -> s.copy(particles = (s.particles + newParticles).takeLast(25)) }
    viewModelScope.launch {
      delay(550)
      _uiState.update { s -> s.copy(particles = s.particles.filterNot { p -> newParticles.any { it.id == p.id } }) }
    }
  }

  fun toggleMusic(enabled: Boolean? = null) {
    val next = enabled ?: !_uiState.value.isMusicEnabled
    prefs.isMusicEnabled = next
    bgmPlayer.isMusicEnabled = next
    _uiState.update { it.copy(isMusicEnabled = next) }
  }

  fun toggleSound(enabled: Boolean? = null) {
    val next = enabled ?: !_uiState.value.isSoundEnabled
    prefs.isSoundEnabled = next
    soundManager.isSoundEnabled = next
    _uiState.update { it.copy(isSoundEnabled = next) }
  }

  fun setSoundStyle(style: SoundStyle) {
    prefs.soundStyle = style
    soundManager.soundStyle = style
    _uiState.update { it.copy(soundStyle = style) }
  }

  fun toggleHaptic(enabled: Boolean) {
    prefs.isHapticEnabled = enabled
    hapticManager.isHapticEnabled = enabled
    _uiState.update { it.copy(isHapticEnabled = enabled) }
  }

  fun toggleGlow(enabled: Boolean) {
    prefs.isGlowEnabled = enabled
    _uiState.update { it.copy(isGlowEnabled = enabled) }
  }

  fun toggleWave(enabled: Boolean) {
    prefs.isWaveEnabled = enabled
    _uiState.update { it.copy(isWaveEnabled = enabled) }
  }

  fun toggleSparkle(enabled: Boolean) {
    prefs.isBorderSparkleEnabled = enabled
    _uiState.update { it.copy(isBorderSparkleEnabled = enabled) }
  }

  fun setAnimationIntensity(intensity: AnimationIntensity) {
    prefs.animationIntensity = intensity
    _uiState.update { it.copy(animationIntensity = intensity) }
  }

  fun setBubbleTheme(theme: BubbleTheme) {
    prefs.bubbleTheme = theme
    _uiState.update { it.copy(theme = theme) }
    rebuildBubbles(_uiState.value.gridDimension.rows, _uiState.value.gridDimension.cols, preserveState = true)
  }

  fun toggleSiliconeMode(enabled: Boolean) {
    prefs.isToggleMode = enabled
    _uiState.update { it.copy(isToggleMode = enabled) }
  }

  fun dismissLevelUpDialog() {
    _uiState.update { it.copy(showLevelUpDialog = false) }
  }

  fun dismissTimeAttackDialog() {
    _uiState.update { it.copy(showTimeAttackResultDialog = false) }
    setGameMode(GameMode.FREE_PLAY)
  }

  fun retryTimeAttack() {
    _uiState.update { it.copy(showTimeAttackResultDialog = false) }
    resetGame()
  }

  fun showSettings(show: Boolean) {
    _uiState.update { it.copy(showSettingsDialog = show) }
  }

  fun showConfirmReset(show: Boolean) {
    _uiState.update { it.copy(showConfirmResetDialog = show) }
  }

  fun resetAllDataConfirmed() {
    prefs.resetAllGameData()
    _uiState.update {
      it.copy(
        score = 0,
        sessionTouches = 0,
        currentLevel = 1,
        bestTimeAttack30 = 0,
        bestTimeAttack60 = 0,
        bestTimeAttack120 = 0,
        maxLevelReached = 1,
        lifetimePops = 0L,
        showConfirmResetDialog = false
      )
    }
    startStageForLevel(1)
  }

  fun onTrimMemory(level: Int) {
    soundManager.onTrimMemory(level)
    clearVisualEffectsMemory()
  }

  fun clearVisualEffectsMemory() {
    _uiState.update { s ->
      s.copy(
        particles = emptyList(),
        floatingScores = emptyList()
      )
    }
  }

  fun onResume() {
    soundManager.onResume()
    val state = _uiState.value

    // ★ 2중 안전장치 (Self-Healing State Sanitation):
    // 만약 다이얼로그가 떠 있지 않은데(showPauseDialog=false) 내부 플래그(isPaused=true)만 잠겨있다면
    // 사용자가 터치할 수 없게 먹통이 되는 현상을 방지하기 위해 즉시 정상 잠금 해제!
    if (!state.showPauseDialog && state.isPaused && !state.isGameOver) {
      _uiState.update { it.copy(isPaused = false) }
    }

    val updatedState = _uiState.value
    // 일시정지 팝업이 떠 있는 상태에서는 BGM이나 타이머를 돌리지 않고 [계속하기]를 안전하게 대기
    if (!updatedState.isPaused && !updatedState.showPauseDialog) {
      bgmPlayer.resume()
      if (updatedState.gameMode == GameMode.TIME_ATTACK) {
        ensureTimeAttackBubblesHealthy()
      }
      when (updatedState.gameMode) {
        GameMode.LEVEL_CHALLENGE -> resumeStageTimer()
        GameMode.TIME_ATTACK, GameMode.FREE_PLAY -> startActiveModeTimers()
        GameMode.INFINITE_POP -> startInfinitePopLoop()
        GameMode.PATTERN_FOLLOW -> {}
      }
    }
  }

  fun onPause() {
    val state = _uiState.value
    // 앱 밖으로 나갈 때 (홈 이동, 다른 앱 전환, 화면 끄기 등)
    // 스피드 모드가 진행 중이면 즉시 일시정지하여 백그라운드 타이머 차감 방지
    if (!state.isPaused && !state.isGameOver && state.gameMode == GameMode.TIME_ATTACK) {
      pauseGame()
    }
  }

  fun onStop() {
    val state = _uiState.value

    // 1. 게임 진행 중 백그라운드로 나갈 때 일시정지 상태 및 진행 데이터 영구 저장 (장시간 프로세스 종료 대비)
    if (!state.isGameOver && !state.showTimeAttackResultDialog && !state.showStageTimeoutDialog && !state.showPatternFailDialog) {
      pauseGame()
      prefs.isGameSuspended = true
      prefs.suspendedGameMode = state.gameMode.name
      prefs.suspendedLevel = state.currentLevel
      prefs.suspendedScore = state.score
      prefs.suspendedRemainingMs = stageRemainingMs
    }

    // 2. 백그라운드 코루틴 100% 완전 정지 (CPU 점유 0% 보장 -> 배터리 소모 원천 차단!)
    stageTimerJob?.cancel()
    timerJob?.cancel()
    infinitePopJob?.cancel()
    patternJob?.cancel()

    bgmPlayer.pause()
    clearVisualEffectsMemory()
  }

  fun retryPreviousStage() {
    _uiState.update { it.copy(showStageTimeoutDialog = false) }
    val targetLevel = (_uiState.value.currentLevel - 1).coerceAtLeast(1)
    _uiState.update { it.copy(currentLevel = targetLevel) }
    startStageForLevel(targetLevel)
  }

  fun restartStageFromFirst() {
    _uiState.update { it.copy(showStageTimeoutDialog = false, currentLevel = 1, score = 0) }
    startStageForLevel(1)
  }

  fun dismissStageTimeoutToFreePlay() {
    _uiState.update { it.copy(showStageTimeoutDialog = false) }
    setGameMode(GameMode.FREE_PLAY)
  }

  fun showDifficultyDialog(show: Boolean) {
    _uiState.update { it.copy(showDifficultyDialog = show) }
  }

  fun setDifficulty(diff: com.krdondon.quickpush.model.GameDifficulty) {
    prefs.difficulty = diff
    _uiState.update {
      it.copy(
        difficulty = diff,
        showDifficultyDialog = false,
        score = 0
      )
    }
    when (_uiState.value.gameMode) {
      GameMode.LEVEL_CHALLENGE -> {
        startStageForLevel(1)
      }
      GameMode.TIME_ATTACK -> {
        // 스피드 모드에서도 난이도 변경 시 처음부터 즉시 재시작!
        resetGame()
      }
      GameMode.PATTERN_FOLLOW -> {
        startPatternRound(1)
      }
      GameMode.FREE_PLAY -> {
        resetGame()
      }
      GameMode.INFINITE_POP -> {
        resetGame()
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    soundManager.release()
    bgmPlayer.release()
    timerJob?.cancel()
    stageTimerJob?.cancel()
    patternJob?.cancel()
    infinitePopJob?.cancel()
  }
}

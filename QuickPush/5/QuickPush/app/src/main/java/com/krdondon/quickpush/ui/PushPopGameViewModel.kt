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
  val isSoundEnabled: Boolean = true,
  val isHapticEnabled: Boolean = true,
  val isGlowEnabled: Boolean = true,
  val isWaveEnabled: Boolean = true,
  val isBorderSparkleEnabled: Boolean = true,
  val animationIntensity: AnimationIntensity = AnimationIntensity.NORMAL,
  val isToggleMode: Boolean = true,
  val floatingScores: List<FloatingScore> = emptyList(),
  val particles: List<Particle> = emptyList(),
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
  private var patternJob: Job? = null
  private var lastTapTimestamp = 0L
  private var patternSequence: MutableList<Int> = mutableListOf()
  private var userPatternStep = 0

  init {
    loadSettingsFromPrefs()
    // Initial standard 3x3 grid (9 bubbles) - 화면 내 완전 가시성 보장
    rebuildBubbles(rows = 3, cols = 3, preserveState = false)
    startActiveModeTimers()
    startStageForLevel(1)
    bgmPlayer.playCurrentTrack()
  }

  private fun loadSettingsFromPrefs() {
    val soundEnabled = prefs.isSoundEnabled
    val soundSt = prefs.soundStyle
    val hapticEnabled = prefs.isHapticEnabled

    soundManager.isSoundEnabled = soundEnabled
    soundManager.soundStyle = soundSt
    hapticManager.isHapticEnabled = hapticEnabled

    _uiState.update {
      it.copy(
        consoleColor = prefs.consoleColor,
        isSoundEnabled = soundEnabled,
        soundStyle = soundSt,
        isHapticEnabled = hapticEnabled,
        isGlowEnabled = prefs.isGlowEnabled,
        isWaveEnabled = prefs.isWaveEnabled,
        isBorderSparkleEnabled = prefs.isBorderSparkleEnabled,
        animationIntensity = prefs.animationIntensity,
        theme = prefs.bubbleTheme,
        isToggleMode = prefs.isToggleMode,
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
    val nextLevel = _uiState.value.currentLevel + 1
    if (nextLevel > prefs.maxLevelReached) {
      prefs.maxLevelReached = nextLevel
    }

    // Immediate clear fanfare sound & haptic!
    soundManager.playLevelUp()
    hapticManager.playCelebrationHaptic()

    _uiState.update {
      it.copy(
        score = it.score + 100, // Stage clear bonus points!
        currentLevel = nextLevel,
        maxLevelReached = prefs.maxLevelReached,
        showLevelUpDialog = (nextLevel % 5 == 0) // 5레벨 단위 마일스톤일 때만 팝업 표시하여 연속 몰입 플레이 보장
      )
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

  private fun startStageForLevel(level: Int) {
    val rows = _uiState.value.gridDimension.rows
    val cols = _uiState.value.gridDimension.cols
    val visibleCount = (rows * cols).coerceAtLeast(4)

    // 화면 그리드 개수와 실제 버블 리스트 크기를 엄격하게 일치시킴
    if (_uiState.value.bubbles.size != visibleCount) {
      rebuildBubbles(rows, cols, preserveState = false)
    }

    // Scale lit bubbles count with level, up to 70% of visible bubbles
    val maxLit = (visibleCount * 0.70f).toInt().coerceAtLeast(3)
    val litCount = (2 + (level % 6)).coerceIn(3, maxLit)

    val greenLed = Color(0xFF00E676)
    val redLed = Color(0xFFFF2A6D)

    // ★ 화면에 실제로 표시되는 0 until visibleCount 인덱스 안에서만 불빛을 추출하여 화면 밖 유령 버블 방지
    val shuffledIndices = (0 until visibleCount).shuffled().take(litCount).toSet()

    _uiState.update { s ->
      s.copy(
        levelGoalPops = litCount,
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

      s.copy(
        score = s.score + points + if (targetBubble.isLit) 35 else 5,
        sessionTouches = s.sessionTouches + 1,
        comboCount = combo,
        comboMultiplier = multiplier,
        lifetimePops = lifetime,
        bubbles = updatedBubbles
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
    _uiState.update { s ->
      val updatedBubbles = s.bubbles.map { b ->
        if (b.id == bubbleId) {
          if (s.isToggleMode) b.copy(isPopped = !b.isPopped) else b.copy(isPopped = true)
        } else b
      }

      val allPopped = s.isToggleMode && updatedBubbles.isNotEmpty() && updatedBubbles.all { it.isPopped }

      s.copy(
        score = s.score + points,
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
        soundManager.playSuccess()
        hapticManager.playCelebrationHaptic()
        val nextRound = state.patternRound + 1
        _uiState.update { it.copy(patternRound = nextRound) }
        viewModelScope.launch {
          delay(700)
          startPatternRound(nextRound)
        }
      }
    } else {
      soundManager.playFailure()
      hapticManager.playFailureHaptic()
      _uiState.update { it.copy(showPatternFailDialog = true) }
    }
  }

  private fun startPatternRound(round: Int) {
    patternJob?.cancel()
    patternJob = viewModelScope.launch {
      val bubbles = _uiState.value.bubbles
      if (bubbles.isEmpty()) return@launch

      val sequenceLength = (2 + round).coerceAtMost(10)
      patternSequence.clear()
      userPatternStep = 0

      for (i in 0 until sequenceLength) {
        val randomBubble = bubbles.random().id
        patternSequence.add(randomBubble)
      }

      _uiState.update { it.copy(isPatternShowing = true) }

      delay(500)
      for (id in patternSequence) {
        _uiState.update { s ->
          s.copy(
            highlightedBubbleId = id,
            bubbles = s.bubbles.map { b -> b.copy(isHighlighted = b.id == id, isLit = b.id == id) }
          )
        }
        soundManager.playBubblePop()
        delay(450)
        _uiState.update { s ->
          s.copy(
            highlightedBubbleId = null,
            bubbles = s.bubbles.map { b -> b.copy(isHighlighted = false, isLit = false) }
          )
        }
        delay(180)
      }

      _uiState.update { it.copy(isPatternShowing = false) }
    }
  }

  fun retryPattern() {
    _uiState.update { it.copy(showPatternFailDialog = false) }
    startPatternRound(_uiState.value.patternRound)
  }

  fun setGameMode(mode: GameMode) {
    timerJob?.cancel()
    patternJob?.cancel()
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
        isPaused = false,
        isGameOver = false,
        showLevelUpDialog = false,
        showTimeAttackResultDialog = false,
        showPatternFailDialog = false,
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
        timeRemainingSec = seconds
      )
    }
    resetGame()
  }

  private fun startActiveModeTimers() {
    timerJob?.cancel()
    timerJob = viewModelScope.launch {
      while (true) {
        delay(1000)
        val state = _uiState.value
        if (!state.isPaused && !state.isGameOver) {
          when (state.gameMode) {
            GameMode.TIME_ATTACK -> {
              val remaining = state.timeRemainingSec - 1
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
                    isGameOver = true,
                    showTimeAttackResultDialog = true,
                    bestTimeAttack30 = if (it.timeAttackDuration == 30) newHigh else it.bestTimeAttack30,
                    bestTimeAttack60 = if (it.timeAttackDuration == 60) newHigh else it.bestTimeAttack60,
                    bestTimeAttack120 = if (it.timeAttackDuration == 120) newHigh else it.bestTimeAttack120
                  )
                }
              } else {
                _uiState.update { it.copy(timeRemainingSec = remaining) }
              }
            }
            GameMode.FREE_PLAY -> {
              _uiState.update { it.copy(freePlayElapsedSec = it.freePlayElapsedSec + 1) }
            }
            else -> {}
          }
        }
      }
    }
  }

  fun pauseGame() {
    _uiState.update { it.copy(isPaused = true, showPauseDialog = true) }
  }

  fun resumeGame() {
    _uiState.update { it.copy(isPaused = false, showPauseDialog = false) }
  }

  fun resetGame() {
    timerJob?.cancel()
    patternJob?.cancel()
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

  fun toggleSound(enabled: Boolean) {
    prefs.isSoundEnabled = enabled
    soundManager.isSoundEnabled = enabled
    bgmPlayer.isMusicEnabled = enabled
    _uiState.update { it.copy(isSoundEnabled = enabled) }
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
    bgmPlayer.pause()
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
    bgmPlayer.resume()
  }

  fun onPause() {
    bgmPlayer.pause()
  }

  override fun onCleared() {
    super.onCleared()
    soundManager.release()
    bgmPlayer.release()
    timerJob?.cancel()
    patternJob?.cancel()
  }
}

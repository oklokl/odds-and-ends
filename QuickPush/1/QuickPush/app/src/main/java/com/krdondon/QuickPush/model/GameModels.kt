package com.krdondon.quickpush.model

import androidx.compose.ui.graphics.Color

enum class GameMode(val title: String, val description: String) {
  LEVEL_CHALLENGE("돌파 모드", "불이 켜진 버블을 모두 누르고 다음 스테이지로 돌파!"),
  FREE_PLAY("자유 힐링", "시간 제한 없이 버블을 누르고 힐링 팝 사운드를 즐기세요"),
  PATTERN_FOLLOW("기억력 모드", "버블이 켜지는 순서를 기억하고 차례대로 눌러보세요"),
  TIME_ATTACK("스피드 모드", "제한 시간 동안 최대한 많은 불빛을 꺼보세요")
}

enum class SoundStyle(val title: String) {
  CLASSIC_POP("전자 장난감 팝"),
  CRYSTAL_POP("크리스탈 팝")
}

enum class AnimationIntensity(val title: String, val scaleFactor: Float) {
  SOFT("부드럽게", 0.7f),
  NORMAL("보통", 1.0f),
  DYNAMIC("역동적", 1.3f)
}

enum class ConsoleColor(
  val title: String,
  val bodyColor: Color,
  val accentColor: Color,
  val innerEarColor: Color,
  val plateColor: Color
) {
  PINK_BUNNY(
    "핑크 버니",
    Color(0xFFFF5E88),
    Color(0xFFFF2A6D),
    Color(0xFFFFB3C6),
    Color(0xFFFFF0F5)
  ),
  MINT_BUNNY(
    "민트 버니",
    Color(0xFF00BFA5),
    Color(0xFF00897B),
    Color(0xFF80CBC4),
    Color(0xFFE0F2F1)
  ),
  LAVENDER_BUNNY(
    "라벤더 버니",
    Color(0xFF8E24AA),
    Color(0xFF7B1FA2),
    Color(0xFFCE93D8),
    Color(0xFFF3E5F5)
  ),
  YELLOW_BUNNY(
    "버터 버니",
    Color(0xFFFFB300),
    Color(0xFFFF8F00),
    Color(0xFFFFE082),
    Color(0xFFFFF8E1)
  )
}

enum class BubbleTheme(val title: String, val colors: List<Color>, val defaultGlow: Color) {
  VIVID_RAINBOW(
    "비비드 레인보우 (강렬한 컬러)",
    listOf(
      Color(0xFFFF2A6D), // Vivid Rose Pink
      Color(0xFFFF7A00), // Vivid Orange
      Color(0xFFFFD600), // Vivid Yellow
      Color(0xFF00E676), // Vivid Emerald Green
      Color(0xFF00D2FF), // Vivid Cyan
      Color(0xFF9D4EDD)  // Vivid Purple
    ),
    Color(0xFF00E676)
  ),
  NEON_POP(
    "네온 팝 (화려한 네온)",
    listOf(
      Color(0xFFFF007F), // Neon Pink
      Color(0xFF39FF14), // Neon Lime
      Color(0xFF00F0FF), // Neon Cyan
      Color(0xFFB026FF), // Neon Purple
      Color(0xFFFF5E00)  // Neon Orange
    ),
    Color(0xFF39FF14)
  ),
  SWEET_CANDY(
    "스위트 캔디 (달콤한 과즙)",
    listOf(
      Color(0xFFFF3366),
      Color(0xFFFF9100),
      Color(0xFF8E24AA),
      Color(0xFF1E88E5),
      Color(0xFF43A047)
    ),
    Color(0xFFFF3366)
  ),
  SILICONE_CREAM(
    "실리콘 크림 (은은한 크림)",
    listOf(Color(0xFFFDF6EE), Color(0xFFF9EFE3), Color(0xFFF5E7D8)),
    Color(0xFF00E676)
  )
}

data class BubbleItem(
  val id: Int,
  val row: Int,
  val col: Int,
  val baseColor: Color = Color(0xFFFF2A6D),
  val glowColor: Color = Color(0xFF00E676),
  val isPopped: Boolean = false,
  val isHighlighted: Boolean = false,
  val isLit: Boolean = false,
  val ledColor: Color = Color(0xFF00E676),
  val hasCarrotIcon: Boolean = true
)

data class GridDimension(
  val rows: Int,
  val cols: Int,
  val bubbleSizeDp: Float,
  val spacingDp: Float
)

data class FloatingScore(
  val id: Long,
  val text: String,
  val x: Float,
  val y: Float,
  val color: Color
)

data class Particle(
  val id: Long,
  val x: Float,
  val y: Float,
  val vx: Float,
  val vy: Float,
  val size: Float,
  val color: Color,
  val alpha: Float = 1f
)

package com.krdondon.quickpush.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object GridCalculator {

  const val MIN_BUBBLE_SIZE_DP = 38f
  const val MAX_BUBBLE_SIZE_DP = 96f
  const val DEFAULT_SPACING_DP = 10f

  /**
   * Calculates the optimal responsive grid layout based on available width and height.
   *
   * @param availableWidthDp Available width in dp inside the game board container
   * @param availableHeightDp Available height in dp inside the game board container
   * @param isLandscape Whether screen is in landscape orientation
   * @param isTablet Whether device is a tablet form factor
   */
  fun calculateGridLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    isLandscape: Boolean = false,
    isTablet: Boolean = false
  ): GridDimension {
    val usableW = max(availableWidthDp - 24f, 160f)
    val usableH = max(availableHeightDp - 20f, 160f)

    var cols: Int
    var rows: Int
    var bubbleSize: Float
    var spacing: Float

    if (isTablet) {
      // 태블릿 대화면: 가로와 세로의 넓은 공간을 버블들로 빈틈없이 시원하게 가득 채움
      spacing = if (isLandscape) 8f else DEFAULT_SPACING_DP
      val targetBubbleSize = if (isLandscape) 64f else 72f

      val availableTrayH = max(availableHeightDp - 150f, 200f)
      rows = floor((availableTrayH + spacing) / (targetBubbleSize + spacing)).toInt().coerceIn(4, 6)

      val cellH = (availableTrayH - (rows - 1) * spacing) / rows
      bubbleSize = cellH.coerceIn(50f, 76f)

      // 가로 가용 너비 전체에 맞춰 열(cols)을 계산하여 좌우 빈 공간을 완전히 채움
      cols = floor((usableW - 40f + spacing) / (bubbleSize + spacing)).toInt().coerceIn(if (isLandscape) 8 else 5, 12)
    } else if (isLandscape) {
      // 스마트폰 가로 모드:
      // 세로 높이에 맞춘 3행에 더해, 가로 너비(usableW)에 맞춰 열(cols)을 7~8개로 확장하여 좌우 빈 공간 제거
      spacing = 6f
      rows = 3
      val availableTrayH = max(availableHeightDp - 135f, 130f)
      val cellH = (availableTrayH - (rows - 1) * spacing) / rows
      bubbleSize = cellH.coerceIn(MIN_BUBBLE_SIZE_DP, 52f)

      // 가로 너비(600~800dp)를 꽉 채우도록 cols를 계산
      cols = floor((usableW - 36f + spacing) / (bubbleSize + spacing)).toInt().coerceIn(6, 9)
    } else {
      // 스마트폰 세로 모드: 3열 x 4행 (12개 버블)로 고정하여 4열로 인한 찌그러짐(작은 버튼) 현상 원천 방지
      spacing = DEFAULT_SPACING_DP
      cols = 3
      rows = 4

      val cellW = (usableW - (cols - 1) * spacing) / cols
      val cellH = (usableH - (rows - 1) * spacing) / rows
      bubbleSize = min(cellW, cellH).coerceIn(64f, MAX_BUBBLE_SIZE_DP)
    }

    return GridDimension(
      rows = rows,
      cols = cols,
      bubbleSizeDp = bubbleSize,
      spacingDp = spacing
    )
  }
}

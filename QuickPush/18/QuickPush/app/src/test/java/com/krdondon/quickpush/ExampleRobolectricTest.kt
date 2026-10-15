package com.krdondon.quickpush

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.krdondon.quickpush.R
import com.krdondon.quickpush.data.GamePreferences
import com.krdondon.quickpush.model.GameMode
import com.krdondon.quickpush.model.GridCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("K 푸쉬팝", appName)
  }

  @Test
  fun `verify game preferences defaults and mutations`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = GamePreferences(context)

    assertTrue(prefs.isSoundEnabled)
    assertTrue(prefs.isHapticEnabled)
    assertTrue(prefs.isGlowEnabled)

    prefs.setTimeAttackHighScore(60, 250)
    assertEquals(250, prefs.getTimeAttackHighScore(60))

    // Do not overwrite with lower score
    prefs.setTimeAttackHighScore(60, 100)
    assertEquals(250, prefs.getTimeAttackHighScore(60))

    // Overwrite with higher score
    prefs.setTimeAttackHighScore(60, 320)
    assertEquals(320, prefs.getTimeAttackHighScore(60))

    // Console color skin verification
    assertEquals(com.krdondon.quickpush.model.ConsoleColor.PINK_BUNNY, prefs.consoleColor)
    prefs.consoleColor = com.krdondon.quickpush.model.ConsoleColor.MINT_BUNNY
    assertEquals(com.krdondon.quickpush.model.ConsoleColor.MINT_BUNNY, prefs.consoleColor)
  }

  @Test
  fun `verify grid calculator responsive dimensions`() {
    // Phone portrait test
    val phonePortrait = GridCalculator.calculateGridLayout(
      availableWidthDp = 360f,
      availableHeightDp = 640f,
      isLandscape = false,
      isTablet = false
    )
    assertTrue("Cols should be between 3 and 4", phonePortrait.cols in 3..4)
    assertTrue("Rows should be between 3 and 5", phonePortrait.rows in 3..5)
    assertTrue("Bubble size must be >= 56dp", phonePortrait.bubbleSizeDp >= 56f)

    // Phone landscape test
    val phoneLandscape = GridCalculator.calculateGridLayout(
      availableWidthDp = 720f,
      availableHeightDp = 360f,
      isLandscape = true,
      isTablet = false
    )
    assertTrue("Landscape cols should be >= 4", phoneLandscape.cols >= 4)
    assertTrue("Landscape rows should be between 2 and 5", phoneLandscape.rows in 2..5)

    // Tablet test
    val tablet = GridCalculator.calculateGridLayout(
      availableWidthDp = 800f,
      availableHeightDp = 1000f,
      isLandscape = false,
      isTablet = true
    )
    assertTrue("Tablet cols should be >= 4", tablet.cols >= 4)
    assertTrue("Tablet rows should be >= 4", tablet.rows >= 4)
  }
}

package com.krdondon.quickpush.data

import android.content.Context
import android.content.SharedPreferences
import com.krdondon.quickpush.model.AnimationIntensity
import com.krdondon.quickpush.model.BubbleTheme
import com.krdondon.quickpush.model.ConsoleColor
import com.krdondon.quickpush.model.SoundStyle

class GamePreferences(context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences("pushpop_healing_prefs", Context.MODE_PRIVATE)

  var isSoundEnabled: Boolean
    get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
    set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

  var soundStyle: SoundStyle
    get() {
      val name = prefs.getString(KEY_SOUND_STYLE, SoundStyle.CLASSIC_POP.name)
      return try {
        SoundStyle.valueOf(name ?: SoundStyle.CLASSIC_POP.name)
      } catch (e: Exception) {
        SoundStyle.CLASSIC_POP
      }
    }
    set(value) = prefs.edit().putString(KEY_SOUND_STYLE, value.name).apply()

  var isHapticEnabled: Boolean
    get() = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
    set(value) = prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, value).apply()

  var isGlowEnabled: Boolean
    get() = prefs.getBoolean(KEY_GLOW_ENABLED, true)
    set(value) = prefs.edit().putBoolean(KEY_GLOW_ENABLED, value).apply()

  var isWaveEnabled: Boolean
    get() = prefs.getBoolean(KEY_WAVE_ENABLED, true)
    set(value) = prefs.edit().putBoolean(KEY_WAVE_ENABLED, value).apply()

  var isBorderSparkleEnabled: Boolean
    get() = prefs.getBoolean(KEY_SPARKLE_ENABLED, true)
    set(value) = prefs.edit().putBoolean(KEY_SPARKLE_ENABLED, value).apply()

  var animationIntensity: AnimationIntensity
    get() {
      val name = prefs.getString(KEY_ANIM_INTENSITY, AnimationIntensity.NORMAL.name)
      return try {
        AnimationIntensity.valueOf(name ?: AnimationIntensity.NORMAL.name)
      } catch (e: Exception) {
        AnimationIntensity.NORMAL
      }
    }
    set(value) = prefs.edit().putString(KEY_ANIM_INTENSITY, value.name).apply()

  var consoleColor: ConsoleColor
    get() {
      val name = prefs.getString(KEY_CONSOLE_COLOR, ConsoleColor.PINK_BUNNY.name)
      return try {
        ConsoleColor.valueOf(name ?: ConsoleColor.PINK_BUNNY.name)
      } catch (e: Exception) {
        ConsoleColor.PINK_BUNNY
      }
    }
    set(value) = prefs.edit().putString(KEY_CONSOLE_COLOR, value.name).apply()

  var bubbleTheme: BubbleTheme
    get() {
      val name = prefs.getString(KEY_BUBBLE_THEME, BubbleTheme.VIVID_RAINBOW.name)
      return try {
        BubbleTheme.valueOf(name ?: BubbleTheme.VIVID_RAINBOW.name)
      } catch (e: Exception) {
        BubbleTheme.VIVID_RAINBOW
      }
    }
    set(value) = prefs.edit().putString(KEY_BUBBLE_THEME, value.name).apply()

  var isAutoGrid: Boolean
    get() = prefs.getBoolean(KEY_AUTO_GRID, true)
    set(value) = prefs.edit().putBoolean(KEY_AUTO_GRID, value).apply()

  var manualCols: Int
    get() = prefs.getInt(KEY_MANUAL_COLS, 3)
    set(value) = prefs.edit().putInt(KEY_MANUAL_COLS, value).apply()

  var manualRows: Int
    get() = prefs.getInt(KEY_MANUAL_ROWS, 4)
    set(value) = prefs.edit().putInt(KEY_MANUAL_ROWS, value).apply()

  var isToggleMode: Boolean
    get() = prefs.getBoolean(KEY_TOGGLE_MODE, true)
    set(value) = prefs.edit().putBoolean(KEY_TOGGLE_MODE, value).apply()

  var lifetimePops: Long
    get() = prefs.getLong(KEY_LIFETIME_POPS, 0L)
    set(value) = prefs.edit().putLong(KEY_LIFETIME_POPS, value).apply()

  var maxLevelReached: Int
    get() = prefs.getInt(KEY_MAX_LEVEL, 1)
    set(value) = prefs.edit().putInt(KEY_MAX_LEVEL, value).apply()

  fun getTimeAttackHighScore(durationSec: Int): Int {
    return prefs.getInt("${KEY_TIME_ATTACK_HIGH_SCORE}_$durationSec", 0)
  }

  fun setTimeAttackHighScore(durationSec: Int, score: Int) {
    val current = getTimeAttackHighScore(durationSec)
    if (score > current) {
      prefs.edit().putInt("${KEY_TIME_ATTACK_HIGH_SCORE}_$durationSec", score).apply()
    }
  }

  fun resetAllGameData() {
    prefs.edit()
      .remove(KEY_LIFETIME_POPS)
      .remove(KEY_MAX_LEVEL)
      .remove("${KEY_TIME_ATTACK_HIGH_SCORE}_30")
      .remove("${KEY_TIME_ATTACK_HIGH_SCORE}_60")
      .remove("${KEY_TIME_ATTACK_HIGH_SCORE}_120")
      .apply()
  }

  companion object {
    private const val KEY_SOUND_ENABLED = "key_sound_enabled"
    private const val KEY_SOUND_STYLE = "key_sound_style"
    private const val KEY_HAPTIC_ENABLED = "key_haptic_enabled"
    private const val KEY_GLOW_ENABLED = "key_glow_enabled"
    private const val KEY_WAVE_ENABLED = "key_wave_enabled"
    private const val KEY_SPARKLE_ENABLED = "key_sparkle_enabled"
    private const val KEY_ANIM_INTENSITY = "key_anim_intensity"
    private val KEY_BUBBLE_THEME = "key_bubble_theme"
    private const val KEY_CONSOLE_COLOR = "key_console_color"
    private const val KEY_AUTO_GRID = "key_auto_grid"
    private const val KEY_MANUAL_COLS = "key_manual_cols"
    private const val KEY_MANUAL_ROWS = "key_manual_rows"
    private const val KEY_TOGGLE_MODE = "key_toggle_mode"
    private const val KEY_LIFETIME_POPS = "key_lifetime_pops"
    private const val KEY_MAX_LEVEL = "key_max_level"
    private const val KEY_TIME_ATTACK_HIGH_SCORE = "key_ta_high"
  }
}

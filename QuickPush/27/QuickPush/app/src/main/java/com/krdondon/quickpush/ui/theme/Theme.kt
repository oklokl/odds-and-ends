package com.krdondon.quickpush.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
  primary = QuickPushPink,
  onPrimary = Color.White,
  primaryContainer = QuickPushPinkContainer,
  onPrimaryContainer = TextPrimary,
  secondary = QuickPushMint,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFD7F9F1),
  onSecondaryContainer = TextPrimary,
  tertiary = QuickPushYellow,
  onTertiary = TextPrimary,
  background = BackgroundWarm,
  onBackground = TextPrimary,
  surface = SurfaceWhite,
  onSurface = TextPrimary,
  surfaceVariant = Color(0xFFFFEEF4),
  onSurfaceVariant = TextSecondary,
  outline = QuickPushPink
)

private val DarkColorScheme = darkColorScheme(
  primary = QuickPushPink,
  onPrimary = Color.White,
  primaryContainer = Color(0xFF8B123F),
  onPrimaryContainer = Color(0xFFFFD4E2),
  secondary = QuickPushMint,
  onSecondary = Color(0xFF003D32),
  tertiary = QuickPushYellow,
  onTertiary = Color(0xFF3E2D00),
  background = Color(0xFF1F1722),
  onBackground = Color(0xFFF9EDF5),
  surface = Color(0xFF2A202E),
  onSurface = Color(0xFFF9EDF5),
  surfaceVariant = Color(0xFF3D2E42),
  onSurfaceVariant = Color(0xFFDAC7DE),
  outline = QuickPushPink
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElegantPrimary,
    onPrimary = ElegantOnPrimary,
    secondary = ElegantPrimary,
    onSecondary = ElegantOnPrimary,
    background = ElegantBackground,
    onBackground = ElegantTextMain,
    surface = ElegantSurface,
    onSurface = ElegantTextMain,
    surfaceVariant = ElegantSurface,
    onSurfaceVariant = ElegantTextSecondary,
    outline = ElegantOutline,
    errorContainer = ElegantErrorBackground,
    onErrorContainer = ElegantOnErrorText
  )

private val LightColorScheme =
  darkColorScheme( // In this "Elegant Dark" app, force dark palette aesthetics
    primary = ElegantPrimary,
    onPrimary = ElegantOnPrimary,
    secondary = ElegantPrimary,
    onSecondary = ElegantOnPrimary,
    background = ElegantBackground,
    onBackground = ElegantTextMain,
    surface = ElegantSurface,
    onSurface = ElegantTextMain,
    surfaceVariant = ElegantSurface,
    onSurfaceVariant = ElegantTextSecondary,
    outline = ElegantOutline,
    errorContainer = ElegantErrorBackground,
    onErrorContainer = ElegantOnErrorText
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Forced elegant dark theme experience by default
  // Dynamic color is key on Android 12+, disabled by default to lock Elegant Dark signature brand
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

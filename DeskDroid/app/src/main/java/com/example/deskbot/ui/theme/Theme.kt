package com.example.deskbot.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CatppuccinDarkColorScheme = darkColorScheme(
    primary = CatppuccinMauve,
    onPrimary = CatppuccinCrust,
    primaryContainer = CatppuccinSurface1,
    onPrimaryContainer = CatppuccinText,
    secondary = CatppuccinBlue,
    onSecondary = CatppuccinCrust,
    secondaryContainer = CatppuccinSurface0,
    onSecondaryContainer = CatppuccinSubtext1,
    tertiary = CatppuccinTeal,
    onTertiary = CatppuccinCrust,
    background = CatppuccinBase,
    onBackground = CatppuccinText,
    surface = CatppuccinSurface0,
    onSurface = CatppuccinText,
    surfaceVariant = CatppuccinSurface1,
    onSurfaceVariant = CatppuccinSubtext0,
    outline = CatppuccinSurface2,
    outlineVariant = CatppuccinSurface1,
    error = CatppuccinRed,
    onError = CatppuccinCrust
)

@Composable
fun DeskBotTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = CatppuccinBase.toArgb()
            window.navigationBarColor = CatppuccinBase.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = CatppuccinDarkColorScheme,
        content = content
    )
}

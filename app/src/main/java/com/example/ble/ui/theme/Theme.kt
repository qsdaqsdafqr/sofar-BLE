package com.example.ble.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Slate700,
    onPrimary = SurfaceWhite,
    secondary = SignalTeal,
    onSecondary = SurfaceWhite,
    tertiary = SignalAmber,
    background = Mist100,
    onBackground = Slate900,
    surface = SurfaceWhite,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFD7E3EF),
    onSurfaceVariant = Slate700,
    error = SignalRed,
    onError = SurfaceWhite
)

@Composable
fun BLETheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

package com.jabs.lumen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumenColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1B33),
    secondary = Color(0xFFFFC978),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF17191C),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = Color(0xFF9AA0A6),
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumenColors, content = content)
}

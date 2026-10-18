package com.jabs.lumen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumenColors = darkColorScheme(
    primary = Color(0xFF8FD8A8),            // verde-menta suave — acento principal
    onPrimary = Color(0xFF0B2B18),          // texto/ícone sobre o verde
    primaryContainer = Color(0xFF1F4A32),   // FAB e containers de destaque
    onPrimaryContainer = Color(0xFFCDEFDB),
    secondary = Color(0xFF9AB8A6),          // verde acinzentado — apoio discreto
    onSecondary = Color(0xFF10231A),
    secondaryContainer = Color(0xFF26352C), // fundo de chips/abas selecionados
    onSecondaryContainer = Color(0xFFD8E8DE),
    tertiary = Color(0xFFFFC978),           // mantém o âmbar para avisos pontuais
    onTertiary = Color(0xFF2B1D05),
    background = Color(0xFF0E0F11),         // inalterado — neutro
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF17191C),            // inalterado
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF23262B),     // inalterado — trilhos, placeholders
    onSurfaceVariant = Color(0xFFA7ADB4),   // texto secundário um pouco mais legível
    outline = Color(0xFF3A4048),            // bordas quando indispensáveis
    error = Color(0xFFE0615E),
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumenColors, content = content)
}

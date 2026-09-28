package com.clipmaker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0D0E12)
    val Surface = Color(0xFF16181E)
    val SurfaceHigh = Color(0xFF1F222A)
    val SurfaceHighest = Color(0xFF2A2E38)
    val Outline = Color(0xFF363A46)
    val Accent = Color(0xFF7C5CFF)
    val AccentSoft = Color(0xFF3A2F7A)
    val Cyan = Color(0xFF22D3EE)
    val Pink = Color(0xFFFF4D8D)
    val Amber = Color(0xFFFFC53D)
    val Green = Color(0xFF34D399)
    val TextPrimary = Color(0xFFF2F3F7)
    val TextSecondary = Color(0xFF9AA0AE)
    val Danger = Color(0xFFFF5A5F)

    // Timeline clip colours per track kind.
    val VideoClip = Color(0xFF4C6FFF)
    val OverlayClip = Color(0xFF9B5CFF)
    val AudioClip = Color(0xFF14B8A6)
    val TextClip = Color(0xFFF59E0B)
    val Beat = Color(0x66FFC53D)
    val Playhead = Color(0xFFFFFFFF)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Color.White,
    secondary = Palette.Cyan,
    tertiary = Palette.Pink,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,
    surfaceContainerLow = Palette.Surface,
    outline = Palette.Outline,
    error = Palette.Danger,
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ClipMakerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

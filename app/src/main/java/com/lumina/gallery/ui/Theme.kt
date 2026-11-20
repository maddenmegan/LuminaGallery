package com.lumina.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LuminaBlack = Color(0xFF000000)
val LuminaSurface = Color(0xFF0D111A)
val LuminaCard = Color(0xFF161D2C)
val LuminaPill = Color(0xFF1F2737)
val LuminaCyan = Color(0xFF38BDF8)
val LuminaViolet = Color(0xFFA78BFA)
val LuminaGreen = Color(0xFF4ADE80)
val LuminaAmber = Color(0xFFFBBF24)
val LuminaRed = Color(0xFFF87171)
val LuminaMuted = Color(0xFF94A3B8)

private val LuminaColors = darkColorScheme(
    primary = LuminaCyan,
    onPrimary = Color(0xFF00131E),
    secondary = LuminaViolet,
    background = LuminaBlack,
    surface = LuminaSurface,
    surfaceVariant = LuminaCard,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = LuminaMuted
)

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LuminaColors,
        content = content
    )
}

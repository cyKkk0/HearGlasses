package com.hearglasses.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HearGlassesColors = lightColorScheme(
    primary = Color(0xFF000000),
    secondary = Color(0xFF4CAF50),
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    outline = Color(0xFF666666),
)

@Composable
fun HearGlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HearGlassesColors,
        content = content,
    )
}

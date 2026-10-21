package com.readall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF7F3EB)
private val PaperSurface = Color(0xFFFFFBF4)
private val Teal = Color(0xFF1F5C58)
private val TealContainer = Color(0xFFB7E4DF)
private val Gold = Color(0xFF8B6914)
private val Ink = Color(0xFF1C1B16)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = Color(0xFF04201E),
    secondary = Gold,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8E2D6),
    onSurfaceVariant = Color(0xFF4A463C),
    outline = Color(0xFF7A756A),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD0C9),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF0F4440),
    onPrimaryContainer = Color(0xFFB7E4DF),
    secondary = Color(0xFFE4C57A),
    onSecondary = Color(0xFF3D2E00),
    background = Color(0xFF141311),
    onBackground = Color(0xFFE7E2D8),
    surface = Color(0xFF1C1B18),
    onSurface = Color(0xFFE7E2D8),
    surfaceVariant = Color(0xFF2A2823),
    onSurfaceVariant = Color(0xFFCBC5B8),
    outline = Color(0xFF958F83)
)

@Composable
fun ReadAllTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

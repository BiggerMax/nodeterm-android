package com.nodeterm.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalDark = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF00210B),
    primaryContainer = Color(0xFF00391B),
    onPrimaryContainer = Color(0xFF8BF5AD),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF004F58),
    onSecondaryContainer = Color(0xFFA6ECFF),
    tertiary = Color(0xFFF472B6),
    onTertiary = Color(0xFF51002F),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF9DA7B0),
    outline = Color(0xFF3D444D),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF2D0000)
)

private val TerminalLight = lightColorScheme(
    primary = Color(0xFF006E3B),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328)
)

@Composable
fun NodetermTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TerminalDark else TerminalLight,
        content = content
    )
}

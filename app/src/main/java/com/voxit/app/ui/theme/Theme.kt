package com.voxit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = SignalBlue, onPrimary = Navy,
    secondary = SafeGreen, onSecondary = Navy,
    tertiary = Amber, onTertiary = Navy,
    background = Navy, onBackground = Mist,
    surface = Panel, onSurface = Mist,
    surfaceVariant = PanelRaised, onSurfaceVariant = SignalMuted,
    error = AlertRed, onError = Navy
)

@Composable
fun VoxITTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

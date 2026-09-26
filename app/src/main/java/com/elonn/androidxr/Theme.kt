package com.elonn.androidxr

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Field's chrome floats over a live camera feed whose real-world lighting/color is unpredictable,
 * so it needs its own deliberately dark, near-opaque, high-contrast palette rather than the default
 * Material 3 light scheme (a pale lavender fill with no border) that was previously used as-is --
 * that combination was close to invisible over some real backgrounds. One fixed dark scheme
 * (not isSystemInDarkTheme-dependent) so the chrome reads the same regardless of the phone's own
 * light/dark system setting, since it's judged against the camera image, not the OS theme.
 */
private val ElonnColorScheme =
    darkColorScheme(
        primary = Color(0xFFB388FF),
        onPrimary = Color(0xFF1A1033),
        primaryContainer = Color(0xFF4A3B7A),
        onPrimaryContainer = Color(0xFFEDE7FF),
        secondary = Color(0xFF8E88B0),
        onSecondary = Color(0xFFF1EEFB),
        secondaryContainer = Color(0xF01C1830),
        onSecondaryContainer = Color(0xFFF1EEFB),
        tertiary = Color(0xFFFFC178),
        onTertiary = Color(0xFF3A2600),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF3A0A0A),
        background = Color(0xFF0F0D1A),
        onBackground = Color(0xFFF1EEFB),
        surface = Color(0xF0171429),
        onSurface = Color(0xFFF1EEFB),
        surfaceVariant = Color(0xF0241F3D),
        onSurfaceVariant = Color(0xFFC7C2E0),
        outline = Color(0xFF8E7CC3),
        outlineVariant = Color(0xFF4A3B7A),
    )

/** Border width every FloatingWindow and card uses, so window chrome reads as one visual family. */
val ElonnBorderWidth = 1.5.dp

object ElonnSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 20.dp
}

@Composable
fun ElonnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ElonnColorScheme, content = content)
}

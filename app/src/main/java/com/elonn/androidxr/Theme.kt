package com.elonn.androidxr

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elonn.androidxr.core.ResolvedTheme

/**
 * Fallback colors, used until the first World Dataset response resolves a real theme (and for any
 * color role the canonical token set has no equivalent for -- secondary/tertiary have no canonical
 * counterpart, so they stay these bespoke values always). Originally chosen deliberately dark,
 * near-opaque, high-contrast against a live camera feed -- see elonnColorScheme() below for how the
 * member's resolved color.* tokens now override everything that DOES have a canonical equivalent.
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

/**
 * 1 canonical `relative`-type token unit = this many dp, for spacing and radius -- the same base
 * web.elonn.local's CSS adapter uses for rem (1 unit = 1rem = 16px by default), so a given token
 * value reads as the same proportion on both Runtimes. borderWidth is the one category this does
 * NOT apply to: those values (0/1/2) are already literal dp counts, the same way the Web adapter
 * treats borderWidth as literal px rather than rem.
 */
private const val TOKEN_BASE_DP = 16f

/**
 * The member's currently resolved effective theme (RuntimeState.theme), set by
 * ElonnRuntimeApp/MainActivity whenever a new World Dataset response arrives. A mutableStateOf,
 * not a plain var, because ElonnTheme is invoked from three independent Compose roots (the
 * Activity's own setContent, plus two standalone ComposeViews in ArCorePassthrough.kt) with no
 * shared composition tree to thread a parameter through -- this is the one thing all three can
 * reach, and Compose recomposes each of them automatically when it changes.
 */
object CurrentTheme {
    var value: ResolvedTheme by mutableStateOf(ResolvedTheme.Empty)
}

/** Ambient access to the current theme for ElonnSpacing/ElonnBorderWidth/ElonnRadius below. */
val LocalResolvedTheme = compositionLocalOf { ResolvedTheme.Empty }

/**
 * Builds a ColorScheme from the member's resolved theme, overriding every role with a direct
 * canonical color.* counterpart and leaving every role without one (secondary, tertiary, and
 * their "on" / Container pairs) at the bespoke fallback above. onPrimary/onError/onPrimaryContainer
 * are deliberately set to the background/text tokens rather than invented contrast colors of
 * their own -- the canonical schema has no separate "on-accent" token yet.
 */
private fun elonnColorScheme(theme: ResolvedTheme): ColorScheme {
    fun color(key: String, fallback: Color): Color {
        val hex = theme.color(key) ?: return fallback
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
    }

    val background = color("color.background", ElonnColorScheme.background)
    val text = color("color.text", ElonnColorScheme.onBackground)
    val surface = color("color.surface", ElonnColorScheme.surface)
    val primary = color("color.primary", ElonnColorScheme.primary)
    val border = color("color.border", ElonnColorScheme.outline)
    val danger = color("color.danger", ElonnColorScheme.error)

    return ElonnColorScheme.copy(
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = text,
        primary = primary,
        onPrimary = background,
        primaryContainer = surface,
        onPrimaryContainer = text,
        error = danger,
        onError = background,
        outline = border,
        outlineVariant = border,
    )
}

/** Border width every FloatingWindow and card uses, so window chrome reads as one visual family. */
val ElonnBorderWidth: Dp
    @Composable get() {
        val relative = LocalResolvedTheme.current.relative("borderWidth.thin")
        return if (relative != null) relative.toFloat().dp else 1.5.dp
    }

object ElonnSpacing {
    val xs: Dp @Composable get() = 4.dp.withToken("spacing.xs")
    val sm: Dp @Composable get() = 8.dp.withToken("spacing.sm")
    val md: Dp @Composable get() = 12.dp.withToken("spacing.md")
    val lg: Dp @Composable get() = 20.dp.withToken("spacing.lg")
}

/**
 * Corner radius, scaled by the same TOKEN_BASE_DP as spacing so the two categories stay
 * proportional to each other. Names here describe the existing call sites (handle/card/panel),
 * not the token scale directly -- radius.sm/md/lg map onto them in that order, the closest
 * canonical match to each literal this repo already used before this token system existed.
 */
object ElonnRadius {
    val handle: Dp @Composable get() = 4.dp.withToken("radius.sm")
    val card: Dp @Composable get() = 8.dp.withToken("radius.md")
    val panel: Dp @Composable get() = 10.dp.withToken("radius.lg")

    /** radius.full (keyword): fully rounded regardless of element size, Compose-clamped to the
     * shorter side -- the same 9999-style convention web.elonn.local's CSS pill shapes already used. */
    val full: Dp = 9999.dp
}

@Composable
private fun Dp.withToken(tokenKey: String): Dp {
    val value = LocalResolvedTheme.current.relative(tokenKey) ?: return this
    return (value.toFloat() * TOKEN_BASE_DP).dp
}

@Composable
fun ElonnTheme(content: @Composable () -> Unit) {
    val theme = CurrentTheme.value
    CompositionLocalProvider(LocalResolvedTheme provides theme) {
        MaterialTheme(colorScheme = elonnColorScheme(theme), content = content)
    }
}

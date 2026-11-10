package com.minichat.app.design.theme

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** The user's saved preference, persisted with DataStore. */
enum class ThemeMode {
    Light,
    Dark,
    System,
}

val MiniChatShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val LightScheme = lightColorScheme(
    primary = MiniChatColors.YellowPrimary,
    onPrimary = MiniChatColors.OnYellow,
    primaryContainer = MiniChatColors.YellowSoft,
    onPrimaryContainer = MiniChatColors.LightTextPrimary,
    secondary = MiniChatColors.AmberSecondary,
    onSecondary = MiniChatColors.OnYellow,
    secondaryContainer = MiniChatColors.YellowSoft,
    onSecondaryContainer = MiniChatColors.LightTextPrimary,
    tertiary = MiniChatColors.AmberSecondary,
    onTertiary = MiniChatColors.OnYellow,
    background = MiniChatColors.LightBackground,
    onBackground = MiniChatColors.LightTextPrimary,
    surface = MiniChatColors.LightSurface,
    onSurface = MiniChatColors.LightTextPrimary,
    surfaceVariant = MiniChatColors.LightSurfaceMuted,
    onSurfaceVariant = MiniChatColors.LightTextSecondary,
    surfaceContainer = MiniChatColors.LightSurface,
    surfaceContainerHigh = MiniChatColors.LightSurfaceMuted,
    surfaceContainerHighest = MiniChatColors.LightSurfaceMuted,
    surfaceContainerLow = MiniChatColors.LightSurface,
    surfaceContainerLowest = MiniChatColors.LightSurface,
    outline = MiniChatColors.LightOutline,
    outlineVariant = MiniChatColors.LightOutline,
    error = MiniChatColors.Danger,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFE7E7),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF7A1C1C),
    inverseSurface = MiniChatColors.LightTextPrimary,
    inverseOnSurface = MiniChatColors.LightSurface,
    scrim = androidx.compose.ui.graphics.Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = MiniChatColors.YellowPrimary,
    onPrimary = MiniChatColors.OnYellow,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3A3208),
    onPrimaryContainer = MiniChatColors.YellowSoft,
    secondary = MiniChatColors.AmberSecondary,
    onSecondary = MiniChatColors.OnYellow,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF33290A),
    onSecondaryContainer = MiniChatColors.YellowSoft,
    tertiary = MiniChatColors.AmberSecondary,
    onTertiary = MiniChatColors.OnYellow,
    background = MiniChatColors.DarkBackground,
    onBackground = MiniChatColors.DarkTextPrimary,
    surface = MiniChatColors.DarkSurface,
    onSurface = MiniChatColors.DarkTextPrimary,
    surfaceVariant = MiniChatColors.DarkSurfaceElevated,
    onSurfaceVariant = MiniChatColors.DarkTextSecondary,
    surfaceContainer = MiniChatColors.DarkSurface,
    surfaceContainerHigh = MiniChatColors.DarkSurfaceElevated,
    surfaceContainerHighest = MiniChatColors.DarkSurfaceElevated,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF15171B),
    surfaceContainerLowest = MiniChatColors.DarkBackground,
    outline = MiniChatColors.DarkOutline,
    outlineVariant = MiniChatColors.DarkOutline,
    error = MiniChatColors.Danger,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFF3B1B1B),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFD9D9),
    inverseSurface = MiniChatColors.DarkTextPrimary,
    inverseOnSurface = MiniChatColors.DarkBackground,
    scrim = androidx.compose.ui.graphics.Color.Black,
)

/** Theme transition duration so light ⇄ dark flips feel deliberate instead of jarring. */
private const val THEME_TRANSITION_MS = 320

@Composable
fun MiniChatTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val colorScheme = if (dark) DarkScheme else LightScheme
    val extras = if (dark) DarkExtraColors else LightExtraColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalMiniChatExtraColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MiniChatTypography,
            shapes = MiniChatShapes,
            content = content,
        )
    }
}

/** Animation spec used for theme-dependent colour transitions. */
internal val themeAnimationSpec = tween<androidx.compose.ui.graphics.Color>(durationMillis = THEME_TRANSITION_MS)

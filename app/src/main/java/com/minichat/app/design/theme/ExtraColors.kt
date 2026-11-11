package com.minichat.app.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors that Material 3's [androidx.compose.material3.ColorScheme] does not model but that
 * MINI CHAT needs in order to keep bubble / status colours identical across every screen.
 */
@Immutable
data class MiniChatExtraColors(
    val bubbleOutgoing: Color,
    val bubbleOutgoingText: Color,
    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val surfaceMuted: Color,
    val textSecondary: Color,
    val divider: Color,
    val online: Color,
    val danger: Color,
    val success: Color,
    val scrimOverlay: Color,
    val isDark: Boolean,
)

val LightExtraColors = MiniChatExtraColors(
    bubbleOutgoing = MiniChatColors.YellowPrimary,
    bubbleOutgoingText = MiniChatColors.OnYellow,
    bubbleIncoming = MiniChatColors.IncomingLight,
    bubbleIncomingText = MiniChatColors.LightTextPrimary,
    surfaceMuted = MiniChatColors.LightSurfaceMuted,
    textSecondary = MiniChatColors.LightTextSecondary,
    divider = MiniChatColors.LightOutline,
    online = MiniChatColors.Success,
    danger = MiniChatColors.Danger,
    success = MiniChatColors.Success,
    scrimOverlay = Color(0x14000000),
    isDark = false,
)

val DarkExtraColors = MiniChatExtraColors(
    bubbleOutgoing = MiniChatColors.YellowPrimary,
    bubbleOutgoingText = MiniChatColors.OnYellow,
    bubbleIncoming = MiniChatColors.IncomingDark,
    bubbleIncomingText = MiniChatColors.DarkTextPrimary,
    surfaceMuted = MiniChatColors.DarkSurfaceElevated,
    textSecondary = MiniChatColors.DarkTextSecondary,
    divider = MiniChatColors.DarkOutline,
    online = MiniChatColors.Success,
    danger = MiniChatColors.Danger,
    success = MiniChatColors.Success,
    scrimOverlay = Color(0x66000000),
    isDark = true,
)

val LocalMiniChatExtraColors = staticCompositionLocalOf { LightExtraColors }

/** Shorthand accessor: `MiniChatTheme.extra.bubbleOutgoing`. */
object MiniChatTheme {

    val extra: MiniChatExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalMiniChatExtraColors.current
}

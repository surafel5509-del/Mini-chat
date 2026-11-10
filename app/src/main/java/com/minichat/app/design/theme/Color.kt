package com.minichat.app.design.theme

import androidx.compose.ui.graphics.Color

/**
 * MINI CHAT brand palette.
 *
 * Yellow is the accent — never the canvas. Big yellow surfaces are reserved for the
 * primary action of a screen (send button, FAB, welcome CTA) and for the user's own
 * message bubbles so the conversation has an unmistakable "mine vs theirs" rhythm.
 */
object MiniChatColors {
    val YellowPrimary = Color(0xFFFFD43B)
    val YellowPressed = Color(0xFFF0C42C)
    val AmberSecondary = Color(0xFFF4B942)
    val YellowSoft = Color(0xFFFFF2B3)

    val LightBackground = Color(0xFFF8F9FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceMuted = Color(0xFFF1F3F8)
    val LightOutline = Color(0xFFE4E7EF)
    val LightTextPrimary = Color(0xFF14161A)
    val LightTextSecondary = Color(0xFF6B7280)

    val DarkBackground = Color(0xFF101114)
    val DarkSurface = Color(0xFF191B20)
    val DarkSurfaceElevated = Color(0xFF21242B)
    val DarkOutline = Color(0xFF2C3038)
    val DarkTextPrimary = Color(0xFFF5F6F8)
    val DarkTextSecondary = Color(0xFF9AA0A8)

    val Success = Color(0xFF34C759)
    val Danger = Color(0xFFEB5757)
    val Info = Color(0xFF5B8DEF)

    /** Warm ink used on top of yellow surfaces in both themes. */
    val OnYellow = Color(0xFF1A1608)

    /** Incoming bubble tints. */
    val IncomingLight = Color(0xFFF1F3F8)
    val IncomingDark = Color(0xFF23262D)
}

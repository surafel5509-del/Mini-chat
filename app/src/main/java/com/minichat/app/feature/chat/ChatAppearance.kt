package com.minichat.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Chat appearance: the wallpaper behind a thread and the colour of my bubbles.
 *
 * Every preset is a plain string that is stored on the conversation row, so a group keeps the
 * same look on every phone and new presets never need a migration.
 */
data class WallpaperPreset(
    val id: String,
    val label: String,
    val colors: List<Color>,
)

val ChatWallpapers: List<WallpaperPreset> = listOf(
    WallpaperPreset("default", "Default", emptyList()),
    WallpaperPreset("aurora", "Aurora", listOf(Color(0xFF0B1F2A), Color(0xFF14495E), Color(0xFF1F7A8C))),
    WallpaperPreset("sunset", "Sunset", listOf(Color(0xFF2B1055), Color(0xFF6A3EA1), Color(0xFFD76D77))),
    WallpaperPreset("ember", "Ember", listOf(Color(0xFF23120B), Color(0xFF6B2D14), Color(0xFFC05621))),
    WallpaperPreset("forest", "Forest", listOf(Color(0xFF0B1A12), Color(0xFF14532D), Color(0xFF2F855A))),
    WallpaperPreset("midnight", "Midnight", listOf(Color(0xFF0B0C10), Color(0xFF1F2833), Color(0xFF2E3B4E))),
    WallpaperPreset("candy", "Candy", listOf(Color(0xFF3A0CA3), Color(0xFF7209B7), Color(0xFFF72585))),
    WallpaperPreset("slate", "Slate", listOf(Color(0xFF111827), Color(0xFF1F2937), Color(0xFF374151))),
)

data class BubblePreset(val value: String?, val label: String, val color: Color?)

val ChatBubbleColors: List<BubblePreset> = listOf(
    BubblePreset(null, "Default", null),
    BubblePreset("#FFD54A", "Sunshine", Color(0xFFFFD54A)),
    BubblePreset("#7C5CFF", "Violet", Color(0xFF7C5CFF)),
    BubblePreset("#2D6CDF", "Ocean", Color(0xFF2D6CDF)),
    BubblePreset("#1F8A70", "Mint", Color(0xFF1F8A70)),
    BubblePreset("#C2410C", "Amber", Color(0xFFC2410C)),
    BubblePreset("#DB2777", "Rose", Color(0xFFDB2777)),
    BubblePreset("#0F766E", "Teal", Color(0xFF0F766E)),
    BubblePreset("#4B5563", "Graphite", Color(0xFF4B5563)),
)

fun wallpaperPreset(id: String?): WallpaperPreset? =
    ChatWallpapers.firstOrNull { it.id == id && it.id != "default" }

/** Parses `#RRGGBB` / `#AARRGGBB` values coming from the server; null when unusable. */
fun parseHexColor(value: String?, fallback: Color? = null): Color? {
    val raw = value?.trim()?.removePrefix("#") ?: return fallback
    return runCatching {
        when (raw.length) {
            6 -> Color(0xFF000000L or raw.toLong(16))
            8 -> Color(raw.toLong(16))
            else -> fallback ?: return null
        }
    }.getOrDefault(fallback)
}

/** Readable text colour for an arbitrary bubble background. */
fun textColorOn(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.62f) Color(0xFF15161A) else Color.White
}

/** Soft version of a bubble colour, used for incoming bubbles and chips. */
fun softVariant(color: Color, amount: Float = 0.22f): Color =
    lerp(Color(0xFF15161A), color, amount)

@Composable
fun ChatWallpaper(
    wallpaperId: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val preset = wallpaperPreset(wallpaperId)
    val background = if (preset == null) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(preset.colors))
    }
    Box(modifier = modifier.then(background), content = content)
}

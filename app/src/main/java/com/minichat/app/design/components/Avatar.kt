package com.minichat.app.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

private val avatarPalette = listOf(
    Color(0xFFF4B942),
    Color(0xFF8ECAE6),
    Color(0xFFB8A1FF),
    Color(0xFF7FD1AE),
    Color(0xFFFFA48A),
    Color(0xFF9BC4FF),
    Color(0xFFF2A7C3),
)

private fun paletteFor(seed: String): Pair<Color, Color> {
    if (seed.isEmpty()) return avatarPalette[0] to avatarPalette[1]
    var hash = 7
    for (char in seed) hash = hash * 31 + char.code
    val index = ((hash % avatarPalette.size) + avatarPalette.size) % avatarPalette.size
    val start = avatarPalette[index]
    val end = avatarPalette[(index + 2) % avatarPalette.size]
    return start to end
}

fun initialsOf(displayName: String, username: String): String {
    val source = displayName.trim().ifBlank { username.trim() }
    if (source.isEmpty()) return "M"
    val parts = source.split(' ', '_', '.', '-').filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        else -> source.take(2).uppercase()
    }
}

/**
 * The avatar used everywhere in MINI CHAT: a soft gradient disc with initials that reads well on
 * both themes, an optional image loaded through Coil, and an online ring.
 */
@Composable
fun MiniChatAvatar(
    displayName: String,
    username: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    online: Boolean = false,
    showOnlineIndicator: Boolean = false,
    borderWidth: Dp = 0.dp,
) {
    val (start, end) = paletteFor(displayName.ifBlank { username })
    val ringAlpha by animateFloatAsState(
        targetValue = if (showOnlineIndicator && online) 1f else 0f,
        animationSpec = tween(220),
        label = "onlineRing",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            start.copy(alpha = 0.95f),
                            end.copy(alpha = 0.85f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isNullOrBlank()) {
                Text(
                    text = initialsOf(displayName, username),
                    color = MiniChatColors.OnYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.34f).sp,
                )
            } else {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "$displayName avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape),
                )
            }
            if (borderWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .border(borderWidth, Color.White.copy(alpha = 0.55f), CircleShape),
                )
            }
        }

        if (ringAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .size(size * 0.30f)
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .size(size * 0.30f),
            )
            Box(
                modifier = Modifier
                    .size(size * 0.22f)
                    .align(Alignment.BottomEnd)
                    .background(MiniChatTheme.extra.online.copy(alpha = ringAlpha), CircleShape),
            )
        }
    }
}

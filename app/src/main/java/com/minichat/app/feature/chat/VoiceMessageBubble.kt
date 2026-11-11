package com.minichat.app.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message

/**
 * A voice note bubble.
 *
 * Outgoing notes are yellow with dark ink (matching text bubbles); incoming notes
 * use the muted surface. The waveform doubles as the scrubber: the played portion
 * is filled, the rest is dimmed.
 */
@Composable
fun VoiceMessageContent(
    message: Message,
    isMine: Boolean,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    isLoading: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = (durationMs.takeIf { it > 0 } ?: message.durationMs ?: 1).coerceAtLeast(1)
    val progress = if (isPlaying || positionMs > 0) {
        (positionMs.toFloat() / total).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(120),
        label = "voiceProgress",
    )

    val ink = if (isMine) MiniChatColors.OnYellow else MaterialTheme.colorScheme.onSurface
    val dimInk = ink.copy(alpha = 0.35f)

    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Play / pause
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (isMine) MiniChatColors.OnYellow.copy(alpha = 0.14f)
                    else MiniChatColors.YellowPrimary.copy(alpha = 0.22f),
                )
                .clickable(enabled = !isLoading, onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (isMine) MiniChatColors.OnYellow else MiniChatColors.AmberSecondary,
                )

                else -> Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isMine) MiniChatColors.OnYellow else MiniChatColors.AmberSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(modifier = Modifier.width(150.dp)) {
            VoiceWaveform(
                progress = animatedProgress,
                playedColor = ink,
                pendingColor = dimInk,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(
                        if (isPlaying || positionMs > 0) positionMs else (message.durationMs ?: 0),
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = ink.copy(alpha = 0.75f),
                )
                if (!message.isExpired) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = null,
                        tint = ink.copy(alpha = 0.45f),
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "24h",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = ink.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

/** Deterministic bar heights so a clip looks the same every time it is rendered. */
@Composable
private fun VoiceWaveform(
    progress: Float,
    playedColor: Color,
    pendingColor: Color,
    bars: Int = 26,
) {
    val heights = remember { waveformHeights(bars) }
    Row(
        modifier = Modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEachIndexed { index, fraction ->
            val played = (index.toFloat() / bars) < progress
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((4 + 18 * fraction).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (played) playedColor else pendingColor),
            )
        }
    }
}

/**
 * A fixed pseudo-random bar shape, derived once per bar count so the waveform is
 * identical across recompositions and devices (no real samples needed for a
 * 7 second clip).
 */
private fun waveformHeights(bars: Int): List<Float> {
    val values = ArrayList<Float>(bars)
    var seed = 7
    repeat(bars) {
        seed = (seed * 31 + 17) % 101
        values.add(0.25f + (seed % 70) / 100f)
    }
    return values
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Used by the chat screen when a note's signed URL is not cached yet. */
@Composable
fun VoiceUnavailable(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.labelSmall,
        color = MiniChatTheme.extra.textSecondary,
        modifier = Modifier.padding(8.dp),
    )
}

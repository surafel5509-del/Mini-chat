package com.minichat.app.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.core.audio.VoiceRecorder
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

/**
 * The recording state of the composer.
 *
 * Shows a live level meter, the elapsed time against the 7 second cap, and the
 * two ways out: cancel (discards the clip) or send. Recording itself is driven by
 * the ViewModel so a configuration change never orphans an encoder.
 */
@Composable
fun VoiceRecorderBar(
    elapsedMs: Int,
    level: Float,
    maxMs: Int = VoiceRecorder.MAX_DURATION_MS,
    isUploading: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = (elapsedMs.toFloat() / maxMs).coerceIn(0f, 1f)
    val secondsLeft = ((maxMs - elapsedMs).coerceAtLeast(0) / 1000f)

    val pulse by rememberInfiniteTransition(label = "recPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MiniChatTheme.extra.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cancel
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = !isUploading, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel recording",
                tint = MiniChatTheme.extra.danger,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))

        // Live meter
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(MiniChatTheme.extra.danger.copy(alpha = pulse), CircleShape),
            )
            Spacer(Modifier.width(4.dp))
            WaveformBars(
                elapsedMs = elapsedMs,
                level = level,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSeconds(secondsLeft),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                color = if (progress > 0.8f) MiniChatTheme.extra.danger else MiniChatTheme.extra.textSecondary,
            )
        }

        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
                    ),
                )
                .clickable(enabled = !isUploading, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (isUploading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MiniChatColors.OnYellow,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "Send voice message",
                    tint = MiniChatColors.OnYellow,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }

}

/**
 * The moving bars behind the timer. Heights are derived from elapsed time so the
 * meter animates identically on every device without needing audio samples on
 * the UI thread.
 */
@Composable
private fun WaveformBars(
    elapsedMs: Int,
    level: Float,
    modifier: Modifier = Modifier,
    bars: Int = 18,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(
        modifier = modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(bars) { index ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 700 + index * 37,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            val base = 0.25f + 0.55f * phase
            val amplitude = (base * (0.65f + level.coerceIn(0f, 1f) * 0.7f))
                .coerceIn(0.15f, 1f)
            val active = index < (elapsedMs / 90).coerceAtMost(bars)

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + (20 * amplitude)).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) {
                            MiniChatColors.AmberSecondary
                        } else {
                            MiniChatTheme.extra.textSecondary.copy(alpha = 0.32f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun formatSeconds(seconds: Float): String {
    val whole = seconds.toInt()
    val tenths = ((seconds - whole) * 10).toInt().coerceIn(0, 9)
    return "%d.%ds".format(whole, tenths)
}

/** The microphone affordance shown in the composer when the field is empty. */
@Composable
fun MicButton(
    recording: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                when {
                    recording -> Brush.linearGradient(
                        listOf(
                            MiniChatTheme.extra.danger,
                            MiniChatTheme.extra.danger.copy(alpha = 0.82f),
                        ),
                    )

                    enabled -> Brush.linearGradient(
                        listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
                    )

                    else -> Brush.linearGradient(
                        listOf(
                            MiniChatTheme.extra.surfaceMuted,
                            MiniChatTheme.extra.surfaceMuted,
                        ),
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = if (recording) "Stop recording" else "Record a voice message",
            tint = when {
                recording -> androidx.compose.ui.graphics.Color.White
                enabled -> MiniChatColors.OnYellow
                else -> MiniChatTheme.extra.textSecondary
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

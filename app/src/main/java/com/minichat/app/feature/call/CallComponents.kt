package com.minichat.app.feature.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon

/**
 * Renders a LiveKit [VideoTrack] with the SDK's own `TextureViewRenderer`.
 *
 * The renderer is created with a private EGL context and released with the composable, so leaving
 * the call screen never leaks GL surfaces.
 */
@Composable
fun VideoTrackView(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
) {
    val eglBase = remember { EglBase.create() }
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(scalingType)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                renderer = this
            }
        },
        onRelease = { view ->
            runCatching { view.release() }
            renderer = null
        },
    )

    LaunchedEffect(track, mirror) {
        val target = renderer ?: return@LaunchedEffect
        runCatching {
            target.setMirror(mirror)
            target.setScalingType(scalingType)
            track?.addRenderer(target)
        }
    }

    DisposableEffect(track) {
        val target = renderer
        onDispose {
            if (track != null && target != null) {
                runCatching { track.removeRenderer(target) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { eglBase.release() }
        }
    }
}

/** Round call control: toggles keep their state visible, the hang-up button is always red. */
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    danger: Boolean = false,
    size: Dp = 62.dp,
    enabled: Boolean = true,
) {
    val background = when {
        danger -> Color(0xFFE5484D)
        active -> Color.White
        else -> Color.White.copy(alpha = 0.14f)
    }
    val tint = when {
        danger -> Color.White
        active -> Color(0xFF14161A)
        else -> Color.White
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

/** Pulsing halo behind the avatar while a call rings or connects. */
@Composable
fun RingingAvatar(
    displayName: String,
    username: String,
    avatarUrl: String?,
    size: Dp = 132.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "ring")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Canvas(modifier = Modifier.size(size * 1.7f)) {
            drawCircle(
                color = Color(0xFFFFD43B).copy(alpha = alpha),
                radius = this.size.minDimension / 2f * scale,
            )
        }
        com.minichat.app.design.components.MiniChatAvatar(
            displayName = displayName,
            username = username,
            avatarUrl = avatarUrl,
            size = size,
            borderWidth = 3.dp,
        )
    }
}

@Composable
fun CallStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
        color = Color.White.copy(alpha = 0.82f),
        modifier = modifier,
    )
}

@Composable
fun CallControlsRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Formats 00:07 / 12:34 / 1:02:03. */
fun formatCallDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

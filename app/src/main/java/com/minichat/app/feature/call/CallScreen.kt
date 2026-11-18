package com.minichat.app.feature.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors

/**
 * One screen for every call state: incoming, outgoing, connecting, active (voice or video) and the
 * post-call summary. The state machine itself lives in [CallViewModel].
 */
@Composable
fun CallScreen(
    onFinished: () -> Unit,
    onOpenChat: (conversationId: String) -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissions = remember(state.isVideo) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (state.isVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val audioGranted = granted[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
        if (audioGranted) {
            viewModel.onPermissionsGranted()
        }
    }

    LaunchedEffect(state.showPermissions, permissions) {
        if (state.showPermissions) {
            permissionLauncher.launch(permissions)
        }
    }

    // Coming back from the background can leave the camera released; re-publish on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.isOngoing) {
        when {
            state.phase == CallViewModel.Phase.Incoming -> viewModel.decline()
            state.phase == CallViewModel.Phase.Active -> onFinished()
            else -> viewModel.hangUp()
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == CallViewModel.Phase.Failed && state.call == null) {
            // Nothing to display at all: give the user a way out.
            onFinished()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        when (state.phase) {
            CallViewModel.Phase.Loading -> LoadingLayer(state)

            CallViewModel.Phase.Incoming -> IncomingLayer(state, viewModel)

            CallViewModel.Phase.Outgoing -> OutgoingLayer(state, viewModel)

            CallViewModel.Phase.Connecting -> ConnectingLayer(state, viewModel)

            CallViewModel.Phase.Active -> ActiveLayer(
                state = state,
                viewModel = viewModel,
                onFinished = onFinished,
            )

            CallViewModel.Phase.Ended -> EndedLayer(state, viewModel, onFinished, onOpenChat)

            CallViewModel.Phase.Failed -> FailedLayer(state, viewModel, onFinished)
        }

        state.error?.takeIf { state.phase != CallViewModel.Phase.Failed }?.let { error ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFE5484D))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun LoadingLayer(state: CallViewModel.UiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Connecting…",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f),
        )
        if (!state.callingConfigured) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Calling is not configured on the server yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun IncomingLayer(state: CallViewModel.UiState, viewModel: CallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = if (state.isVideo) "Incoming video call" else "Incoming voice call",
            style = MaterialTheme.typography.titleSmall,
            color = MiniChatColors.YellowPrimary,
        )
        Spacer(Modifier.height(26.dp))
        RingingAvatar(
            displayName = state.peerName.ifBlank { "Someone" },
            username = state.peerUsername,
            avatarUrl = state.peerAvatarUrl,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.peerName.ifBlank { "MINI CHAT" },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        CallStatusLine("is calling you…")

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallControlButton(
                icon = Icons.Rounded.CallEnd,
                label = "Decline",
                danger = true,
                size = 72.dp,
                onClick = viewModel::decline,
            )
            CallControlButton(
                icon = Icons.Rounded.Call,
                label = "Accept",
                active = true,
                size = 72.dp,
                onClick = viewModel::accept,
            )
        }
        Spacer(Modifier.height(46.dp))
    }
}

@Composable
private fun OutgoingLayer(state: CallViewModel.UiState, viewModel: CallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        RingingAvatar(
            displayName = state.peerName.ifBlank { "Someone" },
            username = state.peerUsername,
            avatarUrl = state.peerAvatarUrl,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = state.peerName.ifBlank { "MINI CHAT" },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        CallStatusLine(if (state.isVideo) "Video calling…" else "Calling…")

        Spacer(Modifier.weight(1f))

        CallControlButton(
            icon = Icons.Rounded.CallEnd,
            label = "Cancel",
            danger = true,
            size = 72.dp,
            onClick = viewModel::hangUp,
        )
        Spacer(Modifier.height(46.dp))
    }
}

@Composable
private fun ConnectingLayer(state: CallViewModel.UiState, viewModel: CallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))
        RingingAvatar(
            displayName = state.peerName.ifBlank { "Someone" },
            username = state.peerUsername,
            avatarUrl = state.peerAvatarUrl,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.peerName.ifBlank { "MINI CHAT" },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        CallStatusLine("Connecting…")
        Spacer(Modifier.weight(1f))
        CallControlButton(
            icon = Icons.Rounded.CallEnd,
            label = "End",
            danger = true,
            size = 72.dp,
            onClick = viewModel::hangUp,
        )
        Spacer(Modifier.height(46.dp))
    }
}

@Composable
private fun ActiveLayer(
    state: CallViewModel.UiState,
    viewModel: CallViewModel,
    onFinished: () -> Unit,
) {
    val showVideo = state.remoteVideoTrack != null

    Box(modifier = Modifier.fillMaxSize()) {
        if (showVideo) {
            VideoTrackView(
                track = state.remoteVideoTrack,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MiniChatAvatar(
                    displayName = state.peerName.ifBlank { "Someone" },
                    username = state.peerUsername,
                    avatarUrl = state.peerAvatarUrl,
                    size = 128.dp,
                    borderWidth = 3.dp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = state.peerName.ifBlank { "MINI CHAT" },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                CallStatusLine(
                    when {
                        state.reconnecting -> "Reconnecting…"
                        state.elapsedSeconds > 0 -> formatCallDuration(state.elapsedSeconds)
                        else -> "Connected"
                    },
                )
                if (state.isVideo && state.elapsedSeconds > 4) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VideocamOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Their camera is off",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Local preview
        if (state.localVideoTrack != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 14.dp, end = 14.dp)
                    .size(width = 104.dp, height = 148.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
            ) {
                VideoTrackView(
                    track = state.localVideoTrack,
                    mirror = state.isFrontCamera,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Top status bar over the video
        if (showVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = state.peerName.ifBlank { "MINI CHAT" },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.reconnecting) "Reconnecting…" else formatCallDuration(state.elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MiniChatColors.YellowPrimary,
                )
            }
        }

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CallControlsRow {
                CallControlButton(
                    icon = if (state.micEnabled) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                    label = if (state.micEnabled) "Mute" else "Unmute",
                    active = !state.micEnabled,
                    onClick = viewModel::toggleMicrophone,
                )
                CallControlButton(
                    icon = if (state.speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    label = "Speaker",
                    active = state.speakerOn,
                    onClick = viewModel::toggleSpeaker,
                )
                if (state.isVideo) {
                    CallControlButton(
                        icon = if (state.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        label = if (state.cameraEnabled) "Camera" else "Camera off",
                        active = !state.cameraEnabled,
                        onClick = viewModel::toggleCamera,
                    )
                    if (state.cameraEnabled) {
                        CallControlButton(
                            icon = Icons.Rounded.Cameraswitch,
                            label = "Flip",
                            onClick = viewModel::flipCamera,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            CallControlButton(
                icon = Icons.Rounded.CallEnd,
                label = "End",
                danger = true,
                size = 72.dp,
                onClick = viewModel::hangUp,
            )
        }
    }
}

@Composable
private fun EndedLayer(
    state: CallViewModel.UiState,
    viewModel: CallViewModel,
    onFinished: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    var closed by remember { mutableStateOf(false) }
    fun close(openChat: Boolean) {
        if (closed) return
        closed = true
        val conversationId = state.call?.conversationId
        onFinished()
        if (openChat && conversationId != null) onOpenChat(conversationId)
    }

    LaunchedEffect(Unit) {
        // Give the user a beat to read "Call ended" before the screen closes itself.
        kotlinx.coroutines.delay(2_500)
        close(openChat = false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MiniChatAvatar(
            displayName = state.peerName.ifBlank { "Someone" },
            username = state.peerUsername,
            avatarUrl = state.peerAvatarUrl,
            size = 104.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.peerName.ifBlank { "MINI CHAT" },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        CallStatusLine(
            when (state.call?.status) {
                com.minichat.app.domain.model.CallStatus.Declined -> "Call declined"
                com.minichat.app.domain.model.CallStatus.Missed -> "No answer"
                com.minichat.app.domain.model.CallStatus.Cancelled -> "Call cancelled"
                else -> if (state.elapsedSeconds > 0) {
                    "Call ended · ${formatCallDuration(state.elapsedSeconds)}"
                } else {
                    "Call ended"
                }
            },
        )
        Spacer(Modifier.height(34.dp))
        PrimaryButton(
            text = "Back to chat",
            onClick = { close(openChat = true) },
            fillWidth = false,
        )
    }
}

@Composable
private fun FailedLayer(
    state: CallViewModel.UiState,
    viewModel: CallViewModel,
    onFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MiniChatColors.YellowPrimary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Call couldn't start",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.error ?: "Check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            text = "Close",
            onClick = {
                viewModel.dismissError()
                onFinished()
            },
            fillWidth = false,
        )
    }
}

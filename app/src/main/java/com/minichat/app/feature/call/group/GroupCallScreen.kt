package com.minichat.app.feature.call.group

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.feature.call.CallControlButton
import com.minichat.app.feature.call.RingingAvatar
import com.minichat.app.feature.call.VideoTrackView
import com.minichat.app.feature.call.formatCallDuration

private data class CallTile(
    val key: String,
    val name: String,
    val username: String,
    val avatarUrl: String?,
    val isLocal: Boolean,
    val isHost: Boolean,
    val microphoneEnabled: Boolean,
    val videoTrack: io.livekit.android.room.track.VideoTrack?,
    val mirror: Boolean,
)

@Composable
fun GroupCallScreen(
    onFinished: () -> Unit,
    viewModel: GroupCallViewModel = hiltViewModel(),
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
        val audio = granted[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
        if (audio) viewModel.onPermissionsGranted()
    }

    LaunchedEffect(state.showPermissions, permissions) {
        if (state.showPermissions) permissionLauncher.launch(permissions)
    }

    BackHandler(enabled = state.phase == GroupCallViewModel.Phase.Incoming ||
        state.phase == GroupCallViewModel.Phase.Active ||
        state.phase == GroupCallViewModel.Phase.Connecting) {
        when {
            state.phase == GroupCallViewModel.Phase.Incoming -> viewModel.decline()
            state.joined -> if (state.isHost) viewModel.endForEveryone() else viewModel.leave()
            else -> onFinished()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        val myId = viewModel.myId
        when (state.phase) {
            GroupCallViewModel.Phase.Loading -> Text(
                text = "Opening the room…",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center),
            )

            GroupCallViewModel.Phase.Incoming -> GroupIncomingLayer(state, viewModel)

            GroupCallViewModel.Phase.Connecting -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                RingingAvatar(
                    displayName = state.call?.title.orEmpty().ifBlank { "Group call" },
                    username = "",
                    avatarUrl = null,
                    size = 110.dp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Joining…",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(24.dp))
                CallControlButton(
                    icon = Icons.Rounded.CallEnd,
                    label = "Cancel",
                    danger = true,
                    size = 68.dp,
                    onClick = { if (state.isHost) viewModel.endForEveryone() else viewModel.leave() },
                )
            }

            GroupCallViewModel.Phase.Active -> GroupActiveLayer(state, viewModel, myId)

            GroupCallViewModel.Phase.Ended -> GroupEndedLayer(state, onFinished)

            GroupCallViewModel.Phase.Failed -> GroupFailedLayer(state, viewModel, onFinished)
        }

        state.error?.takeIf { state.phase != GroupCallViewModel.Phase.Failed }?.let { error ->
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
private fun GroupIncomingLayer(state: GroupCallViewModel.UiState, viewModel: GroupCallViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(44.dp))
        Text(
            text = if (state.isVideo) "Group video call" else "Group voice call",
            style = MaterialTheme.typography.titleSmall,
            color = MiniChatColors.YellowPrimary,
        )
        Spacer(Modifier.height(22.dp))
        RingingAvatar(
            displayName = state.call?.title.orEmpty().ifBlank { "Group call" },
            username = "",
            avatarUrl = null,
            size = 120.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.call?.title.orEmpty().ifBlank { "Group call" },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append("You're invited")
                val host = state.participants.firstOrNull { it.isHost }
                if (host != null) append(" by ${host.displayName}")
                if (state.inCallCount > 0) append(" · ${state.inCallCount} already inside")
            },
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.participants.take(5).forEach { person ->
                Box(modifier = Modifier.clip(CircleShape)) {
                    MiniChatAvatar(
                        displayName = person.displayName,
                        username = person.username,
                        avatarUrl = person.avatarUrl,
                        size = 44.dp,
                        borderWidth = 2.dp,
                    )
                }
            }
        }

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
                label = "Join",
                active = true,
                size = 72.dp,
                onClick = viewModel::join,
            )
        }
        Spacer(Modifier.height(44.dp))
    }
}

@Composable
private fun GroupActiveLayer(
    state: GroupCallViewModel.UiState,
    viewModel: GroupCallViewModel,
    myId: String?,
) {
    val tiles = buildList {
        add(
            CallTile(
                key = "local",
                name = "You",
                username = "",
                avatarUrl = null,
                isLocal = true,
                isHost = state.isHost,
                microphoneEnabled = state.micEnabled,
                videoTrack = state.localVideoTrack,
                mirror = state.isFrontCamera,
            ),
        )
        state.participants
            .filter { it.status.isInCall && it.userId != myId }
            .forEach { person ->
                val peer = state.peers[person.userId]
                add(
                    CallTile(
                        key = person.userId,
                        name = person.displayName,
                        username = person.username,
                        avatarUrl = person.avatarUrl,
                        isLocal = false,
                        isHost = person.isHost,
                        microphoneEnabled = peer?.microphoneEnabled ?: true,
                        videoTrack = peer?.videoTrack,
                        mirror = false,
                    ),
                )
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.call?.title.orEmpty().ifBlank { "Group call" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatCallDuration(state.elapsedSeconds))
                        append(" · ")
                        append(if (state.inCallCount == 1) "1 person" else "${state.inCallCount} people")
                        if (state.isHost) append(" · host")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatColors.YellowPrimary,
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "${state.inCallCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tiles.size <= 2) 1 else 2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = tiles, key = { it.key }) { tile ->
                GroupCallTile(
                    tile = tile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (tiles.size <= 2) Modifier.aspectRatio(0.78f) else Modifier.aspectRatio(0.72f)),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Spacer(Modifier.height(18.dp))
            CallControlButton(
                icon = Icons.Rounded.CallEnd,
                label = if (state.isHost) "End for all" else "Leave",
                danger = true,
                size = 70.dp,
                onClick = { if (state.isHost) viewModel.endForEveryone() else viewModel.leave() },
            )
        }
    }
}

@Composable
private fun GroupCallTile(tile: CallTile, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1D23)),
    ) {
        if (tile.videoTrack != null) {
            VideoTrackView(
                track = tile.videoTrack,
                mirror = tile.mirror,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MiniChatAvatar(
                    displayName = tile.name,
                    username = tile.username,
                    avatarUrl = tile.avatarUrl,
                    size = 76.dp,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!tile.microphoneEnabled) {
                Icon(
                    imageVector = Icons.Rounded.MicOff,
                    contentDescription = "Muted",
                    tint = Color(0xFFE5484D),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (tile.isHost && !tile.isLocal) "${tile.name} · host" else tile.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupEndedLayer(state: GroupCallViewModel.UiState, onFinished: () -> Unit) {
    var closed by remember { mutableStateOf(false) }
    fun close() {
        if (closed) return
        closed = true
        onFinished()
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_000)
        close()
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
        Icon(
            imageVector = Icons.Rounded.CallEnd,
            contentDescription = null,
            tint = MiniChatColors.YellowPrimary,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Group call ended",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.elapsedSeconds > 0) {
                "It lasted ${formatCallDuration(state.elapsedSeconds)}"
            } else {
                "No one stayed on the call."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton(text = "Done", onClick = { close() }, fillWidth = false)
    }
}

@Composable
private fun GroupFailedLayer(
    state: GroupCallViewModel.UiState,
    viewModel: GroupCallViewModel,
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
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Couldn't join the call",
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
        Spacer(Modifier.height(24.dp))
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

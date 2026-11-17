package com.minichat.app.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.EmptyState
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatIconButton
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.domain.model.CallHistoryItem
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.GroupCallHistoryItem
import com.minichat.app.domain.model.CallType

/**
 * Call log for the signed-in user, backed by the `call_history()` RPC so every row arrives with
 * the other person's profile already joined.
 */
@Composable
fun CallHistoryScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenCall: (String) -> Unit,
    onNewGroupCall: () -> Unit = {},
    viewModel: CallHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val startedCallId by viewModel.startedCallId.collectAsStateWithLifecycle()

    LaunchedEffect(startedCallId) {
        startedCallId?.let {
            viewModel.consumeStartedCall()
            onOpenCall(it)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(
            title = "Calls",
            onBack = onBack,
            actions = {
                MiniChatIconButton(
                    icon = Icons.Rounded.Groups,
                    contentDescription = "New group call",
                    onClick = onNewGroupCall,
                    containerColor = MaterialTheme.colorScheme.surface,
                )
                Spacer(Modifier.width(4.dp))
                MiniChatIconButton(
                    icon = Icons.Rounded.Call,
                    contentDescription = "Refresh",
                    onClick = viewModel::refresh,
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            },
        )

        when {
            state.isLoading -> MiniChatLoader()

            state.error != null && state.items.isEmpty() -> InlineError(
                message = state.error!!,
                modifier = Modifier.padding(20.dp),
            )

            state.items.isEmpty() && state.groupItems.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Call,
                title = "No calls yet",
                message = "Start a voice or video call from any conversation — it will show up here.",
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = state.items, key = { it.call.id }) { item ->
                    CallHistoryRow(
                        item = item,
                        busy = state.redialling == item.call.conversationId,
                        onClick = { onOpenConversation(item.call.conversationId) },
                        onRedial = { viewModel.redial(item) },
                    )
                }

                if (state.groupItems.isNotEmpty()) {
                    item { GroupCallSectionHeader() }
                    items(items = state.groupItems, key = { "g-${it.call.id}" }) { group ->
                        GroupCallHistoryRow(item = group)
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(
    item: CallHistoryItem,
    busy: Boolean,
    onClick: () -> Unit,
    onRedial: () -> Unit,
) {
    val call = item.call
    val missed = !item.isOutgoing && (call.status == CallStatus.Missed || call.status == CallStatus.Declined)

    val (icon, tint) = when {
        missed -> Icons.Rounded.CallMissed to Color(0xFFE5484D)
        item.isOutgoing -> Icons.Rounded.CallMade to MiniChatColors.AmberSecondary
        else -> Icons.Rounded.CallReceived to Color(0xFF2FBF71)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = item.peerDisplayName,
            username = item.peerUsername,
            avatarUrl = item.peerAvatarUrl,
            size = 46.dp,
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.peerDisplayName.ifBlank { item.peerUsername },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (missed) Color(0xFFE5484D) else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = historySubtitle(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = TimeFormat.lastSeen(call.createdAt?.toString(), false),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }

        Spacer(Modifier.width(10.dp))
        if (busy) {
            MiniChatLoader(modifier = Modifier.size(28.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MiniChatIconButton(
                    icon = if (call.type.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                    contentDescription = "Call back",
                    onClick = onRedial,
                    containerColor = MaterialTheme.colorScheme.surface,
                    size = 40.dp,
                    iconSize = 18.dp,
                )
            }
        }
    }
}

@Composable
private fun GroupCallSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Groups,
            contentDescription = null,
            tint = MiniChatColors.YellowPrimary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Group calls",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun GroupCallHistoryRow(item: GroupCallHistoryItem) {
    val call = item.call
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (call.type.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = null,
                tint = MiniChatColors.YellowPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.title.ifBlank { "Group call" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (item.isHost) "You hosted" else "${item.hostName} hosted")
                    append(" · ${item.participantCount} invited")
                    val duration = call.connectedSeconds()
                    if (duration > 0) append(" · ${formatCallDuration(duration)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = TimeFormat.lastSeen(call.createdAt?.toString(), false),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

private fun historySubtitle(item: CallHistoryItem): String {
    val call = item.call
    val kind = if (call.type == CallType.Video) "Video" else "Voice"
    val status = when (call.status) {
        CallStatus.Ringing -> "no answer"
        CallStatus.Missed -> "missed"
        CallStatus.Declined -> if (item.isOutgoing) "declined" else "declined by you"
        CallStatus.Cancelled -> "cancelled"
        CallStatus.Failed -> "failed"
        CallStatus.Active -> "in progress"
        CallStatus.Ended -> call.connectedSeconds()?.let { formatCallDuration(it) } ?: "ended"
    }
    val direction = if (item.isOutgoing) "Outgoing" else "Incoming"
    return "$direction $kind · $status"
}

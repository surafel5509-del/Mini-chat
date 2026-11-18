package com.minichat.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.SeenReceipt

/**
 * "Seen by" panel: one row per member with their mini profile and whether they have read the
 * message. Direct chats get a two-row list, groups get the full roster.
 */
@Composable
fun SeenBySheet(
    message: Message,
    members: List<SeenReceipt>,
    myId: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sentAt = message.createdAt
    val others = members.filter { it.userId != myId }
    val seen = others.filter { member ->
        val readAt = member.lastReadAt
        sentAt == null || (readAt != null && !readAt.isBefore(sentAt.minusSeconds(1)))
    }
    val pending = others - seen.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.DoneAll,
                    contentDescription = null,
                    tint = MiniChatTheme.extra.success,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Message info",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "\"${message.content.take(90)}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sent ${TimeFormat.fullTimestamp(message.createdAt?.toString())}",
                style = MaterialTheme.typography.labelSmall,
                color = MiniChatTheme.extra.textSecondary,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "SEEN BY ${seen.size} OF ${others.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn {
                items(items = seen, key = { it.userId }) { member ->
                    MemberRow(member = member, seen = true)
                }
                if (seen.isEmpty()) {
                    item {
                        Text(
                            text = "Nobody has read this message yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MiniChatTheme.extra.textSecondary,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (pending.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "DELIVERED",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MiniChatTheme.extra.textSecondary,
                        )
                    }
                    items(items = pending, key = { "pending-${it.userId}" }) { member ->
                        MemberRow(member = member, seen = false)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MemberRow(member: SeenReceipt, seen: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = member.displayName,
            username = member.username,
            avatarUrl = member.avatarUrl,
            size = 34.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName.ifBlank { member.username },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (seen) {
                    member.lastReadAt?.let { "Read ${TimeFormat.conversationTime(it.toString())}" }
                        ?: "Read"
                } else {
                    "Delivered"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }
        Row(
            modifier = Modifier
                .background(
                    if (seen) MiniChatTheme.extra.success.copy(alpha = 0.16f) else MiniChatTheme.extra.surfaceMuted,
                    CircleShape,
                )
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (seen) "Seen" else "Sent",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (seen) MiniChatTheme.extra.success else MiniChatTheme.extra.textSecondary,
            )
        }
    }
}

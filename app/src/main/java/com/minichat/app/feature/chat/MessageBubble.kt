package com.minichat.app.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.MessageStatus
import com.minichat.app.domain.model.ReactionSummary
import java.time.Instant

private val outgoingShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)
private val incomingShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    myId: String,
    showSenderName: Boolean,
    peerName: String,
    peerReadAt: Instant?,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onReactionTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = message.isMine
    val bubbleColor = if (mine) MiniChatTheme.extra.bubbleOutgoing else MiniChatTheme.extra.bubbleIncoming
    val textColor = if (mine) MiniChatTheme.extra.bubbleOutgoingText else MiniChatTheme.extra.bubbleIncomingText
    val metaColor = if (mine) {
        MiniChatColors.OnYellow.copy(alpha = 0.62f)
    } else {
        MiniChatTheme.extra.textSecondary
    }

    val summaries = message.reactions
        .groupBy { it.emoji }
        .map { (emoji, reactions) ->
            ReactionSummary(
                emoji = emoji,
                count = reactions.size,
                reactedByMe = reactions.any { it.userId == myId },
            )
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            if (showSenderName && !mine) {
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MiniChatColors.AmberSecondary,
                    modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                )
            }

            Box(
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .animateContentSize(tween(180))
                    .background(bubbleColor, if (mine) outgoingShape else incomingShape)
                    .padding(horizontal = 15.dp, vertical = 10.dp),
            ) {
                Column {
                    message.replyPreview?.let { reply ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (mine) {
                                        MiniChatColors.OnYellow.copy(alpha = 0.10f)
                                    } else {
                                        MiniChatTheme.extra.textSecondary.copy(alpha = 0.12f)
                                    },
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Reply,
                                contentDescription = null,
                                tint = if (mine) MiniChatColors.OnYellow.copy(alpha = 0.8f) else MiniChatColors.AmberSecondary,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(
                                    text = reply.senderName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (mine) MiniChatColors.OnYellow.copy(alpha = 0.85f) else MiniChatColors.AmberSecondary,
                                )
                                Text(
                                    text = reply.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor.copy(alpha = 0.8f),
                                    maxLines = 2,
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }

                    if (message.isDeleted) {
                        Text(
                            text = "This message was deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.6f),
                        )
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        if (message.isEdited && !message.isDeleted) {
                            Text(
                                text = "edited ",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = metaColor,
                            )
                        }
                        Text(
                            text = TimeFormat.messageTime(message.createdAt?.toString()),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = metaColor,
                        )
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            MessageStatusIcon(
                                status = resolveStatus(message, peerReadAt),
                                onRetry = onRetry,
                            )
                        }
                    }
                }
            }

            if (summaries.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    summaries.forEach { summary ->
                        Row(
                            modifier = Modifier
                                .background(
                                    if (summary.reactedByMe) {
                                        MiniChatColors.YellowPrimary.copy(alpha = 0.30f)
                                    } else {
                                        MiniChatTheme.extra.surfaceMuted
                                    },
                                    CircleShape,
                                )
                                .combinedClickableCompat { onReactionTap(summary.emoji) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = summary.emoji, fontSize = 12.sp)
                            if (summary.count > 1) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = summary.count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MiniChatTheme.extra.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveStatus(message: Message, peerReadAt: Instant?): MessageStatus {
    if (message.status == MessageStatus.Sending || message.status == MessageStatus.Failed) return message.status
    val created = message.createdAt
    return if (created != null && peerReadAt != null && !peerReadAt.isBefore(created.minusSeconds(1))) {
        MessageStatus.Read
    } else {
        message.status
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus, onRetry: () -> Unit) {
    when (status) {
        MessageStatus.Sending -> Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = "Sending",
            tint = MiniChatColors.OnYellow.copy(alpha = 0.55f),
            modifier = Modifier.size(11.dp),
        )

        MessageStatus.Sent -> Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = "Sent",
            tint = MiniChatColors.OnYellow.copy(alpha = 0.65f),
            modifier = Modifier.size(12.dp),
        )

        MessageStatus.Delivered -> Icon(
            imageVector = Icons.Rounded.DoneAll,
            contentDescription = "Delivered",
            tint = MiniChatColors.OnYellow.copy(alpha = 0.65f),
            modifier = Modifier.size(13.dp),
        )

        MessageStatus.Read -> Icon(
            imageVector = Icons.Rounded.DoneAll,
            contentDescription = "Read",
            tint = ColorReadTick,
            modifier = Modifier.size(13.dp),
        )

        MessageStatus.Failed -> Box(
            modifier = Modifier.combinedClickableCompat(onRetry),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Failed — tap to retry",
                tint = MiniChatTheme.extra.danger,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private val ColorReadTick = androidx.compose.ui.graphics.Color(0xFF2B6BFF)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = null))

@Composable
fun DayDivider(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
            modifier = Modifier
                .background(MiniChatTheme.extra.surfaceMuted, CircleShape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun TypingBubble(peerName: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .background(MiniChatTheme.extra.bubbleIncoming, incomingShape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.minichat.app.design.components.TypingDots(dotSize = 6.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$peerName is typing",
                style = MaterialTheme.typography.labelSmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }
    }
}

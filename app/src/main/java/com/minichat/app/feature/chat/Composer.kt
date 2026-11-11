package com.minichat.app.feature.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message

/**
 * MINI CHAT composer: grows with the text, keeps a yellow send affordance that is impossible to
 * miss, and shows reply / edit context above the field.
 */
@Composable
fun MessageComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    replyTo: Message?,
    editing: Message?,
    onCancelContext: () -> Unit,
    onOpenEmoji: () -> Unit,
    enabled: Boolean,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = text.isNotBlank() && enabled
    // Telegram behaviour: with an empty field the send button becomes a
    // microphone, so recording is always one tap away and never hidden in a menu.
    val showMic = text.isBlank() && editing == null && enabled
    val sendScale by animateFloatAsState(
        targetValue = if (canSend) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "sendScale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (text.contains('\n')) 20.dp else 26.dp,
        label = "composerCorner",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (replyTo != null || editing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editing != null) "Editing message" else "Replying to ${replyTo?.senderName?.ifBlank { "message" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MiniChatColors.AmberSecondary,
                    )
                    if (editing == null && replyTo != null) {
                        Text(
                            text = replyTo.content.take(90),
                            style = MaterialTheme.typography.bodySmall,
                            color = MiniChatTheme.extra.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelContext),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel",
                        tint = MiniChatTheme.extra.textSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 9.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (editing != null) "Update your message" else "Message",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MiniChatTheme.extra.textSecondary,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MiniChatColors.AmberSecondary),
                        maxLines = 6,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenEmoji),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEmotions,
                        contentDescription = "Emoji",
                        tint = MiniChatTheme.extra.textSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .scale(sendScale)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend || showMic) {
                            Brush.linearGradient(listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary))
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    MiniChatTheme.extra.surfaceMuted,
                                    MiniChatTheme.extra.surfaceMuted,
                                ),
                            )
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        enabled = canSend || showMic,
                        onClick = if (showMic) onStartRecording else onSend,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        editing != null -> Icons.Rounded.Edit
                        showMic -> Icons.Rounded.Mic
                        else -> Icons.Rounded.Send
                    },
                    contentDescription = when {
                        editing != null -> "Save"
                        showMic -> "Record a voice message"
                        else -> "Send"
                    },
                    tint = if (canSend || showMic) {
                        MiniChatColors.OnYellow
                    } else {
                        MiniChatTheme.extra.textSecondary
                    },
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        if (!enabled) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "You can no longer send messages to this person.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatTheme.extra.textSecondary,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
        }
    }
}

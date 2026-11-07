package com.minichat.app.feature.chat

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message

private val emojiGroups: List<Pair<String, List<String>>> = listOf(
    "Smileys" to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉", "😊", "😇",
        "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜", "🤪",
        "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏",
        "😒", "🙄", "😬", "😮‍💨", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒",
        "🥵", "🥶", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐", "😕", "😟", "🙁",
    ),
    "Gestures" to listOf(
        "👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘",
        "🤙", "👈", "👉", "👆", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "💪",
    ),
    "Hearts" to listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕",
        "💞", "💓", "💗", "💖", "💘", "💝", "💟", "✨", "⭐", "🌟", "💫", "🔥",
    ),
    "Life" to listOf(
        "🎉", "🎊", "🎁", "🎈", "☕", "🍕", "🍔", "🍟", "🌮", "🍩", "🍪", "🎂",
        "🌸", "🌻", "🌙", "☀️", "⚡", "🌈", "💡", "📌", "📎", "✅", "❌", "⚽",
    ),
)

private val quickReactions = listOf("❤️", "😂", "👍", "🔥", "🙏", "😮", "😢", "🎉")

@Composable
fun EmojiPickerSheet(
    onEmoji: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var groupIndex by remember { mutableStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = "Emoji",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 22.dp, bottom = 10.dp),
            )

            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(emojiGroups) { (name, _) ->
                    val index = emojiGroups.indexOfFirst { it.first == name }
                    val selected = index == groupIndex
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (selected) MiniChatColors.YellowPrimary.copy(alpha = 0.25f)
                                else MiniChatTheme.extra.surfaceMuted,
                            )
                            .clickable { groupIndex = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(260.dp),
            ) {
                items(emojiGroups[groupIndex].second) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onEmoji(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageActionsSheet(
    message: Message,
    isMine: Boolean,
    onReaction: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                quickReactions.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MiniChatTheme.extra.surfaceMuted)
                            .clickable { onReaction(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 20.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (!message.isDeleted) {
                ActionRow(icon = Icons.Rounded.Reply, label = "Reply", onClick = onReply)
                ActionRow(icon = Icons.Rounded.ContentCopy, label = "Copy text", onClick = onCopy)
                ActionRow(icon = Icons.Rounded.Forward, label = "Forward", onClick = onForward)
                if (isMine) {
                    ActionRow(icon = Icons.Rounded.Edit, label = "Edit", onClick = onEdit)
                    ActionRow(
                        icon = Icons.Rounded.Delete,
                        label = "Delete",
                        onClick = onDelete,
                        destructive = true,
                    )
                }
            }
            ActionRow(icon = Icons.Rounded.Info, label = "Message info", onClick = onInfo)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    SettingsRow(
        icon = icon,
        title = label,
        onClick = onClick,
        iconTint = if (destructive) MiniChatTheme.extra.danger else MiniChatColors.OnYellow,
        iconBackground = if (destructive) {
            MiniChatTheme.extra.danger.copy(alpha = 0.14f)
        } else {
            MiniChatColors.YellowPrimary.copy(alpha = 0.22f)
        },
        titleColor = if (destructive) MiniChatTheme.extra.danger else MaterialTheme.colorScheme.onSurface,
    )
}

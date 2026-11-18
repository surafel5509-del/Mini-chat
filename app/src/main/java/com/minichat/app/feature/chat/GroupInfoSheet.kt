package com.minichat.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.ConfirmDialog
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.ConversationTheme
import com.minichat.app.domain.model.SeenReceipt

/**
 * Everything about a group chat: its look (wallpaper + bubble colour), its name and its members.
 *
 * The same sheet is used for a one-to-one thread, where only the wallpaper and bubble colour make
 * sense — group management rows stay hidden.
 */
@Composable
fun GroupInfoSheet(
    title: String,
    theme: ConversationTheme?,
    members: List<SeenReceipt>,
    myId: String,
    saving: Boolean,
    onUpdateTheme: (
        wallpaper: String?,
        bubbleColor: String?,
        accentColor: String?,
        title: String?,
    ) -> Unit,
    onLeaveGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(theme?.title) { mutableStateOf(theme?.title.orEmpty()) }
    var confirmLeave by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MiniChatColors.YellowPrimary,
                                    MiniChatColors.AmberSecondary,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                        color = MiniChatColors.OnYellow,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${members.size} members",
                        style = MaterialTheme.typography.labelMedium,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                }
                if (theme?.canEdit != false) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { renaming = !renaming }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Rename",
                            tint = MiniChatColors.AmberSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (renaming) {
                Spacer(Modifier.height(12.dp))
                MiniChatTextField(
                    value = newName,
                    onValueChange = { newName = it.take(60) },
                    label = "Group name",
                    placeholder = "Weekend plans",
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "Save name",
                    loading = saving,
                    onClick = {
                        onUpdateTheme(null, null, null, newName.trim())
                        renaming = false
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel(icon = true, text = "Wallpaper")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items = ChatWallpapers, key = { it.id }) { preset ->
                    val selected = (theme?.wallpaper ?: "default") == preset.id
                    WallpaperSwatch(
                        presetColors = preset.colors,
                        label = preset.label,
                        selected = selected,
                        onClick = {
                            onUpdateTheme(
                                if (preset.id == "default") "" else preset.id,
                                null,
                                null,
                                null,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel(icon = true, text = "My message colour")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatBubbleColors.forEach { preset ->
                    val selected = theme?.bubbleColor == preset.value
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(preset.color ?: MiniChatTheme.extra.bubbleOutgoing)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MiniChatTheme.extra.divider
                                },
                                shape = CircleShape,
                            )
                            .clickable {
                                onUpdateTheme(null, preset.value ?: "", null, null)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = textColorOn(preset.color ?: MiniChatColors.YellowPrimary),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(icon = false, text = "Members")
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(items = members, key = { it.userId }) { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniChatAvatar(
                            displayName = member.displayName,
                            username = member.username,
                            avatarUrl = member.avatarUrl,
                            size = 38.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (member.userId == myId) {
                                    "${member.displayName.ifBlank { member.username }} (you)"
                                } else {
                                    member.displayName.ifBlank { member.username }
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = member.lastReadAt?.let {
                                    "Seen ${TimeFormat.conversationTime(it.toString())}"
                                } ?: "Hasn't opened the chat yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MiniChatTheme.extra.textSecondary,
                            )
                        }
                        if (!member.role.isNullOrBlank() && !member.role.equals("member", true)) {
                            Text(
                                text = member.role!!.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MiniChatColors.AmberSecondary,
                            )
                        }
                    }
                }
            }

            if (theme?.isGroup == true) {
                Spacer(Modifier.height(10.dp))
                SettingsRow(
                    icon = Icons.Rounded.ExitToApp,
                    title = "Leave group",
                    onClick = { confirmLeave = true },
                    iconTint = MiniChatTheme.extra.danger,
                    iconBackground = MiniChatTheme.extra.danger.copy(alpha = 0.14f),
                    titleColor = MiniChatTheme.extra.danger,
                )
            } else {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = "Wallpaper and colours are saved for this chat",
                    onClick = null,
                    showChevron = false,
                )
            }
            Spacer(Modifier.height(26.dp))
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave this group?",
            message = "You'll stop receiving its messages. The other members stay in the group.",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                onLeaveGroup()
                onDismiss()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String, icon: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MiniChatColors.AmberSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

@Composable
private fun WallpaperSwatch(
    presetColors: List<Color>,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 62.dp)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (presetColors.isEmpty()) {
                        Modifier.background(MiniChatTheme.extra.surfaceMuted)
                    } else {
                        Modifier.background(Brush.verticalGradient(presetColors))
                    },
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MiniChatColors.YellowPrimary else MiniChatTheme.extra.divider,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

package com.minichat.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.EmptyState
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatIconButton
import com.minichat.app.design.components.MiniChatMark
import com.minichat.app.design.components.MiniChatSearchField
import com.minichat.app.design.components.NoteStoryRow
import com.minichat.app.design.components.FriendNoteSheetContent
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.components.ShimmerRow
import com.minichat.app.design.components.TypingDots
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.FriendNote
import com.minichat.app.domain.model.MessagePreview
import com.minichat.app.feature.notes.NoteComposerSheet

@Composable
fun HomeScreen(
    onOpenConversation: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The "+" opens a chooser: a direct chat or a new group. Both are one tap
    // from the inbox without needing a menu buried in a settings screen.
    var showCreateMenu by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var actionSheetFor by remember { mutableStateOf<ConversationSummary?>(null) }
    var showNoteComposer by remember { mutableStateOf(false) }
    var viewingNote by remember { mutableStateOf<FriendNote?>(null) }

    LaunchedEffect(state.conversations) {
        state.conversations.firstOrNull()?.let { viewModel.clearNotification(it.id) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                displayName = state.profile?.displayName ?: "You",
                username = state.profile?.username ?: "",
                avatarUrl = state.profile?.avatarUrl,
                searchVisible = searchVisible,
                onToggleSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) viewModel.onQueryChange("")
                },
                onOpenProfile = onOpenProfile,
                onOpenSettings = onOpenSettings,
            )

            AnimatedVisibility(
                visible = searchVisible,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
            ) {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    MiniChatSearchField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "Search conversations",
                        leadingIcon = Icons.Rounded.Search,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading && state.conversations.isEmpty() -> {
                        Column {
                            repeat(6) { ShimmerRow() }
                        }
                    }

                    state.isEmpty -> {
                        EmptyState(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            title = if (state.query.isBlank()) "No conversations yet" else "Nothing matches",
                            message = if (state.query.isBlank()) {
                                "Start your first chat — tap the yellow button and find someone to talk to."
                            } else {
                                "Try a different name or username."
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 110.dp),
                        ) {
                            if (state.hasNotes && state.query.isBlank()) {
                                item(key = "notes-row") {
                                    NoteStoryRow(
                                        myNote = state.myNote,
                                        myDisplayName = state.profile?.displayName ?: "You",
                                        myUsername = state.profile?.username.orEmpty(),
                                        myAvatarUrl = state.profile?.avatarUrl,
                                        friends = state.friendNotes,
                                        onOpenComposer = { showNoteComposer = true },
                                        onOpenFriend = { viewingNote = it },
                                    )
                                }
                            }

                            items(items = state.visible, key = { it.id }) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    typing = state.typingByConversation[conversation.id]?.isNotEmpty() == true,
                                    onClick = { onOpenConversation(conversation.id) },
                                    onLongClick = { actionSheetFor = conversation },
                                )
                            }
                        }
                    }
                }
            }
        }

        NewChatFab(
            onClick = { showCreateMenu = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 22.dp),
        )

        state.error?.let { error ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 90.dp),
            ) {
                com.minichat.app.design.components.InlineError(message = error)
            }
        }
    }

    if (showCreateMenu) {
        ModalBottomSheet(
            onDismissRequest = { showCreateMenu = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = "Start something",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
                CreateMenuRow(
                    title = "New chat",
                    subtitle = "Message one person",
                    icon = Icons.Rounded.ChatBubbleOutline,
                    onClick = {
                        showCreateMenu = false
                        onNewChat()
                    },
                )
                CreateMenuRow(
                    title = "New group",
                    subtitle = "Bring a few people together",
                    icon = Icons.Rounded.Groups,
                    onClick = {
                        showCreateMenu = false
                        onNewGroup()
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showNoteComposer) {
        NoteComposerSheet(
            existing = state.myNote,
            displayName = state.profile?.displayName ?: "You",
            username = state.profile?.username.orEmpty(),
            avatarUrl = state.profile?.avatarUrl,
            isSaving = state.isSavingNote,
            error = state.noteError,
            onDismiss = {
                showNoteComposer = false
                viewModel.dismissNoteError()
            },
            onSave = { body, minutes ->
                viewModel.saveNote(body, minutes)
                showNoteComposer = false
            },
            onDelete = {
                viewModel.deleteNote()
                showNoteComposer = false
            },
        )
    }

    viewingNote?.let { friend ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewingNote = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            FriendNoteSheetContent(
                friend = friend,
                onDismiss = { viewingNote = null },
                onReply = {
                    viewingNote = null
                    val existing = state.conversations.firstOrNull { it.peer?.id == friend.author.id }
                    if (existing != null) {
                        onOpenConversation(existing.id)
                    } else {
                        onNewChat()
                    }
                },
            )
        }
    }

    actionSheetFor?.let { conversation ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetFor = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 22.dp, bottom = 8.dp),
                )
                SettingsRow(
                    icon = Icons.Rounded.PushPin,
                    title = if (conversation.isPinned) "Unpin conversation" else "Pin conversation",
                    onClick = {
                        viewModel.togglePinned(conversation)
                        actionSheetFor = null
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.NotificationsOff,
                    title = if (conversation.isMuted) "Unmute notifications" else "Mute notifications",
                    onClick = {
                        viewModel.toggleMuted(conversation)
                        actionSheetFor = null
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.DoneAll,
                    title = "Mark as read",
                    onClick = {
                        viewModel.markRead(conversation)
                        actionSheetFor = null
                    },
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(
    displayName: String,
    username: String,
    avatarUrl: String?,
    searchVisible: Boolean,
    onToggleSearch: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniChatMark(size = 38.dp, cornerPercent = 32)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MINI CHAT",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Fast. Simple. Connected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
            MiniChatIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = "Search",
                onClick = onToggleSearch,
                containerColor = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(6.dp))
            MiniChatIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
                containerColor = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickableCompat(onClick = onOpenProfile),
            ) {
                MiniChatAvatar(
                    displayName = displayName,
                    username = username,
                    avatarUrl = avatarUrl,
                    size = 40.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = null))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    typing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Groups never show a presence dot: twelve people are not "online" as one.
    val online = !conversation.isGroup &&
        (TimeFormat.isOnline(conversation.peer?.lastSeenAt?.toString(), thresholdSeconds = 90) ||
            isPeerRecentlyOnline(conversation))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = conversation.displayName,
            username = conversation.peer?.username.orEmpty(),
            avatarUrl = conversation.avatarUrl,
            size = 54.dp,
            online = online,
            showOnlineIndicator = !conversation.isGroup,
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isGroup) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (conversation.memberCount == 1) {
                            "1 member"
                        } else {
                            "${conversation.memberCount} members"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                }
                if (conversation.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        tint = MiniChatColors.AmberSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (conversation.isMuted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MiniChatTheme.extra.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))

            if (typing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots(dotSize = 5.dp, color = MiniChatColors.AmberSecondary)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "typing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatColors.AmberSecondary,
                    )
                }
            } else {
                Text(
                    text = groupPreview(conversation),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conversation.unreadCount > 0) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MiniChatTheme.extra.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = TimeFormat.conversationTime(conversation.lastMessage?.sentAt?.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = if (conversation.unreadCount > 0) {
                    MiniChatColors.AmberSecondary
                } else {
                    MiniChatTheme.extra.textSecondary
                },
            )
            Spacer(Modifier.height(6.dp))
            if (conversation.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(MiniChatColors.YellowPrimary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (conversation.unreadCount > 9) "9+" else conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MiniChatColors.OnYellow,
                    )
                }
            } else if (conversation.lastMessage?.isMine == true) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Delivered",
                    tint = MiniChatTheme.extra.textSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private fun isPeerRecentlyOnline(conversation: ConversationSummary): Boolean {
    val lastSeen = conversation.peer?.lastSeenAt ?: return false
    return java.time.Duration.between(lastSeen, java.time.Instant.now()).seconds <= 90
}

private fun previewText(preview: MessagePreview?): String {
    if (preview == null) return "Say hello 👋"
    val body = when {
        preview.isDeleted -> "This message was deleted"
        preview.text.isBlank() -> "New message"
        else -> preview.text
    }
    return if (preview.isMine) "You: $body" else body
}

@Composable
private fun NewChatFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(22.dp), ambientColor = MiniChatColors.YellowPrimary, spotColor = MiniChatColors.YellowPrimary)
            .clip(RoundedCornerShape(22.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
                ),
            )
            .combinedClickableCompat(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MiniChatColors.OnYellow,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = "New chat",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MiniChatColors.OnYellow,
        )
    }
}

/** A row in the "+" chooser sheet. */
@Composable
private fun CreateMenuRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MiniChatColors.YellowPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiniChatColors.AmberSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extra.textSecondary,
            )
        }
    }
}

/**
 * Inbox preview line.
 *
 * Groups lead with the sender so "Sam: see you at 7" reads correctly, and media
 * messages get a label instead of an empty string.
 */
private fun groupPreview(conversation: ConversationSummary): String {
    val message = conversation.lastMessage ?: return "No messages yet"
    val body = when {
        message.isDeleted -> "Message deleted"
        message.messageType == com.minichat.app.domain.model.MessageType.Voice ->
            "\uD83C\uDF99 Voice message · ${formatVoiceLength(message.durationMs)}"

        message.text.isBlank() -> "Attachment"
        else -> message.text
    }

    if (!conversation.isGroup || message.isMine) {
        return if (conversation.isGroup && message.isMine) "You: $body" else body
    }
    val sender = message.senderName?.takeIf { it.isNotBlank() } ?: return body
    return "${sender.substringBefore(' ')}: $body"
}

private fun formatVoiceLength(ms: Int?): String {
    val seconds = ((ms ?: 0) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

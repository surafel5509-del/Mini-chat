package com.minichat.app.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.ConfirmDialog
import com.minichat.app.design.components.EmptyState
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MessageInfoDialog
import com.minichat.app.domain.model.ConversationTheme
import com.minichat.app.domain.model.SeenReceipt
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.domain.model.CallType
import com.minichat.app.design.components.MiniChatIconButton
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Message

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenCall: (String) -> Unit,
    onStartGroupCall: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showEmojiSheet by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    var showConversationSheet by remember { mutableStateOf(false) }
    var showGroupInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Message?>(null) }
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val outgoingCall by viewModel.outgoingCall.collectAsStateWithLifecycle()

    LaunchedEffect(state.groupLeft) {
        if (state.groupLeft) onBack()
    }

    LaunchedEffect(outgoingCall?.id) {
        outgoingCall?.let {
            viewModel.consumeOutgoingCall()
            onOpenCall(it.id)
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onScreenStarted()
        onDispose { viewModel.onScreenDisposed() }
    }

    // Keep the newest message in view without fighting the user while they scroll up.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.lastIndex) }
        }
    }

    // Older pages load when the top of the list is reached.
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && state.hasMoreHistory && !state.isLoading) {
            viewModel.loadMessages()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            displayName = state.conversation?.displayName ?: "Conversation",
            subtitle = state.subtitle,
            avatarUrl = state.conversation?.avatar,
            username = state.conversation?.subtitleHandle.orEmpty(),
            online = !state.isGroup &&
                TimeFormat.isOnline(state.conversation?.peer?.lastSeenAt?.toString()),
            peerTyping = state.peerTyping || state.typingNames.isNotEmpty(),
            isGroup = state.isGroup,
            onBack = onBack,
            onOpenProfile = {
                if (state.isGroup) {
                    showGroupInfo = true
                } else {
                    state.conversation?.peer?.id?.let(onOpenProfile)
                }
            },
            onMore = { showConversationSheet = true },
            onVoiceCall = { viewModel.startCall(CallType.Voice) },
            onVideoCall = { viewModel.startCall(CallType.Video) },
        )

        ChatWallpaper(wallpaperId = state.theme?.wallpaper, modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.messages.isEmpty() -> MiniChatLoader()

                state.messages.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Info,
                    title = "Say hello",
                    message = "This is the start of your conversation with ${state.conversation?.displayName ?: "them"}. Messages are private to the two of you.",
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        items(items = state.messages, key = { it.id }) { message ->
                            val index = state.messages.indexOf(message)
                            val previous = state.messages.getOrNull(index - 1)
                            val showsDayHeader = previous == null ||
                                TimeFormat.dayKey(previous.createdAt?.toString()) !=
                                TimeFormat.dayKey(message.createdAt?.toString())

                            Column {
                                if (showsDayHeader) {
                                    DayDivider(TimeFormat.dayHeader(message.createdAt?.toString()))
                                }
                                MessageBubble(
                                    message = message,
                                    myId = state.myId,
                                    showSenderName = state.isGroup,
                                    peerName = message.senderName.ifBlank {
                                        state.conversation?.displayName.orEmpty()
                                    },
                                    peerReadAt = state.peerReadAt,
                                    onLongPress = { actionMessage = message },
                                    onRetry = { viewModel.retry(message) },
                                    onReactionTap = { emoji -> viewModel.toggleReaction(message, emoji) },
                                    seenProfiles = state.seenBy(message),
                                    onSeenClick = { viewModel.openSeenSheet(message) },
                                    bubbleColorOverride = parseHexColor(state.theme?.bubbleColor),
                                )
                            }
                        }
                        if (state.typingNames.isNotEmpty() || state.peerTyping) {
                            item {
                                TypingBubble(
                                    peerName = state.typingLabel.ifBlank {
                                        state.conversation?.displayName.orEmpty()
                                    },
                                    showLabel = state.isGroup,
                                )
                            }
                        }
                    }
                }
            }

            if (state.isLoadingOlder) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                ) {
                    com.minichat.app.design.components.TypingDots(dotSize = 6.dp)
                }
            }
        }

        state.error?.let { error ->
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                InlineError(message = error)
            }
        }

        MessageComposer(
            text = state.composerText,
            onTextChange = viewModel::onComposerTextChange,
            onSend = viewModel::send,
            replyTo = state.replyTo,
            editing = state.editing,
            onCancelContext = viewModel::cancelReplyOrEdit,
            onOpenEmoji = { showEmojiSheet = true },
            enabled = !state.isBlocked,
        )
    }

    if (showEmojiSheet) {
        EmojiPickerSheet(
            onEmoji = { emoji ->
                viewModel.onComposerTextChange(state.composerText + emoji)
                showEmojiSheet = false
            },
            onDismiss = { showEmojiSheet = false },
        )
    }

    actionMessage?.let { message ->
        MessageActionsSheet(
            message = message,
            isMine = message.isMine,
            onReaction = { emoji ->
                viewModel.toggleReaction(message, emoji)
                actionMessage = null
            },
            onReply = {
                viewModel.startReply(message)
                actionMessage = null
            },
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("message", message.content))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                actionMessage = null
            },
            onForward = {
                viewModel.loadForwardTargets()
                forwardMessage = message
                actionMessage = null
            },
            onEdit = {
                viewModel.startEdit(message)
                actionMessage = null
            },
            onDelete = {
                confirmDelete = message
                actionMessage = null
            },
            onInfo = {
                viewModel.showInfo(message)
                actionMessage = null
            },
            onDismiss = { actionMessage = null },
        )
    }

    forwardMessage?.let { message ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { forwardMessage = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Forward to",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 22.dp, bottom = 12.dp),
                )
                if (forwardTargets.isEmpty()) {
                    Text(
                        text = "No other conversations yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MiniChatTheme.extra.textSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                forwardTargets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.forward(message, target.id)
                                forwardMessage = null
                                Toast.makeText(context, "Forwarded", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniChatAvatar(
                            displayName = target.displayName,
                            username = target.peer?.username.orEmpty(),
                            avatarUrl = target.peer?.avatarUrl,
                            size = 42.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    if (showGroupInfo) {
        GroupInfoSheet(
            title = state.conversation?.displayName ?: "This chat",
            theme = state.theme,
            members = state.members,
            myId = state.myId,
            saving = state.isSavingTheme,
            onUpdateTheme = { wallpaper, bubble, accent, title ->
                viewModel.updateTheme(wallpaper, bubble, accent, title)
            },
            onLeaveGroup = viewModel::leaveGroup,
            onStartCall = {
                showGroupInfo = false
                onStartGroupCall(viewModel.conversationId)
            },
            onDismiss = { showGroupInfo = false },
        )
    }

    state.seenSheetFor?.let { message ->
        SeenBySheet(
            message = message,
            members = state.members,
            myId = state.myId,
            onDismiss = viewModel::closeSeenSheet,
        )
    }

    if (showConversationSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showConversationSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                if (state.isGroup) {
                    SettingsRow(
                        icon = Icons.Rounded.Groups,
                        title = "Group info, wallpaper and colours",
                        onClick = {
                            showConversationSheet = false
                            showGroupInfo = true
                        },
                    )
                } else {
                    SettingsRow(
                        icon = Icons.Rounded.Info,
                        title = "View profile",
                        onClick = {
                            showConversationSheet = false
                            state.conversation?.peer?.id?.let(onOpenProfile)
                        },
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = "Wallpaper and colours",
                        onClick = {
                            showConversationSheet = false
                            showGroupInfo = true
                        },
                    )
                }
                SettingsRow(
                    icon = Icons.Rounded.PushPin,
                    title = if (state.conversation?.isPinned == true) "Unpin conversation" else "Pin conversation",
                    onClick = { showConversationSheet = false },
                )
                SettingsRow(
                    icon = Icons.Rounded.NotificationsOff,
                    title = "Mute notifications",
                    onClick = { showConversationSheet = false },
                )
                if (!state.isGroup) SettingsRow(
                    icon = Icons.Rounded.Block,
                    title = "Block this person",
                    onClick = { showConversationSheet = false },
                    iconTint = MiniChatTheme.extra.danger,
                    iconBackground = MiniChatTheme.extra.danger.copy(alpha = 0.14f),
                    titleColor = MiniChatTheme.extra.danger,
                )
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = "Clear chat",
                    onClick = { showConversationSheet = false },
                    iconTint = MiniChatTheme.extra.danger,
                    iconBackground = MiniChatTheme.extra.danger.copy(alpha = 0.14f),
                    titleColor = MiniChatTheme.extra.danger,
                )
            }
        }
    }

    confirmDelete?.let { message ->
        ConfirmDialog(
            title = "Delete message?",
            message = "This message will be removed for both of you.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.deleteMessage(message)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }

    state.infoMessage?.let { message ->
        MessageInfoDialog(
            title = "Message info",
            lines = listOf(
                "Sent" to TimeFormat.fullTimestamp(message.createdAt?.toString()),
                "Edited" to if (message.isEdited) "Yes" else "No",
                "Status" to message.status.name,
                "Reactions" to if (message.reactions.isEmpty()) "None" else "${message.reactions.size}",
            ),
            onDismiss = viewModel::hideInfo,
        )
    }
}

@Composable
private fun ChatTopBar(
    displayName: String,
    subtitle: String,
    avatarUrl: String?,
    username: String,
    online: Boolean,
    peerTyping: Boolean,
    isGroup: Boolean,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onMore: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniChatIconButton(
                icon = Icons.Rounded.ArrowBackIosNew,
                contentDescription = "Back",
                onClick = onBack,
                containerColor = MaterialTheme.colorScheme.surface,
                iconSize = 17.dp,
                size = 40.dp,
            )
            Spacer(Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenProfile)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniChatAvatar(
                    displayName = displayName,
                    username = username,
                    avatarUrl = avatarUrl,
                    size = 40.dp,
                    online = online,
                    showOnlineIndicator = !isGroup,
                )
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedVisibility(
                        visible = peerTyping,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = if (isGroup) "someone is typing…" else "typing…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MiniChatColors.AmberSecondary,
                        )
                    }
                    if (!peerTyping) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MiniChatTheme.extra.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            MiniChatIconButton(
                icon = Icons.Rounded.Call,
                contentDescription = "Voice call",
                onClick = onVoiceCall,
                containerColor = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(2.dp))
            MiniChatIconButton(
                icon = Icons.Rounded.Videocam,
                contentDescription = "Video call",
                onClick = onVideoCall,
                containerColor = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(2.dp))
            MiniChatIconButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = "More",
                onClick = onMore,
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiniChatTheme.extra.divider.copy(alpha = 0.6f)),
        )
    }
}

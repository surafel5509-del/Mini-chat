package com.minichat.app.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.datastore.UserPreferences
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.ConversationTheme
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.SeenReceipt
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.MessageStatus
import com.minichat.app.domain.repository.CallRepository
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.MessageEvent
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.domain.repository.RealtimeRepository
import com.minichat.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import com.minichat.app.core.util.onSuccess
import com.minichat.app.core.util.onFailure

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val profileRepository: ProfileRepository,
    private val realtimeRepository: RealtimeRepository,
    private val userPreferences: UserPreferences,
    private val callRepository: CallRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val conversation: ConversationSummary? = null,
        val messages: List<Message> = emptyList(),
        val myId: String = "",
        val composerText: String = "",
        val replyTo: Message? = null,
        val editing: Message? = null,
        val peerTyping: Boolean = false,
        val peerReadAt: Instant? = null,
        val error: String? = null,
        val isLoadingOlder: Boolean = false,
        val hasMoreHistory: Boolean = true,
        val infoMessage: Message? = null,
        val isBlockedByPeer: Boolean = false,
        val theme: ConversationTheme? = null,
        val members: List<SeenReceipt> = emptyList(),
        val typingNames: List<String> = emptyList(),
        val seenSheetFor: Message? = null,
        val isSavingTheme: Boolean = false,
        val groupLeft: Boolean = false,
    ) {
        val isGroup: Boolean get() = conversation?.isGroup == true || theme?.isGroup == true

        /** Members whose read cursor is past this message — the mini profiles on the bubble. */
        fun seenBy(message: Message): List<Profile> {
            val sentAt = message.createdAt ?: return emptyList()
            return members
                .filter { it.userId != myId }
                .filter { member ->
                    val readAt = member.lastReadAt
                    readAt != null && !readAt.isBefore(sentAt.minusSeconds(1))
                }
                .map { it.toProfile() }
        }

        val typingLabel: String
            get() = when {
                typingNames.isEmpty() -> ""
                typingNames.size == 1 -> "${typingNames.first()} is typing"
                typingNames.size == 2 -> "${typingNames[0]} and ${typingNames[1]} are typing"
                else -> "${typingNames.size} people are typing"
            }

        val isBlocked: Boolean get() = isBlockedByPeer
        val canSend: Boolean get() = composerText.isNotBlank() && !isBlockedByPeer
        val subtitle: String get() = when {
            typingNames.isNotEmpty() -> typingLabel
            peerTyping -> "typing…"
            isGroup -> {
                val count = conversation?.memberCount?.takeIf { it > 0 } ?: members.size
                if (count > 0) "$count members" else "Group chat"
            }
            conversation?.peer == null -> ""
            else -> com.minichat.app.core.util.TimeFormat.lastSeen(
                conversation.peer.lastSeenAt?.toString(),
                com.minichat.app.core.util.TimeFormat.isOnline(conversation.peer.lastSeenAt?.toString()),
            )
        }
    }

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _forwardTargets = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val forwardTargets: StateFlow<List<ConversationSummary>> = _forwardTargets.asStateFlow()

    /** Set when this screen successfully started a call so the UI can open the call screen. */
    private val _outgoingCall = MutableStateFlow<Call?>(null)
    val outgoingCall: StateFlow<Call?> = _outgoingCall.asStateFlow()

    private var typingJob: Job? = null
    private var typingSentAt: Long = 0L
    private var readMarkerJob: Job? = null

    init {
        openConversation()
    }

    private fun openConversation() {
        viewModelScope.launch {
            val myId = profileRepository.currentUserId().orEmpty()
            _uiState.update { it.copy(myId = myId) }

            when (val result = chatRepository.loadConversation(conversationId)) {
                is AppResult.Success -> _uiState.update { it.copy(conversation = result.data) }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }

            loadMessages(initial = true)
            refreshPeerReadState()
            refreshTheme()
            refreshReceipts()
            markRead()

            chatRepository.messageEvents(conversationId).collect { event -> applyMessageEvent(event) }
        }
    }

    fun loadMessages(initial: Boolean = false) {
        viewModelScope.launch {
            val state = _uiState.value
            if (!initial && (!state.hasMoreHistory || state.isLoadingOlder)) return@launch

            if (!initial) _uiState.update { it.copy(isLoadingOlder = true) }
            val oldest = state.messages.firstOrNull()?.createdAt

            when (val result = chatRepository.loadMessages(conversationId, before = oldest)) {
                is AppResult.Success -> {
                    val existingIds = state.messages.map { it.id }.toSet()
                    val incoming = result.data.filterNot { it.id in existingIds }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingOlder = false,
                            hasMoreHistory = result.data.size >= PAGE_SIZE,
                            messages = (incoming + it.messages).sortedBy { message -> message.createdAt ?: Instant.EPOCH },
                            error = null,
                        )
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, isLoadingOlder = false, error = result.message)
                }
            }
        }
    }

    private fun applyMessageEvent(event: MessageEvent) {
        when (event) {
            is MessageEvent.Inserted -> {
                val message = event.message
                if (message.conversationId != conversationId) return
                _uiState.update { state ->
                    if (state.messages.any { it.id == message.id }) {
                        state
                    } else {
                        state.copy(
                            messages = (state.messages + message).sortedBy { it.createdAt ?: Instant.EPOCH },
                        )
                    }
                }
                if (!message.isMine) markRead()
            }

            is MessageEvent.Updated -> {
                val message = event.message
                if (message.conversationId != conversationId) return
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { existing ->
                            if (existing.id == message.id) message.copy(reactions = existing.reactions) else existing
                        },
                    )
                }
            }

            is MessageEvent.Deleted -> _uiState.update { state ->
                state.copy(messages = state.messages.filterNot { it.id == event.messageId })
            }

            is MessageEvent.ReactionAdded -> _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        if (message.id == event.reaction.messageId &&
                            message.reactions.none { it.id == event.reaction.id }
                        ) {
                            message.copy(reactions = message.reactions + event.reaction)
                        } else {
                            message
                        }
                    },
                )
            }

            is MessageEvent.ReactionRemoved -> _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        if (message.id == event.reaction.messageId) {
                            message.copy(reactions = message.reactions.filterNot { it.id == event.reaction.id })
                        } else {
                            message
                        }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    fun onComposerTextChange(value: String) {
        val limited = value.take(Validators.MAX_MESSAGE_LENGTH)
        _uiState.update { it.copy(composerText = limited, error = null) }
        notifyTyping()
    }

    private fun notifyTyping() {
        viewModelScope.launch {
            val settings = userPreferences.settings.first()
            if (!settings.typingIndicatorsEnabled) return@launch
            val now = System.currentTimeMillis()
            if (now - typingSentAt < TYPING_THROTTLE_MS) return@launch
            typingSentAt = now
            runCatching { realtimeRepository.setTyping(conversationId, true) }
            typingJob?.cancel()
            typingJob = viewModelScope.launch {
                delay(TYPING_IDLE_MS)
                runCatching { realtimeRepository.setTyping(conversationId, false) }
            }
        }
    }

    fun send() {
        val state = _uiState.value
        val content = state.composerText.trim()
        if (content.isEmpty()) return

        val editing = state.editing
        if (editing != null) {
            _uiState.update { it.copy(composerText = "", editing = null) }
            viewModelScope.launch {
                when (val result = chatRepository.editMessage(editing.id, content)) {
                    is AppResult.Success -> _uiState.update { st ->
                        st.copy(
                            messages = st.messages.map {
                                if (it.id == editing.id) it.copy(content = content, isEdited = true) else it
                            },
                        )
                    }

                    is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
                }
            }
            return
        }

        val replyTo = state.replyTo
        val optimistic = Message(
            id = "local-${UUID.randomUUID()}",
            conversationId = conversationId,
            senderId = state.myId,
            content = content,
            createdAt = Instant.now(),
            replyToId = replyTo?.id,
            replyPreview = replyTo?.let {
                com.minichat.app.domain.model.ReplyPreview(
                    messageId = it.id,
                    senderName = if (it.isMine) "You" else (state.conversation?.peer?.displayName ?: "Them"),
                    snippet = it.content.take(120),
                )
            },
            status = MessageStatus.Sending,
            isMine = true,
        )

        _uiState.update {
            it.copy(
                composerText = "",
                replyTo = null,
                messages = it.messages + optimistic,
                error = null,
            )
        }

        viewModelScope.launch {
            runCatching { realtimeRepository.setTyping(conversationId, false) }
            when (val result = chatRepository.sendMessage(conversationId, content, replyTo?.id)) {
                is AppResult.Success -> _uiState.update { st ->
                    st.copy(
                        messages = st.messages
                            .filterNot { it.id == optimistic.id }
                            .let { list -> if (list.any { it.id == result.data.id }) list else list + result.data }
                            .sortedBy { it.createdAt ?: Instant.EPOCH },
                    )
                }

                is AppResult.Failure -> {
                    _uiState.update { st ->
                        st.copy(
                            messages = st.messages.map {
                                if (it.id == optimistic.id) it.copy(status = MessageStatus.Failed) else it
                            },
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    fun startReply(message: Message) {
        _uiState.update { it.copy(replyTo = message, editing = null) }
    }

    fun startEdit(message: Message) {
        if (!message.isMine || message.isDeleted) return
        _uiState.update { it.copy(editing = message, composerText = message.content, replyTo = null) }
    }

    fun cancelReplyOrEdit() {
        _uiState.update { it.copy(replyTo = null, editing = null, composerText = "") }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            _uiState.update { st ->
                st.copy(
                    messages = st.messages.map {
                        if (it.id == message.id) it.copy(isDeleted = true, content = "") else it
                    },
                )
            }
            chatRepository.deleteMessage(message.id).onFailure { failure ->
                _uiState.update { it.copy(error = failure.message) }
            }
        }
    }

    fun retry(message: Message) {
        if (message.status != MessageStatus.Failed) return
        _uiState.update { st -> st.copy(messages = st.messages.filterNot { it.id == message.id }) }
        _uiState.update { it.copy(composerText = message.content) }
        send()
    }

    fun toggleReaction(message: Message, emoji: String) {
        val mine = message.reactions.any { it.userId == _uiState.value.myId && it.emoji == emoji }
        viewModelScope.launch {
            if (mine) {
                chatRepository.removeReaction(message.id, emoji)
            } else {
                chatRepository.addReaction(message.id, emoji)
            }
        }
    }

    fun loadForwardTargets() {
        viewModelScope.launch {
            chatRepository.loadConversations().onSuccess { conversations ->
                _forwardTargets.value = conversations.filter { it.id != conversationId }
            }
        }
    }

    fun forward(message: Message, toConversationId: String) {
        viewModelScope.launch {
            chatRepository.sendMessage(toConversationId, message.content)
                .onFailure { failure -> _uiState.update { it.copy(error = failure.message) } }
        }
    }

    fun showInfo(message: Message) {
        _uiState.update { it.copy(infoMessage = message) }
    }

    fun hideInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun startCall(type: CallType) {
        viewModelScope.launch {
            when (val result = callRepository.startCall(conversationId, type)) {
                is AppResult.Success -> _outgoingCall.value = result.data
                is AppResult.Failure -> {
                    // Someone may already be on a call in this conversation: jump into it instead.
                    val active = callRepository.activeCall()
                    if (active is AppResult.Success && active.data != null) {
                        _outgoingCall.value = active.data
                    } else {
                        _uiState.update { it.copy(error = result.message) }
                    }
                }
            }
        }
    }

    fun consumeOutgoingCall() {
        _outgoingCall.value = null
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // Read receipts + presence
    // ------------------------------------------------------------------

    fun markRead() {
        readMarkerJob?.cancel()
        readMarkerJob = viewModelScope.launch {
            delay(180)
            chatRepository.markConversationRead(conversationId)
        }
    }

    private fun refreshPeerReadState() {
        viewModelScope.launch {
            chatRepository.peerReadAt(conversationId).onSuccess { readAt ->
                _uiState.update { it.copy(peerReadAt = readAt) }
            }
        }
    }

    fun refreshPeerProfile() {
        viewModelScope.launch {
            when (val result = chatRepository.loadConversation(conversationId)) {
                is AppResult.Success -> _uiState.update { it.copy(conversation = result.data) }
                is AppResult.Failure -> Unit
            }
        }
    }

    /** Called from the screen's DisposableEffect so the typing topic is torn down on exit. */
    fun onScreenDisposed() {
        viewModelScope.launch {
            runCatching { realtimeRepository.releaseTyping(conversationId) }
            runCatching { realtimeRepository.unwatchConversation(conversationId) }
        }
    }

    fun onScreenStarted() {
        // Read cursors also arrive over Realtime, but a slow poll keeps the seen avatars honest.
        viewModelScope.launch {
            while (true) {
                refreshReceipts()
                delay(RECEIPTS_POLL_MS)
            }
        }
        viewModelScope.launch {
            runCatching { realtimeRepository.watchConversation(conversationId) }
            realtimeRepository.typingUserIds(conversationId).collect { typingUserIds ->
                val state = _uiState.value
                val others = typingUserIds - state.myId
                val names = others.mapNotNull { id ->
                    state.members.firstOrNull { it.userId == id }?.let { member ->
                        member.displayName.takeIf { it.isNotBlank() } ?: member.username
                    } ?: state.conversation?.peer?.takeIf { it.id == id }?.displayName
                }
                _uiState.update {
                    it.copy(
                        peerTyping = others.contains(it.conversation?.peer?.id),
                        typingNames = names.distinct(),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Groups, theme and seen receipts
    // ------------------------------------------------------------------

    fun refreshTheme() {
        viewModelScope.launch {
            chatRepository.theme(conversationId).onSuccess { theme ->
                _uiState.update { it.copy(theme = theme) }
            }
        }
    }

    fun refreshReceipts() {
        viewModelScope.launch {
            chatRepository.members(conversationId).onSuccess { members ->
                _uiState.update { state ->
                    state.copy(
                        members = members,
                        typingNames = state.typingNames,
                        peerReadAt = members.firstOrNull { it.userId != state.myId }?.lastReadAt
                            ?: state.peerReadAt,
                    )
                }
            }
        }
    }

    fun updateTheme(
        wallpaper: String? = null,
        bubbleColor: String? = null,
        accentColor: String? = null,
        title: String? = null,
    ) {
        _uiState.update { it.copy(isSavingTheme = true) }
        viewModelScope.launch {
            chatRepository.updateTheme(conversationId, wallpaper, bubbleColor, accentColor, title)
                .onSuccess {
                    refreshTheme()
                    refreshPeerProfile()
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.message) }
                }
            _uiState.update { it.copy(isSavingTheme = false) }
        }
    }

    fun leaveGroup() {
        viewModelScope.launch {
            chatRepository.leaveGroup(conversationId)
                .onSuccess { _uiState.update { it.copy(groupLeft = true) } }
                .onFailure { failure -> _uiState.update { it.copy(error = failure.message) } }
        }
    }

    fun openSeenSheet(message: Message) {
        _uiState.update { it.copy(seenSheetFor = message) }
    }

    fun closeSeenSheet() {
        _uiState.update { it.copy(seenSheetFor = null) }
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val RECEIPTS_POLL_MS = 12_000L
        const val TYPING_THROTTLE_MS = 2_500L
        const val TYPING_IDLE_MS = 2_000L
    }
}

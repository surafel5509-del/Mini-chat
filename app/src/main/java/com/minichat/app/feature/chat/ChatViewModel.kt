package com.minichat.app.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.datastore.UserPreferences
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.MessageStatus
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.MessageEvent
import com.minichat.app.domain.repository.NoteRepository
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
    private val noteRepository: NoteRepository,
    private val userPreferences: UserPreferences,
    private val voiceRecorder: com.minichat.app.core.audio.VoiceRecorder,
    private val voicePlayer: com.minichat.app.core.audio.VoicePlayer,
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
        /** True while a pull-to-refresh is syncing the thread. */
        val isRefreshing: Boolean = false,
        val hasMoreHistory: Boolean = true,
        val infoMessage: Message? = null,
        val isBlockedByPeer: Boolean = false,
        /** The peer's live note, surfaced in the chat header. */
        val peerNote: com.minichat.app.domain.model.Note? = null,
        /** My own live note, so "reply with a note" can be offered. */
        val myNote: com.minichat.app.domain.model.Note? = null,
        /** True while the microphone is open. */
        val isRecording: Boolean = false,
        /** Milliseconds captured so far, drives the recorder bar. */
        val recordingMs: Int = 0,
        /** Normalised input level 0..1 for the live meter. */
        val recordingLevel: Float = 0f,
        /** Uploading the finished clip. */
        val isSendingVoice: Boolean = false,
        /** Signed URLs for voice notes, keyed by message id. */
        val voiceUrls: Map<String, String> = emptyMap(),
        /** The note currently being fetched, so its bubble can spin. */
        val loadingVoiceId: String? = null,
    ) {
        val isGroup: Boolean get() = conversation?.isGroup == true

        val isBlocked: Boolean get() = isBlockedByPeer
        val canSend: Boolean get() = composerText.isNotBlank() && !isBlockedByPeer
        val subtitle: String get() = when {
            peerTyping -> if (isGroup) "someone is typing…" else "typing…"
            conversation == null -> ""
            isGroup -> when (val count = conversation.memberCount) {
                1 -> "1 member"
                else -> "$count members"
            }
            conversation.peer == null -> ""
            else -> com.minichat.app.core.util.TimeFormat.lastSeen(
                conversation.peer.lastSeenAt?.toString(),
                com.minichat.app.core.util.TimeFormat.isOnline(conversation.peer.lastSeenAt?.toString()),
            )
        }
    }

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Exposed so a voice bubble can draw its own progress. */
    val playback: StateFlow<com.minichat.app.core.audio.VoicePlayer.PlaybackState> = voicePlayer.state

    private val _forwardTargets = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val forwardTargets: StateFlow<List<ConversationSummary>> = _forwardTargets.asStateFlow()

    private var recordingTickerJob: Job? = null

    private var typingJob: Job? = null
    private var typingSentAt: Long = 0L
    private var readMarkerJob: Job? = null

    init {
        openConversation()
    }

    override fun onCleared() {
        recordingTickerJob?.cancel()
        voiceRecorder.cancel()
        voicePlayer.release()
        super.onCleared()
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
            markRead()
            refreshNotes()

            chatRepository.messageEvents(conversationId).collect { event -> applyMessageEvent(event) }
        }
    }

    /**
     * Pull-to-refresh: re-reads the conversation and the newest page of messages.
     *
     * This exists because realtime can miss an event while the socket is
     * reconnecting; a deliberate gesture should always leave the screen truthful
     * rather than hoping the subscription stayed up.
     */
    fun syncNow() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }

        viewModelScope.launch {
            val conversation = chatRepository.loadConversation(conversationId)
            if (conversation is AppResult.Success) {
                _uiState.update { it.copy(conversation = conversation.data) }
            }

            val page = chatRepository.loadMessages(conversationId, before = null)
            when (page) {
                is AppResult.Success -> _uiState.update { state ->
                    // Keep anything still sending locally, then merge the server
                    // page on top so a refresh never eats an optimistic bubble.
                    val pending = state.messages.filter { it.id.startsWith("local-") }
                    val merged = (page.data + pending)
                        .distinctBy { it.id }
                        .sortedBy { it.createdAt ?: Instant.EPOCH }
                    state.copy(messages = merged, isRefreshing = false, hasMoreHistory = true)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isRefreshing = false, error = page.message)
                }
            }

            refreshPeerReadState()
            markRead()
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

    // -------------------------------------------------------------------------
    // Voice notes
    // -------------------------------------------------------------------------

    /**
     * Opens the microphone and starts the recorder bar.
     *
     * The timer is owned here rather than in the composable: a rotation or a
     * background/foreground cycle must not stop the clip mid-word, and the 7
     * second cap has to fire even if the UI is not drawing frames.
     */
    fun startRecording() {
        if (_uiState.value.isRecording) return
        if (_uiState.value.isBlockedByPeer) {
            _uiState.update { it.copy(error = "You can't send messages in this chat.") }
            return
        }
        if (!voiceRecorder.start()) {
            _uiState.update {
                it.copy(error = "Microphone unavailable. Check that Mini Chat can record audio.")
            }
            return
        }

        _uiState.update {
            it.copy(isRecording = true, recordingMs = 0, recordingLevel = 0f, error = null)
        }

        recordingTickerJob = viewModelScope.launch {
            while (true) {
                delay(RECORDING_TICK_MS)
                val elapsed = voiceRecorder.elapsedMs()
                _uiState.update {
                    it.copy(
                        recordingMs = elapsed,
                        recordingLevel = pseudoLevel(elapsed),
                    )
                }
                if (elapsed >= com.minichat.app.core.audio.VoiceRecorder.MAX_DURATION_MS) {
                    // Hard stop at the cap: the clip is never longer than 7s.
                    finishRecording(send = true)
                    return@launch
                }
            }
        }
    }

    /** Surfaces a microphone/permission problem without starting a recording. */
    fun reportRecordingProblem(message: String) {
        _uiState.update { it.copy(isRecording = false, recordingMs = 0, recordingLevel = 0f, error = message) }
    }

    /** Discards an in-progress recording. */
    fun cancelRecording() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        voiceRecorder.cancel()
        _uiState.update {
            it.copy(isRecording = false, recordingMs = 0, recordingLevel = 0f)
        }
    }

    /** Stops the microphone and, when [send] is true, uploads and posts the clip. */
    fun finishRecording(send: Boolean) {
        recordingTickerJob?.cancel()
        recordingTickerJob = null

        if (!send) {
            cancelRecording()
            return
        }

        val recording = voiceRecorder.stop()
        _uiState.update {
            it.copy(isRecording = false, recordingMs = 0, recordingLevel = 0f)
        }

        if (recording == null) {
            _uiState.update { it.copy(error = "That recording was too short to send.") }
            return
        }

        val state = _uiState.value
        val durationMs = recording.durationMs.coerceIn(
            com.minichat.app.core.audio.VoiceRecorder.MIN_DURATION_MS,
            com.minichat.app.core.audio.VoiceRecorder.MAX_DURATION_MS,
        )
        val replyTo = state.replyTo
        val optimisticId = "local-${UUID.randomUUID()}"

        val optimistic = Message(
            id = optimisticId,
            conversationId = conversationId,
            senderId = state.myId,
            content = "",
            createdAt = Instant.now(),
            replyToId = replyTo?.id,
            replyPreview = replyTo?.let {
                com.minichat.app.domain.model.ReplyPreview(
                    messageId = it.id,
                    senderName = if (it.isMine) "You" else (state.conversation?.peer?.displayName ?: "Them"),
                    snippet = it.displayText.take(120),
                )
            },
            status = MessageStatus.Sending,
            isMine = true,
            messageType = com.minichat.app.domain.model.MessageType.Voice,
            durationMs = durationMs,
        )

        _uiState.update {
            it.copy(
                isSendingVoice = true,
                replyTo = null,
                messages = it.messages + optimistic,
                error = null,
            )
        }

        viewModelScope.launch {
            val bytes = runCatching { recording.file.readBytes() }.getOrNull()
            recording.file.delete()

            if (bytes == null || bytes.isEmpty()) {
                markVoiceFailed(optimisticId, "We couldn't read that recording. Please try again.")
                return@launch
            }

            runCatching { realtimeRepository.setTyping(conversationId, false) }
            when (
                val result = chatRepository.sendVoiceMessage(
                    conversationId = conversationId,
                    audio = bytes,
                    durationMs = durationMs,
                    replyToId = replyTo?.id,
                )
            ) {
                is AppResult.Success -> _uiState.update { st ->
                    st.copy(
                        isSendingVoice = false,
                        messages = st.messages
                            .filterNot { it.id == optimisticId }
                            .let { list ->
                                if (list.any { it.id == result.data.id }) list else list + result.data
                            }
                            .sortedBy { it.createdAt ?: Instant.EPOCH },
                    )
                }

                is AppResult.Failure -> markVoiceFailed(optimisticId, result.message)
            }
        }
    }

    private fun markVoiceFailed(localId: String, message: String) {
        _uiState.update { st ->
            st.copy(
                isSendingVoice = false,
                messages = st.messages.map {
                    if (it.id == localId) it.copy(status = MessageStatus.Failed) else it
                },
                error = message,
            )
        }
    }

    /**
     * Resolves a playable URL for a voice note, then plays it. Signed URLs are
     * cached for the life of the screen because they last an hour.
     */
    fun toggleVoicePlayback(message: Message) {
        if (message.isExpired) {
            _uiState.update { it.copy(error = "This voice message has expired.") }
            return
        }
        val path = message.mediaUrl
        if (path.isNullOrBlank()) {
            _uiState.update { it.copy(error = "This voice message is unavailable.") }
            return
        }

        val known = _uiState.value.voiceUrls[message.id]
        if (known != null) {
            voicePlayer.toggle(message.id, known, message.durationMs ?: 0)
            return
        }

        _uiState.update { it.copy(loadingVoiceId = message.id) }
        viewModelScope.launch {
            when (val result = chatRepository.signedMediaUrl(path)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            loadingVoiceId = null,
                            voiceUrls = it.voiceUrls + (message.id to result.data),
                        )
                    }
                    voicePlayer.toggle(message.id, result.data, message.durationMs ?: 0)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(loadingVoiceId = null, error = result.message)
                }
            }
        }
    }

    /** Deterministic, gently varying meter value so the bars feel alive. */
    private fun pseudoLevel(elapsedMs: Int): Float {
        val beat = kotlin.math.sin(elapsedMs / 130.0).toFloat()
        return (0.45f + beat * 0.35f).coerceIn(0.15f, 1f)
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

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------
    // Conversation actions (the chat header menu)
    // ------------------------------------------------------------------

    fun togglePinned() {
        val current = _uiState.value.conversation ?: return
        val target = !current.isPinned
        _uiState.update { it.copy(conversation = current.copy(isPinned = target)) }
        viewModelScope.launch {
            chatRepository.setPinned(conversationId, target).onFailure { failure ->
                _uiState.update {
                    it.copy(conversation = current, error = failure.message)
                }
            }
        }
    }

    fun toggleMuted() {
        val current = _uiState.value.conversation ?: return
        val target = !current.isMuted
        _uiState.update { it.copy(conversation = current.copy(isMuted = target)) }
        viewModelScope.launch {
            chatRepository.setMuted(conversationId, target).onFailure { failure ->
                _uiState.update {
                    it.copy(conversation = current, error = failure.message)
                }
            }
        }
    }

    /**
     * Clears the thread for me. Messages are soft deleted through the normal
     * delete path so the other person keeps their history — this only removes
     * what is currently loaded and tells the UI to drop it.
     */
    fun clearLoadedMessages() {
        _uiState.update { it.copy(messages = emptyList(), error = null) }
    }

    fun blockPeer(onDone: () -> Unit = {}) {
        val peer = _uiState.value.conversation?.peer ?: return
        viewModelScope.launch {
            profileRepository.blockUser(peer.id)
                .onSuccess {
                    _uiState.update { it.copy(isBlockedByPeer = true) }
                    onDone()
                }
                .onFailure { failure -> _uiState.update { it.copy(error = failure.message) } }
        }
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
            refreshNotes()
        }
    }

    /** Pulls the peer's note (header pill) and my own (note reply). Best effort. */
    fun refreshNotes() {
        viewModelScope.launch {
            val peerId = _uiState.value.conversation?.peer?.id
            val peerNote = peerId?.let { id -> (noteRepository.noteFor(id) as? AppResult.Success)?.data }
            val myNote = (noteRepository.myNote() as? AppResult.Success)?.data
            _uiState.update { it.copy(peerNote = peerNote, myNote = myNote) }
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
        viewModelScope.launch {
            runCatching { realtimeRepository.watchConversation(conversationId) }
            realtimeRepository.typingUserIds(conversationId).collect { typingUserIds ->
                val peerId = _uiState.value.conversation?.peer?.id
                _uiState.update { it.copy(peerTyping = typingUserIds.contains(peerId)) }
            }
        }
    }

    private companion object {
        const val RECORDING_TICK_MS = 60L

        const val PAGE_SIZE = 40
        const val TYPING_THROTTLE_MS = 2_500L
        const val TYPING_IDLE_MS = 2_000L
    }
}

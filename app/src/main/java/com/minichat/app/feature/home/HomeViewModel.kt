package com.minichat.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.FriendNote
import com.minichat.app.domain.model.Note
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.NoteRepository
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.domain.repository.RealtimeRepository
import com.minichat.app.notifications.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val profileRepository: ProfileRepository,
    private val realtimeRepository: RealtimeRepository,
    private val noteRepository: NoteRepository,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        /** True while a pull-to-refresh is in flight. */
        val isRefreshing: Boolean = false,
        val conversations: List<ConversationSummary> = emptyList(),
        val query: String = "",
        val profile: Profile? = null,
        val error: String? = null,
        val typingByConversation: Map<String, Set<String>> = emptyMap(),
        val actionInProgress: String? = null,
        // Notes
        val myNote: Note? = null,
        val friendNotes: List<FriendNote> = emptyList(),
        val isSavingNote: Boolean = false,
        val noteError: String? = null,
    ) {
        val visible: List<ConversationSummary> get() = if (query.isBlank()) {
            conversations
        } else {
            val needle = query.trim().lowercase()
            conversations.filter { conversation ->
                conversation.displayName.lowercase().contains(needle) ||
                    conversation.peer?.username?.lowercase()?.contains(needle) == true ||
                    conversation.lastMessage?.text?.lowercase()?.contains(needle) == true
            }
        }

        val isEmpty: Boolean get() = !isLoading && visible.isEmpty()

        val hasNotes: Boolean get() = myNote != null || friendNotes.isNotEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var watchedConversations: Set<String> = emptySet()
    private var typingJobs: List<Job> = emptyList()
    private var refreshJob: Job? = null

    init {
        refresh(showSpinner = true)
        observeRealtime()
        observeNotes()
        loadProfile()
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun refresh(showSpinner: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (showSpinner) _uiState.update { it.copy(isLoading = true) }
            when (val result = chatRepository.loadConversations()) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            conversations = result.data,
                            error = null,
                        )
                    }
                    syncTypingSubscriptions(result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = result.message)
                }
            }
        }
    }

    /**
     * Pull-to-refresh.
     *
     * Unlike [refresh] this deliberately bypasses the in-flight guard: the user
     * made a gesture and the spinner must resolve to something.
     */
    fun onPullToRefresh() {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        refreshJob?.cancel()
        viewModelScope.launch {
            val conversations = chatRepository.loadConversations()
            when (conversations) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            conversations = conversations.data,
                            error = null,
                        )
                    }
                    syncTypingSubscriptions(conversations.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = conversations.message)
                }
            }
            // Notes are cheap and belong in the same gesture.
            loadNotes()
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val profile = profileRepository.myProfile().getOrNull()
            if (profile != null) _uiState.update { it.copy(profile = profile) }
        }
    }

    // ------------------------------------------------------------------
    // Notes
    // ------------------------------------------------------------------

    /** Notes are cheap: re-read the (RLS filtered, unexpired) list on any change. */
    private fun observeNotes() {
        viewModelScope.launch {
            realtimeRepository.noteSignals().collect {
                delay(150)
                loadNotes()
            }
        }
        loadNotes()
    }

    fun loadNotes() {
        viewModelScope.launch {
            // A failure and "no note" are different states: only overwrite the
            // cached values when the read actually succeeded.
            val mine = noteRepository.myNote()
            val friends = noteRepository.friendNotes()
            _uiState.update { state ->
                state.copy(
                    myNote = (mine as? AppResult.Success)?.data ?: state.myNote,
                    friendNotes = (friends as? AppResult.Success)?.data ?: state.friendNotes,
                )
            }
        }
    }

    fun saveNote(body: String, minutes: Int) {
        if (_uiState.value.isSavingNote) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingNote = true, noteError = null) }
            when (val result = noteRepository.setNote(body, minutes)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSavingNote = false, myNote = result.data, noteError = null) }
                    loadNotes()
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isSavingNote = false, noteError = result.message)
                }
            }
        }
    }

    fun deleteNote() {
        if (_uiState.value.isSavingNote) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingNote = true, noteError = null) }
            when (val result = noteRepository.deleteNote()) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSavingNote = false, myNote = null) }
                    loadNotes()
                }

                is AppResult.Failure -> _uiState.update { it.copy(isSavingNote = false, noteError = result.message) }
            }
        }
    }

    fun dismissNoteError() {
        _uiState.update { it.copy(noteError = null) }
    }

    private fun observeRealtime() {
        viewModelScope.launch {
            // Coalesce bursts of realtime events into a single reload.
            chatRepository.conversationSignals().collect {
                delay(220)
                refresh()
            }
        }
    }

    /** Keeps at most eight typing subscriptions alive — the pinned / most recent threads. */
    private suspend fun syncTypingSubscriptions(conversations: List<ConversationSummary>) {
        val target = conversations.take(8).map { it.id }.toSet()
        val toRemove = watchedConversations - target
        val toAdd = target - watchedConversations

        toRemove.forEach { id -> runCatching { realtimeRepository.unwatchConversation(id) } }
        toAdd.forEach { id -> runCatching { realtimeRepository.watchConversation(id) } }
        watchedConversations = target

        typingJobs.forEach { it.cancel() }
        typingJobs = target.map { conversationId ->
            viewModelScope.launch {
                realtimeRepository.typingUserIds(conversationId).collect { userIds ->
                    _uiState.update { state ->
                        state.copy(typingByConversation = state.typingByConversation + (conversationId to userIds))
                    }
                }
            }
        }
    }

    fun togglePinned(conversation: ConversationSummary) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionInProgress = conversation.id) }
            chatRepository.setPinned(conversation.id, !conversation.isPinned)
            refresh()
            _uiState.update { it.copy(actionInProgress = null) }
        }
    }

    fun toggleMuted(conversation: ConversationSummary) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionInProgress = conversation.id) }
            chatRepository.setMuted(conversation.id, !conversation.isMuted)
            refresh()
            _uiState.update { it.copy(actionInProgress = null) }
        }
    }

    fun markRead(conversation: ConversationSummary) {
        viewModelScope.launch {
            chatRepository.markConversationRead(conversation.id)
            refresh()
        }
    }

    fun clearNotification(conversationId: String) {
        notificationHelper.clearConversationNotifications(conversationId)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        typingJobs.forEach { it.cancel() }
        super.onCleared()
    }
}

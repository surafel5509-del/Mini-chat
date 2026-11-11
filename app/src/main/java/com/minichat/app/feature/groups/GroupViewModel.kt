package com.minichat.app.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.ConversationMember
import com.minichat.app.domain.model.GroupRole
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.GroupRepository
import com.minichat.app.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Create-group and group-info state.
 *
 * One ViewModel serves both because they share the same roster loading and the
 * same error channel; the screen decides which half to show.
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Empty when creating a new group. */
    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    val isEditing: Boolean get() = conversationId.isNotBlank()

    data class UiState(
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val error: String? = null,
        /** Step 1: who is in the group. */
        val candidates: List<Profile> = emptyList(),
        val selected: Set<String> = emptySet(),
        val query: String = "",
        /** Step 2 (or the info screen). */
        val title: String = "",
        val description: String = "",
        val members: List<ConversationMember> = emptyList(),
        val myRole: GroupRole = GroupRole.Member,
        val avatarUrl: String? = null,
        val memberCount: Int = 0,
    ) {
        val filteredCandidates: List<Profile>
            get() = if (query.isBlank()) {
                candidates
            } else {
                candidates.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.handle.contains(query, ignoreCase = true)
                }
            }

        val canCreate: Boolean get() = title.isNotBlank() && selected.isNotEmpty()
        val canManage: Boolean get() = myRole.isAdmin
        val isOwner: Boolean get() = myRole == GroupRole.Owner
    }

    sealed interface Event {
        data class Created(val conversationId: String) : Event
        data class Message(val text: String) : Event
        data object LeftGroup : Event
        data object DeletedGroup : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (isEditing) {
            loadGroup()
            loadCandidates()
        } else {
            loadCandidates()
        }
    }

    // ------------------------------------------------------------------
    // Create flow
    // ------------------------------------------------------------------

    private fun loadCandidates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // People you have talked to recently are the useful first screen;
            // the search field below widens that to everyone.
            when (val result = profileRepository.recentPeers()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, candidates = result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    /** Live search so a group can include someone you've never messaged. */
    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        if (value.trim().length < 2) return
        viewModelScope.launch {
            when (val result = profileRepository.searchUsers(value.trim())) {
                is AppResult.Success -> _uiState.update { state ->
                    val merged = (state.candidates + result.data).distinctBy { it.id }
                    state.copy(candidates = merged)
                }

                is AppResult.Failure -> Unit
            }
        }
    }

    fun toggleMember(userId: String) {
        _uiState.update { state ->
            val next = if (userId in state.selected) {
                state.selected - userId
            } else {
                state.selected + userId
            }
            state.copy(selected = next, error = null)
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value.take(MAX_TITLE), error = null) }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value.take(MAX_DESCRIPTION), error = null) }
    }

    fun createGroup() {
        val state = _uiState.value
        if (!state.canCreate) {
            _uiState.update { it.copy(error = "Add a name and at least one member.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (
                val result = groupRepository.createGroup(
                    title = state.title,
                    description = state.description.takeIf { it.isNotBlank() },
                    memberIds = state.selected.toList(),
                )
            ) {
                is AppResult.Success -> _events.send(Event.Created(result.data))
                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Existing group
    // ------------------------------------------------------------------

    fun loadGroup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = groupRepository.members(conversationId)) {
                is AppResult.Success -> {
                    val me = profileRepository.currentUserId()
                    val mine = result.data.firstOrNull { it.userId == me }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            members = result.data,
                            memberCount = result.data.size,
                            myRole = mine?.role ?: GroupRole.Member,
                        )
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }

            // Title/description/avatar come from the overview, which already has
            // them; loading separately keeps this screen independent of the chat.
            when (val overview = groupRepository.groupOverview(conversationId)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        title = overview.data.title.orEmpty(),
                        description = overview.data.description.orEmpty(),
                        avatarUrl = overview.data.avatarUrl,
                        myRole = overview.data.myRole,
                        memberCount = overview.data.memberCount,
                    )
                }

                is AppResult.Failure -> Unit
            }
        }
    }

    fun saveChanges() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "The group needs a name.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (
                val result = groupRepository.updateGroup(
                    conversationId = conversationId,
                    title = state.title,
                    description = state.description,
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.send(Event.Message("Group updated"))
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun addMembers(userIds: List<String>) {
        if (userIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (val result = groupRepository.addMembers(conversationId, userIds)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, selected = emptySet()) }
                    _events.send(
                        Event.Message(
                            when (result.data) {
                                1 -> "1 member added"
                                else -> "${result.data} members added"
                            },
                        ),
                    )
                    loadGroup()
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun removeMember(userId: String) {
        viewModelScope.launch {
            when (val result = groupRepository.removeMember(conversationId, userId)) {
                is AppResult.Success -> {
                    _uiState.update { st ->
                        st.copy(
                            members = st.members.filterNot { it.userId == userId },
                            memberCount = (st.memberCount - 1).coerceAtLeast(0),
                        )
                    }
                    _events.send(Event.Message("Member removed"))
                }

                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun promote(userId: String) = changeRole(userId, GroupRole.Admin)

    fun demote(userId: String) = changeRole(userId, GroupRole.Member)

    private fun changeRole(userId: String, role: GroupRole) {
        viewModelScope.launch {
            when (val result = groupRepository.setRole(conversationId, userId, role)) {
                is AppResult.Success -> {
                    _uiState.update { st ->
                        st.copy(
                            members = st.members.map {
                                if (it.userId == userId) it.copy(role = role) else it
                            },
                        )
                    }
                    _events.send(
                        Event.Message(
                            if (role == GroupRole.Admin) "Promoted to admin" else "Admin removed",
                        ),
                    )
                }

                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun leave() {
        viewModelScope.launch {
            when (val result = groupRepository.leave(conversationId)) {
                is AppResult.Success -> _events.send(Event.LeftGroup)
                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun deleteGroup() {
        viewModelScope.launch {
            when (val result = groupRepository.deleteGroup(conversationId)) {
                is AppResult.Success -> _events.send(Event.DeletedGroup)
                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun uploadAvatar(bytes: ByteArray, extension: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (val result = groupRepository.uploadGroupAvatar(conversationId, bytes, extension)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, avatarUrl = result.data) }
                    _events.send(Event.Message("Group photo updated"))
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        const val MAX_TITLE = 80
        const val MAX_DESCRIPTION = 240
    }
}

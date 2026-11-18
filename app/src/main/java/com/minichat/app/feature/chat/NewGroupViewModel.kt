package com.minichat.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Creates a group chat: name, description and the people to add. */
@HiltViewModel
class NewGroupViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val name: String = "",
        val description: String = "",
        val query: String = "",
        val people: List<Profile> = emptyList(),
        val selected: List<Profile> = emptyList(),
        val creating: Boolean = false,
        val error: String? = null,
        val createdConversationId: String? = null,
    ) {
        val canCreate: Boolean
            get() = name.isNotBlank() && selected.isNotEmpty() && !creating
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadPeople()
    }

    private fun loadPeople() {
        viewModelScope.launch {
            when (val result = profileRepository.recentPeers()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, people = result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value.take(60), error = null) }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value.take(160)) }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            loadPeople()
            return
        }
        searchJob = viewModelScope.launch {
            delay(320)
            when (val result = profileRepository.searchUsers(value.trim())) {
                is AppResult.Success -> _uiState.update { it.copy(people = result.data) }
                is AppResult.Failure -> Unit
            }
        }
    }

    fun toggle(person: Profile) {
        _uiState.update { state ->
            val already = state.selected.any { it.id == person.id }
            state.copy(
                selected = if (already) {
                    state.selected.filterNot { it.id == person.id }
                } else {
                    state.selected + person
                },
            )
        }
    }

    fun create() {
        val state = _uiState.value
        if (!state.canCreate) return
        _uiState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (
                val result = chatRepository.createGroup(
                    title = state.name.trim(),
                    description = state.description.trim().takeIf { it.isNotEmpty() },
                    memberIds = state.selected.map { it.id },
                )
            ) {
                is AppResult.Success -> _uiState.update {
                    it.copy(creating = false, createdConversationId = result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(creating = false, error = result.message)
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

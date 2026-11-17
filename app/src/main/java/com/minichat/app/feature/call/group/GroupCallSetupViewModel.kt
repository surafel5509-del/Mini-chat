package com.minichat.app.feature.call.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.GroupCallRepository
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

/** Picks the people to invite, then creates the group room. */
@HiltViewModel
class GroupCallSetupViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val groupCallRepository: GroupCallRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val people: List<Profile> = emptyList(),
        val selected: Set<String> = emptySet(),
        val query: String = "",
        val title: String = "",
        val starting: Boolean = false,
        val error: String? = null,
        val startedCallId: String? = null,
    ) {
        val canStart: Boolean get() = selected.isNotEmpty() && !starting
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
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

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            viewModelScope.launch {
                when (val recent = profileRepository.recentPeers()) {
                    is AppResult.Success -> _uiState.update { state ->
                        if (state.query.isBlank()) state.copy(people = recent.data) else state
                    }

                    is AppResult.Failure -> Unit
                }
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            when (val result = profileRepository.searchUsers(value.trim())) {
                is AppResult.Success -> _uiState.update { it.copy(people = result.data) }
                is AppResult.Failure -> Unit
            }
        }
    }

    fun toggle(userId: String) {
        _uiState.update { state ->
            val selected = if (state.selected.contains(userId)) {
                state.selected - userId
            } else {
                state.selected + userId
            }
            state.copy(selected = selected, error = null)
        }
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value.take(60)) }
    }

    fun start(type: CallType) {
        val state = _uiState.value
        if (!state.canStart) {
            _uiState.update { it.copy(error = "Pick at least one person to invite.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(starting = true, error = null) }
            when (val result = groupCallRepository.start(state.title, type, state.selected.toList())) {
                is AppResult.Success -> _uiState.update {
                    it.copy(starting = false, startedCallId = result.data.id)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(starting = false, error = result.message)
                }
            }
        }
    }

    fun consumeStartedCall() {
        _uiState.update { it.copy(startedCallId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

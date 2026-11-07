package com.minichat.app.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.StartedConversation
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
import com.minichat.app.core.util.onSuccess

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<Profile> = emptyList(),
        val recent: List<Profile> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val openingUserId: String? = null,
        val blockedUserIds: Set<String> = emptySet(),
    ) {
        val showRecents: Boolean get() = query.isBlank()
        val isEmptyResult: Boolean get() = query.length >= 2 && !isLoading && results.isEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _navigation = MutableStateFlow<StartedConversation?>(null)
    val navigation: StateFlow<StartedConversation?> = _navigation.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadRecents()
    }

    private fun loadRecents() {
        viewModelScope.launch {
            profileRepository.recentPeers().onSuccess { peers ->
                _uiState.update { it.copy(recent = peers) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, error = null) }
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _uiState.update { it.copy(results = emptyList(), isLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(280)
            _uiState.update { it.copy(isLoading = true) }
            when (val result = profileRepository.searchUsers(value)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(results = result.data, isLoading = false)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun startConversation(profile: Profile) {
        viewModelScope.launch {
            _uiState.update { it.copy(openingUserId = profile.id) }
            when (val result = profileRepository.startConversation(profile.id)) {
                is AppResult.Success -> _navigation.value = result.data
                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
            _uiState.update { it.copy(openingUserId = null) }
        }
    }

    fun consumeNavigation() {
        _navigation.value = null
    }

    fun block(profile: Profile) {
        viewModelScope.launch {
            profileRepository.blockUser(profile.id).onSuccess {
                _uiState.update { state ->
                    state.copy(
                        results = state.results.filterNot { it.id == profile.id },
                        recent = state.recent.filterNot { it.id == profile.id },
                        blockedUserIds = state.blockedUserIds + profile.id,
                    )
                }
            }
        }
    }

    fun report(profile: Profile, reason: String) {
        viewModelScope.launch {
            profileRepository.reportUser(profile.id, reason)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

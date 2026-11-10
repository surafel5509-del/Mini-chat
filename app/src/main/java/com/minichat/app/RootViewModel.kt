package com.minichat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.datastore.UserPreferences
import com.minichat.app.core.datastore.UserSettings
import com.minichat.app.design.theme.ThemeMode
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the two things that decide what the app looks like before any screen renders:
 * the restored session and the persisted theme.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    data class UiState(
        val authState: AuthState = AuthState.Loading,
        val settings: UserSettings = UserSettings.Default,
    ) {
        val isResolvingSession: Boolean get() = authState is AuthState.Loading
    }

    val uiState: StateFlow<UiState> = combine(
        authRepository.authState,
        userPreferences.settings,
    ) { authState, settings ->
        UiState(authState = authState, settings = settings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState(),
    )

    init {
        viewModelScope.launch {
            authRepository.awaitReady()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(mode) }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

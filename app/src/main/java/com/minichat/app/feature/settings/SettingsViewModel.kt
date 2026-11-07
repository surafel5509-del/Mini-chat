package com.minichat.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.datastore.UserPreferences
import com.minichat.app.core.datastore.UserSettings
import com.minichat.app.design.theme.ThemeMode
import com.minichat.app.domain.model.BlockedUser
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class BlockedState(
        val isLoading: Boolean = true,
        val users: List<BlockedUser> = emptyList(),
        val error: String? = null,
    )

    private val _blocked = MutableStateFlow(BlockedState())
    val blocked: StateFlow<BlockedState> = _blocked.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    val settings: StateFlow<UserSettings> = userPreferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings.Default,
    )

    val combined: StateFlow<Pair<UserSettings, Profile?>> = combine(settings, profile) { prefs, me ->
        prefs to me
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserSettings.Default to null,
    )

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            profileRepository.myProfile().onSuccess { _profile.value = it }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { userPreferences.setThemeMode(mode) }

    fun setNotifications(enabled: Boolean) =
        viewModelScope.launch { userPreferences.setNotificationsEnabled(enabled) }

    fun setSound(enabled: Boolean) = viewModelScope.launch { userPreferences.setSoundEnabled(enabled) }

    fun setVibration(enabled: Boolean) =
        viewModelScope.launch { userPreferences.setVibrationEnabled(enabled) }

    fun setPreviews(enabled: Boolean) =
        viewModelScope.launch { userPreferences.setPreviewsEnabled(enabled) }

    fun setReadReceipts(enabled: Boolean) =
        viewModelScope.launch { userPreferences.setReadReceiptsEnabled(enabled) }

    fun setTypingIndicators(enabled: Boolean) =
        viewModelScope.launch { userPreferences.setTypingIndicatorsEnabled(enabled) }

    fun loadBlockedUsers() {
        viewModelScope.launch {
            _blocked.update { it.copy(isLoading = true, error = null) }
            when (val result = profileRepository.blockedUsers()) {
                is com.minichat.app.core.util.AppResult.Success ->
                    _blocked.update { it.copy(isLoading = false, users = result.data) }

                is com.minichat.app.core.util.AppResult.Failure ->
                    _blocked.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun unblock(user: BlockedUser) {
        viewModelScope.launch {
            profileRepository.unblockUser(user.profile.id).onSuccess {
                _blocked.update { state ->
                    state.copy(users = state.users.filterNot { it.profile.id == user.profile.id })
                }
            }
        }
    }

    fun signOut() = viewModelScope.launch { authRepository.signOut() }
}

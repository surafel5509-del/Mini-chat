package com.minichat.app.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.minichat.app.core.util.onSuccess
import com.minichat.app.core.util.onFailure

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val profile: Profile? = null,
        val isMe: Boolean = false,
        val isBlocked: Boolean = false,
        val isSaving: Boolean = false,
        val isUploadingAvatar: Boolean = false,
        val error: String? = null,
        val info: String? = null,
        val displayName: String = "",
        val username: String = "",
        val bio: String = "",
        val nameError: String? = null,
        val usernameError: String? = null,
        val bioError: String? = null,
        val blockedCount: Int = 0,
    ) {
        val canSave: Boolean
            get() = displayName.isNotBlank() && username.isNotBlank() && !isSaving
    }

    private val routeUserId: String? = savedStateHandle.get<String>("userId")

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val currentUserId = profileRepository.currentUserId()
            val targetId = routeUserId?.takeIf { it != "me" } ?: currentUserId
            if (targetId == null) {
                _uiState.update { it.copy(isLoading = false, error = "You're not signed in.") }
                return@launch
            }
            val isMe = targetId == currentUserId

            val profileResult = if (isMe) profileRepository.myProfile() else profileRepository.profile(targetId)
            when (val result = profileResult) {
                is AppResult.Success -> {
                    val profile = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = profile,
                            isMe = isMe,
                            displayName = profile.displayName,
                            username = profile.username,
                            bio = profile.bio.orEmpty(),
                        )
                    }
                    if (!isMe) {
                        profileRepository.blockedUsers().onSuccess { blocks ->
                            _uiState.update { state ->
                                state.copy(isBlocked = blocks.any { it.profile.id == targetId })
                            }
                        }
                    } else {
                        profileRepository.blockedUsers().onSuccess { blocks ->
                            _uiState.update { state -> state.copy(blockedCount = blocks.size) }
                        }
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update { it.copy(displayName = value.take(48), nameError = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = Validators.sanitizeUsername(value), usernameError = null) }
    }

    fun onBioChange(value: String) {
        _uiState.update { it.copy(bio = value.take(Validators.MAX_BIO_LENGTH), bioError = null) }
    }

    fun saveProfile(onSaved: () -> Unit) {
        val state = _uiState.value
        val nameError = Validators.validateDisplayName(state.displayName)
        val usernameError = Validators.validateUsername(state.username)
        val bioError = Validators.validateBio(state.bio)
        if (nameError != null || usernameError != null || bioError != null) {
            _uiState.update {
                it.copy(nameError = nameError, usernameError = usernameError, bioError = bioError)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (val result = profileRepository.updateProfile(state.displayName, state.username, state.bio)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, profile = result.data, info = "Profile updated") }
                    onSaved()
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.message)
                }
            }
        }
    }

    fun uploadAvatar(bytes: ByteArray, extension: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, error = null) }
            when (val result = profileRepository.uploadAvatar(bytes, extension)) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            isUploadingAvatar = false,
                            profile = state.profile?.copy(avatarUrl = result.data),
                            info = "Photo updated",
                        )
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isUploadingAvatar = false, error = result.message)
                }
            }
        }
    }

    fun blockUser() {
        val target = _uiState.value.profile ?: return
        viewModelScope.launch {
            profileRepository.blockUser(target.id).onSuccess {
                _uiState.update { it.copy(isBlocked = true, info = "${target.displayName} is blocked") }
            }.onFailure { failure ->
                _uiState.update { it.copy(error = failure.message) }
            }
        }
    }

    fun unblockUser() {
        val target = _uiState.value.profile ?: return
        viewModelScope.launch {
            profileRepository.unblockUser(target.id).onSuccess {
                _uiState.update { it.copy(isBlocked = false, info = "${target.displayName} is unblocked") }
            }.onFailure { failure ->
                _uiState.update { it.copy(error = failure.message) }
            }
        }
    }

    fun report(reason: String) {
        val target = _uiState.value.profile ?: return
        viewModelScope.launch {
            profileRepository.reportUser(target.id, reason).onSuccess {
                _uiState.update { it.copy(info = "Report sent. Thank you.") }
            }.onFailure { failure ->
                _uiState.update { it.copy(error = failure.message) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(error = null, info = null) }
    }
}

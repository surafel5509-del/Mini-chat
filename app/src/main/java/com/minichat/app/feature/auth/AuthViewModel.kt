package com.minichat.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.SignUpOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val email: String = "",
        val password: String = "",
        val displayName: String = "",
        val isLoading: Boolean = false,
        val emailError: String? = null,
        val passwordError: String? = null,
        val nameError: String? = null,
        val formError: String? = null,
        val resetLinkSent: Boolean = false,
        val verificationEmail: String? = null,
        val passwordUpdated: Boolean = false,
    )

    sealed interface Event {
        data object NavigateHome : Event
        data class Message(val text: String) : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value.trim(), emailError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, formError = null) }
    }

    fun onDisplayNameChange(value: String) {
        _uiState.update {
            it.copy(displayName = value.take(Validators.MAX_DISPLAY_NAME_LENGTH), nameError = null)
        }
    }

    fun clearFormError() {
        _uiState.update { it.copy(formError = null) }
    }

    // ------------------------------------------------------------------
    // Sign in
    // ------------------------------------------------------------------
    fun signIn() {
        val state = _uiState.value
        val emailError = Validators.validateEmail(state.email)
        val passwordError = Validators.validateLoginPassword(state.password)
        if (emailError != null || passwordError != null) {
            _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        launchSafely {
            when (val result = authRepository.signIn(state.email, state.password)) {
                is AppResult.Success -> _events.send(Event.NavigateHome)
                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    // ------------------------------------------------------------------
    // Register
    // ------------------------------------------------------------------
    fun register() {
        val state = _uiState.value
        val nameError = Validators.validateDisplayName(state.displayName)
        val emailError = Validators.validateEmail(state.email)
        val passwordError = Validators.validatePassword(state.password)
        if (nameError != null || emailError != null || passwordError != null) {
            _uiState.update {
                it.copy(nameError = nameError, emailError = emailError, passwordError = passwordError)
            }
            return
        }
        launchSafely {
            when (val result = authRepository.signUp(state.email, state.password, state.displayName)) {
                is AppResult.Success -> when (result.data) {
                    SignUpOutcome.SignedIn -> _events.send(Event.NavigateHome)
                    SignUpOutcome.VerificationRequired -> _uiState.update {
                        it.copy(verificationEmail = state.email)
                    }
                }

                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    fun resendVerification() {
        val email = _uiState.value.verificationEmail ?: _uiState.value.email
        launchSafely {
            when (val result = authRepository.resendVerificationEmail(email)) {
                is AppResult.Success -> _events.send(Event.Message("Verification email sent to $email"))
                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    // ------------------------------------------------------------------
    // Password reset
    // ------------------------------------------------------------------
    fun sendPasswordReset() {
        val state = _uiState.value
        val emailError = Validators.validateEmail(state.email)
        if (emailError != null) {
            _uiState.update { it.copy(emailError = emailError) }
            return
        }
        launchSafely {
            when (val result = authRepository.sendPasswordReset(state.email)) {
                is AppResult.Success -> _uiState.update { it.copy(resetLinkSent = true) }
                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    fun updatePassword(newPassword: String) {
        val error = Validators.validatePassword(newPassword)
        if (error != null) {
            _uiState.update { it.copy(passwordError = error) }
            return
        }
        launchSafely {
            when (val result = authRepository.updatePassword(newPassword)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(passwordUpdated = true) }
                    _events.send(Event.Message("Password updated. You're all set."))
                }

                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    private suspend fun handleFailure(message: String) {
        _uiState.update { it.copy(isLoading = false, formError = message) }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        _uiState.update { it.copy(isLoading = true, formError = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}

package com.minichat.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.SignUpOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
        val passwordUpdated: Boolean = false,

        // ---- email link verification ----
        /** Address awaiting a confirmation link; non-null shows the inbox screen. */
        val verificationEmail: String? = null,
        val verificationError: String? = null,
        /** Seconds until "Resend link" is allowed again. */
        val resendIn: Int = 0,
    ) {
        /** The "check your inbox" screen is active. */
        val isVerifying: Boolean get() = verificationEmail != null
    }

    sealed interface Event {
        data object NavigateHome : Event
        data class Message(val text: String) : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var resendJob: Job? = null

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
                    // Projects with "confirm email" off sign straight in.
                    SignUpOutcome.SignedIn -> _events.send(Event.NavigateHome)
                    // Supabase has already mailed the confirmation link during
                    // signUp; just explain what to do next.
                    SignUpOutcome.VerificationRequired -> showVerificationPending(state.email)
                }

                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    // ------------------------------------------------------------------
    // Email link verification
    // ------------------------------------------------------------------

    /**
     * Shows the inbox screen for [email] and starts the resend cooldown.
     *
     * No extra email is sent here: `signUp` already asked Supabase to send the
     * confirmation link, and requesting a second one immediately would just
     * duplicate it (and hit GoTrue's own mail throttle).
     */
    private fun showVerificationPending(email: String) {
        resendJob?.cancel()
        _uiState.update {
            it.copy(
                verificationEmail = email,
                verificationError = null,
                resendIn = RESEND_COOLDOWN_SECONDS,
            )
        }
        startResendCountdown()
    }

    private fun startResendCountdown() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            var remaining = RESEND_COOLDOWN_SECONDS
            while (remaining > 0) {
                _uiState.update { it.copy(resendIn = remaining) }
                kotlinx.coroutines.delay(1_000)
                remaining--
            }
            _uiState.update { it.copy(resendIn = 0) }
        }
    }

    /** Mails another confirmation link for the address on the pending screen. */
    fun resendVerification() {
        val email = _uiState.value.verificationEmail ?: return
        if (_uiState.value.resendIn > 0) return

        launchSafely {
            when (val result = authRepository.resendVerificationEmail(email)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(verificationError = null) }
                    startResendCountdown()
                    _events.send(Event.Message("Confirmation link sent to $email"))
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(verificationError = result.message)
                }
            }
        }
    }

    /** Leaves the inbox screen and returns to sign-in. */
    fun dismissVerification() {
        resendJob?.cancel()
        _uiState.update {
            it.copy(verificationEmail = null, verificationError = null, resendIn = 0)
        }
    }

    /**
     * From the sign-in screen: the address exists but was never confirmed, so
     * offer to mail the link again.
     */
    fun startEmailVerification() {
        val email = _uiState.value.email
        val emailError = Validators.validateEmail(email)
        if (emailError != null) {
            _uiState.update { it.copy(emailError = emailError) }
            return
        }
        launchSafely {
            when (val result = authRepository.resendVerificationEmail(email)) {
                is AppResult.Success -> {
                    showVerificationPending(email)
                    _events.send(Event.Message("Confirmation link sent to $email"))
                }

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

    override fun onCleared() {
        resendJob?.cancel()
        super.onCleared()
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

    companion object {
        /** Resend stays locked for this long so GoTrue's mail throttle is respected. */
        const val RESEND_COOLDOWN_SECONDS = 30
    }
}

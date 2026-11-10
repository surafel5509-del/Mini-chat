package com.minichat.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.OtpPurpose
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

        // ---- 6-digit code verification ----
        /** Address awaiting a code; non-null means the code screen is showing. */
        val otpEmail: String? = null,
        /** Why the code was requested, so Verify does the right thing after. */
        val otpPurpose: OtpPurpose = OtpPurpose.Signup,
        /** The digits the user has typed so far (always 0..6 chars). */
        val otpCode: String = "",
        val otpError: String? = null,
        /** Seconds until "Resend code" is allowed again. */
        val otpResendIn: Int = 0,
        /** The six boxes shake when a code is rejected. */
        val otpShake: Int = 0,
    ) {
        /** The code screen is active. */
        val isVerifying: Boolean get() = otpEmail != null
        val otpComplete: Boolean get() = otpCode.length == OTP_LENGTH
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
                    SignUpOutcome.VerificationRequired -> openOtpScreen(
                        email = state.email,
                        purpose = OtpPurpose.Signup,
                    )
                }

                is AppResult.Failure -> handleFailure(result.message)
            }
        }
    }

    // ------------------------------------------------------------------
    // 6-digit email verification
    // ------------------------------------------------------------------

    /**
     * Moves to the code screen and asks for a code.
     *
     * Signup already triggered Supabase's confirmation email during `signUp`, so
     * we only request another one when the user is coming from "forgot password"
     * or explicitly taps resend — otherwise the user would get two emails.
     */
    private suspend fun openOtpScreen(email: String, purpose: OtpPurpose, sendNow: Boolean = false) {
        _uiState.update {
            it.copy(
                otpEmail = email,
                otpPurpose = purpose,
                otpCode = "",
                otpError = null,
                otpResendIn = OTP_RESEND_SECONDS,
            )
        }
        startResendCountdown()

        if (sendNow) {
            when (val result = authRepository.requestOtp(email)) {
                is AppResult.Success -> _events.send(
                    Event.Message(result.data.message.ifBlank { "Code sent to $email" }),
                )

                is AppResult.Failure -> _uiState.update { it.copy(otpError = result.message) }
            }
        }
    }

    private fun startResendCountdown() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            var remaining = OTP_RESEND_SECONDS
            while (remaining > 0) {
                _uiState.update { it.copy(otpResendIn = remaining) }
                kotlinx.coroutines.delay(1_000)
                remaining--
            }
            _uiState.update { it.copy(otpResendIn = 0) }
        }
    }

    /** Typing in any of the six boxes. */
    fun onOtpChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(OTP_LENGTH)
        _uiState.update { it.copy(otpCode = digits, otpError = null, otpShake = 0) }
        // Auto-submit the moment the sixth digit lands — no button hunt.
        if (digits.length == OTP_LENGTH) verifyOtp()
    }

    /** Backspace from the last filled box. */
    fun onOtpBackspace() {
        val current = _uiState.value.otpCode
        if (current.isEmpty()) return
        _uiState.update { it.copy(otpCode = current.dropLast(1), otpError = null) }
    }

    fun verifyOtp() {
        val state = _uiState.value
        val email = state.otpEmail ?: return
        if (state.otpCode.length != OTP_LENGTH) {
            _uiState.update { it.copy(otpError = "Enter all six digits.") }
            return
        }

        launchSafely {
            when (val result = authRepository.verifyOtp(email, state.otpCode, state.otpPurpose)) {
                is AppResult.Success -> when (state.otpPurpose) {
                    OtpPurpose.Signup -> {
                        _uiState.update { it.copy(otpEmail = null, otpCode = "") }
                        _events.send(Event.Message("Email confirmed. Welcome to Mini Chat!"))
                        _events.send(Event.NavigateHome)
                    }

                    OtpPurpose.Recovery -> {
                        // Session is live now, so the reset screen can set a password.
                        _uiState.update { it.copy(otpEmail = null, otpCode = "", resetLinkSent = true) }
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        otpError = result.message,
                        otpShake = it.otpShake + 1,
                        otpCode = "",
                    )
                }
            }
        }
    }

    /**
     * From the sign-in screen: the address exists but was never confirmed, so
     * move straight to the code screen and ask Supabase for a fresh code.
     */
    fun startEmailVerification() {
        val email = _uiState.value.email
        val emailError = Validators.validateEmail(email)
        if (emailError != null) {
            _uiState.update { it.copy(emailError = emailError) }
            return
        }
        launchSafely {
            openOtpScreen(email = email, purpose = OtpPurpose.Signup, sendNow = true)
        }
    }

    fun resendOtp() {
        val email = _uiState.value.otpEmail ?: return
        if (_uiState.value.otpResendIn > 0) return
        launchSafely {
            when (val result = authRepository.requestOtp(email)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(otpError = null, otpCode = "") }
                    startResendCountdown()
                    _events.send(Event.Message("New code sent to $email"))
                }

                is AppResult.Failure -> _uiState.update { it.copy(otpError = result.message) }
            }
        }
    }

    /** Leaves the code screen without confirming. */
    fun dismissOtp() {
        resendJob?.cancel()
        _uiState.update {
            it.copy(otpEmail = null, otpCode = "", otpError = null, otpResendIn = 0)
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
            openOtpScreen(email = state.email, purpose = OtpPurpose.Recovery, sendNow = true)
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
        const val OTP_LENGTH = 6
        /** Codes are short-lived; resend stays locked for this long. */
        const val OTP_RESEND_SECONDS = 30
    }
}

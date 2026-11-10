package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.safeCall
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.AuthState
import com.minichat.app.domain.repository.SignUpOutcome
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : AuthRepository {

    override val authState: Flow<AuthState> = client.auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    val user = status.session.user
                    AuthState.SignedIn(
                        userId = user?.id.orEmpty(),
                        email = user?.email,
                        emailVerified = user?.emailConfirmedAt != null ||
                            user?.confirmedAt != null,
                    )
                }

                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.RefreshFailure -> AuthState.Loading
            }
        }
        .distinctUntilChanged()

    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    override fun currentEmail(): String? = client.auth.currentUserOrNull()?.email

    override suspend fun awaitReady() {
        // Blocks until the persisted session has been restored (or proven absent).
        runCatching { client.auth.awaitInitialization() }
    }

    override suspend fun signIn(email: String, password: String): AppResult<Unit> = safeCall {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
    ): AppResult<SignUpOutcome> = safeCall {
        val user = client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            this.data = buildJsonObject {
                put("display_name", displayName.trim())
            }
        }

        val hasSession = client.auth.currentSessionOrNull() != null
        when {
            hasSession -> SignUpOutcome.SignedIn
            user?.identities.isNullOrEmpty() -> {
                // Supabase returns an empty identity list for an existing confirmed address.
                throw IllegalStateException("An account with this email already exists. Try signing in instead.")
            }

            else -> SignUpOutcome.VerificationRequired
        }
    }

    override suspend fun signOut(): AppResult<Unit> = safeCall {
        client.auth.signOut()
    }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> = safeCall {
        client.auth.resetPasswordForEmail(
            email = email.trim(),
            redirectUrl = "minichat://auth-callback",
        )
    }

    override suspend fun resendVerificationEmail(email: String): AppResult<Unit> = safeCall {
        client.auth.resendEmail(
            type = OtpType.Email.SIGNUP,
            email = email.trim(),
            redirectUrl = "minichat://auth-callback",
        )
    }

    override suspend fun updatePassword(newPassword: String): AppResult<Unit> = safeCall {
        client.auth.updateUser {
            password = newPassword
        }
    }

    override suspend fun completeRecoveryLink(
        accessToken: String,
        refreshToken: String,
    ): AppResult<Unit> = safeCall {
        client.auth.importAuthToken(accessToken = accessToken, refreshToken = refreshToken)
    }

    override suspend fun refreshSession(): AppResult<Unit> = safeCall {
        if (client.auth.currentSessionOrNull() != null) {
            client.auth.refreshCurrentSession()
        }
    }
}

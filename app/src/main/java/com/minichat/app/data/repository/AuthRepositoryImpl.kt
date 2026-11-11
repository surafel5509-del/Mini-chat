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
import com.minichat.app.BuildConfig
import com.minichat.app.core.util.Validators
import com.minichat.app.domain.repository.OtpPurpose
import com.minichat.app.domain.repository.OtpSendInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
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
            // Without this the confirmation link falls back to the project's Site
            // URL — a web page — and the app is never resumed, so a new user taps
            // the link, sees a generic page, and is still unconfirmed when they
            // return. Pointing it at our scheme hands the session straight back.
            this.redirectUrl = "minichat://auth-callback"
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

    override fun hasSession(): Boolean = client.auth.currentSessionOrNull() != null

    // -------------------------------------------------------------------------
    // 6-digit email verification
    // -------------------------------------------------------------------------

    /**
     * Asks the `otp-guard` function for a code.
     *
     * The function (not the app) talks to GoTrue, so rate limits are enforced
     * server side against both the address and the caller's IP, and the service
     * role key never ships in the APK.
     */
    override suspend fun requestOtp(email: String): AppResult<OtpSendInfo> = safeCall {
        val address = email.trim().lowercase()
        if (Validators.validateEmail(address) != null) error("Enter a valid email address.")

        val response = callOtpGuard(
            buildJsonObject {
                put("action", "resend")
                put("email", address)
            },
        )

        if (response.optBoolean("ok", false).not()) {
            error(response.optString("message").ifBlank { "We couldn't send that code." })
        }
        OtpSendInfo(
            codeLength = response.optInt("codeLength", 6),
            expiresInMinutes = response.optInt("expiresInMinutes", 10),
            message = response.optString("message").ifBlank { "Code sent." },
        )
    }

    /**
     * Verifies a code and imports the session the function hands back.
     *
     * Importing (rather than trusting local state) means the very next request
     * is authenticated as the confirmed user, so onboarding can continue without
     * a second sign-in round trip.
     */
    override suspend fun verifyOtp(
        email: String,
        code: String,
        purpose: OtpPurpose,
    ): AppResult<Unit> = safeCall {
        val address = email.trim().lowercase()
        val digits = code.filter { it.isDigit() }
        if (digits.length != CODE_LENGTH) error("Enter the 6-digit code from your email.")

        val response = callOtpGuard(
            buildJsonObject {
                put("action", "verify")
                put("email", address)
                put("code", digits)
            },
        )

        if (response.optBoolean("ok", false).not()) {
            error(response.optString("message").ifBlank { "That code isn't right." })
        }

        val accessToken = response.optString("accessToken").takeIf { it.isNotBlank() }
            ?: error("Your email is confirmed — please sign in.")
        val refreshToken = response.optString("refreshToken").takeIf { it.isNotBlank() }
            ?: error("Your email is confirmed — please sign in.")

        client.auth.importAuthToken(accessToken = accessToken, refreshToken = refreshToken)

        // Recovery only needed the code to prove ownership; a confirmed signup
        // keeps the session so the profile can be completed right away.
        if (purpose == OtpPurpose.Recovery) {
            // Leave the session in place: updatePassword runs against it.
        }
    }

    /**
     * POSTs to the `otp-guard` function and returns its JSON body.
     *
     * A plain [HttpURLConnection] keeps this off the Supabase client's request
     * pipeline: the function is deliberately callable *before* a session exists
     * (it is deployed with `--no-verify-jwt`), so it must not pick up session
     * headers or auth refresh behaviour. The publishable key is sent because the
     * gateway expects it, not because the function trusts it.
     */
    private suspend fun callOtpGuard(body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/otp-guard"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
            }

            try {
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                runCatching {
                    Json.parseToJsonElement(raw).jsonObject
                }.getOrElse {
                    throw IllegalStateException(
                        "Verification is unavailable right now. Please try again.",
                    )
                }
            } catch (network: java.io.IOException) {
                throw IllegalStateException(
                    "Can't reach Mini Chat. Check your connection and try again.",
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun JsonObject.optBoolean(key: String, fallback: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

    private fun JsonObject.optInt(key: String, fallback: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: fallback

    private fun JsonObject.optString(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

    private companion object {
        const val CODE_LENGTH = 6
    }
}

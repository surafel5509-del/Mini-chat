package com.minichat.app.core.util

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnknownRestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Every repository call returns one of these. The UI layer never sees raw exceptions, which keeps
 * ViewModels free of Android/Supabase specifics and makes error copy consistent.
 */
sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val isNetwork: Boolean = false,
    ) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppResult.Failure) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(this)
    return this
}

/**
 * Runs a suspending block and converts every failure into a human readable [AppResult.Failure].
 * Cancellation is always rethrown so structured concurrency keeps working.
 */
suspend fun <T> safeCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    AppResult.Failure(
        message = throwable.toUserMessage(),
        cause = throwable,
        isNetwork = throwable.isNetworkProblem(),
    )
}

fun Throwable.isNetworkProblem(): Boolean =
    this is IOException ||
        this is HttpRequestException ||
        (message?.contains("Unable to resolve host", ignoreCase = true) == true) ||
        (message?.contains("timeout", ignoreCase = true) == true) ||
        (message?.contains("connect", ignoreCase = true) == true && this !is RestException)

/** Maps backend / transport failures onto copy a person can actually act on. */
fun Throwable.toUserMessage(): String {
    val raw = message.orEmpty()
    return when {
        this is HttpRequestException || this is IOException ->
            "You appear to be offline. Check your connection and try again."

        this is RestException -> when {
            raw.contains("Invalid login credentials", ignoreCase = true) ->
                "That email and password combination doesn't match."

            raw.contains("Email not confirmed", ignoreCase = true) ->
                "Please verify your email address before signing in."

            raw.contains("User already registered", ignoreCase = true) ->
                "An account with this email already exists. Try signing in instead."

            raw.contains("Password should be at least", ignoreCase = true) ->
                "Choose a stronger password (at least 8 characters)."

            raw.contains("For security purposes", ignoreCase = true) ->
                "Too many attempts. Please wait a moment and try again."

            raw.contains("duplicate key", ignoreCase = true) && raw.contains("username") ->
                "That username is already taken. Try another one."

            raw.contains("you cannot message this user", ignoreCase = true) ->
                "You can't message this person right now."

            raw.contains("this conversation is blocked", ignoreCase = true) ->
                "This conversation is blocked. Unblock the user to continue."

            raw.contains("row-level security", ignoreCase = true) ->
                "You don't have permission to do that."

            raw.contains("message content cannot be empty", ignoreCase = true) ->
                "Messages can't be empty."

            raw.contains("Failed to fetch", ignoreCase = true) ->
                "The server is unreachable. Try again in a moment."

            else -> raw.ifBlank { "Something went wrong. Please try again." }
        }

        this is SerializationException -> "We couldn't read the server response. Please update the app."

        this is UnknownRestException -> raw.ifBlank { "Something went wrong. Please try again." }

        raw.isBlank() -> "Something went wrong. Please try again."

        else -> raw
    }
}

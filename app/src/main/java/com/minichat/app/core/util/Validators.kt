package com.minichat.app.core.util

/**
 * Client side validation. This exists to give instant, friendly feedback — the database is the
 * source of truth and re-validates every rule with constraints and triggers.
 */
object Validators {

    private val emailPattern = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    private val usernamePattern = Regex("^[a-z][a-z0-9_]{2,23}$")

    const val MAX_MESSAGE_LENGTH = 4000
    const val MAX_BIO_LENGTH = 240
    const val MAX_DISPLAY_NAME_LENGTH = 48

    fun validateEmail(email: String): String? {
        val value = email.trim()
        return when {
            value.isEmpty() -> "Enter your email address"
            !emailPattern.matches(value) -> "That doesn't look like a valid email"
            else -> null
        }
    }

    fun validatePassword(password: String): String? = when {
        password.isEmpty() -> "Enter your password"
        password.length < 8 -> "Use at least 8 characters"
        password.none { it.isDigit() } || password.none { it.isLetter() } ->
            "Mix letters and numbers for a stronger password"
        else -> null
    }

    /** Only used at sign-in, where legacy passwords may predate the current rules. */
    fun validateLoginPassword(password: String): String? = when {
        password.isEmpty() -> "Enter your password"
        password.length < 6 -> "Passwords are at least 6 characters"
        else -> null
    }

    fun validateDisplayName(name: String): String? {
        val value = name.trim()
        return when {
            value.isEmpty() -> "Add a display name"
            value.length > MAX_DISPLAY_NAME_LENGTH -> "Keep it under $MAX_DISPLAY_NAME_LENGTH characters"
            else -> null
        }
    }

    fun validateUsername(username: String): String? {
        // Validate the raw, trimmed value — do NOT lowercase here. Silently
        // accepting "Ada" hides a real problem: the database stores usernames
        // verbatim, so an auto-capitalised keyboard would create a handle that
        // never matches what the person sees. Reject it and say why.
        val value = username.trim()
        return when {
            value.isEmpty() -> "Pick a username"
            value.length > 24 -> "Usernames are at most 24 characters"
            value.length < 3 -> "Usernames are at least 3 characters"
            value != value.lowercase() -> "Use lowercase letters only (try '${value.lowercase()}')"
            !usernamePattern.matches(value) ->
                "Start with a letter. Lowercase letters, numbers and underscores only"
            else -> null
        }
    }

    /**
     * Live input sanitiser for the username field: lowercases, drops illegal
     * characters, and guarantees the value starts with a letter so the field is
     * practically impossible to make invalid by typing.
     */
    fun sanitizeUsername(raw: String): String {
        val cleaned = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        val withoutLeadingNonLetters = cleaned.dropWhile { !it.isLetter() }
        return withoutLeadingNonLetters.take(24)
    }

    fun validateBio(bio: String): String? = when {
        bio.length > MAX_BIO_LENGTH -> "Bios are limited to $MAX_BIO_LENGTH characters"
        else -> null
    }

    fun validateMessage(raw: String): String? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> "Type a message first"
            value.length > MAX_MESSAGE_LENGTH -> "Messages are limited to $MAX_MESSAGE_LENGTH characters"
            else -> null
        }
    }

    fun validateReportReason(reason: String): String? = when {
        reason.trim().length < 3 -> "Tell us what happened"
        reason.length > 500 -> "Keep the description under 500 characters"
        else -> null
    }
}

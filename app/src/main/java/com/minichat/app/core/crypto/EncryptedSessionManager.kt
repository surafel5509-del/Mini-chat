package com.minichat.app.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Supabase session in hardware-backed storage.
 *
 * The refresh token is the most sensitive value the app holds, so it is stored with
 * AES-256 GCM keys kept in the Android Keystore instead of a plain SharedPreferences file.
 * If the keystore is unavailable (rare, e.g. some emulators) we degrade to a normal private
 * preference file rather than losing the ability to sign in.
 */
@Singleton
class EncryptedSessionManager @Inject constructor(
    context: Context,
) : SessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val preferences: SharedPreferences = createPreferences(context.applicationContext)

    override suspend fun saveSession(session: UserSession) {
        val encoded = json.encodeToString(UserSession.serializer(), session)
        preferences.edit().putString(KEY_SESSION, encoded).apply()
    }

    override suspend fun loadSession(): UserSession {
        val encoded = preferences.getString(KEY_SESSION, null)
            ?: throw NoSessionFoundException()
        return runCatching { json.decodeFromString(UserSession.serializer(), encoded) }
            .getOrElse { error ->
                Log.w(TAG, "Stored session could not be read, clearing it", error)
                preferences.edit().remove(KEY_SESSION).apply()
                throw NoSessionFoundException()
            }
    }

    override suspend fun deleteSession() {
        preferences.edit().remove(KEY_SESSION).apply()
    }

    private fun createPreferences(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        Log.e(TAG, "Encrypted storage unavailable, falling back to private preferences", error)
        context.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
    }

    private companion object {
        const val TAG = "EncryptedSessionManager"
        const val FILE_ENCRYPTED = "minichat_secure_session"
        const val FILE_FALLBACK = "minichat_session_fallback"
        const val KEY_SESSION = "supabase_session"
    }
}

package com.minichat.app.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.minichat.app.BuildConfig
import com.minichat.app.domain.repository.ProfileRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Keeps the FCM token of this device registered against the signed-in user.
 *
 * Push is entirely optional: when [BuildConfig.PUSH_NOTIFICATIONS_ENABLED] is false (the default
 * until a Firebase project is configured) nothing here runs and the app stays fully functional.
 */
@Singleton
class PushTokenManager @Inject constructor(
    private val profileRepository: ProfileRepository,
) {

    suspend fun syncCurrentToken() {
        if (!BuildConfig.PUSH_NOTIFICATIONS_ENABLED) return
        val token = currentToken() ?: return
        registerToken(token)
    }

    suspend fun registerToken(token: String) {
        if (!BuildConfig.PUSH_NOTIFICATIONS_ENABLED) return
        profileRepository.registerDeviceToken(token)
            .onFailure { Log.w(TAG, "Could not register push token: ${it.message}") }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (continuation.isActive) continuation.resume(token) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "FCM token unavailable", error)
                    if (continuation.isActive) continuation.resume(null)
                }
        }.onFailure { error ->
            Log.w(TAG, "Firebase not initialised", error)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private companion object {
        const val TAG = "PushTokenManager"
    }
}

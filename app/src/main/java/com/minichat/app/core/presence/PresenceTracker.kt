package com.minichat.app.core.presence

import android.util.Log
import com.minichat.app.data.realtime.RealtimeService
import com.minichat.app.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.minichat.app.core.util.onFailure

/**
 * Foreground presence.
 *
 * While the app is visible we refresh `profiles.last_seen_at` every 45 seconds and keep the
 * Realtime socket warm. Other members derive "Online" from a fresh heartbeat (see
 * [com.minichat.app.core.util.TimeFormat.isOnline]), so presence survives process death, poor
 * networks and multiple devices without a separate presence protocol.
 */
@Singleton
class PresenceTracker @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val realtimeService: RealtimeService,
) {

    private var heartbeatJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (heartbeatJob?.isActive == true) return
        realtimeService.ensureRealtimeConnected()
        runCatching { realtimeService.startChatStream() }
        heartbeatJob = scope.launch {
            while (isActive) {
                profileRepository.heartbeat()
                    .onFailure { Log.d(TAG, "heartbeat failed: ${it.message}") }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private companion object {
        const val TAG = "PresenceTracker"
        const val HEARTBEAT_INTERVAL_MS = 45_000L
    }
}

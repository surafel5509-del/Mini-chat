package com.minichat.app.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minichat.app.core.datastore.UserPreferences
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.AuthState
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.MessageEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Delivers message notifications over Supabase Realtime instead of Firebase.
 *
 * ## Why a foreground service
 *
 * Android will not let an app hold a socket open indefinitely in the background;
 * the process gets frozen or killed. A `dataSync` foreground service is the
 * supported way to keep work — here, one WebSocket — alive while the app is not
 * on screen, and it is what messaging apps including Telegram and Signal do.
 *
 * ## How a message becomes a notification
 *
 * 1. The service subscribes to `messages` over Realtime (already enabled, with
 *    `REPLICA IDENTITY FULL` so inserts carry the full row).
 * 2. Each insert is checked against the local mute/pin state and the reader's own
 *    id (you never get notified about your own message).
 * 3. [NotificationHelper] posts a grouped notification with a reply-friendly
 *    deep link; the user's sound / vibration / preview settings are applied last.
 *
 * Latency is whatever the socket delivers — typically well under a second, and
 * faster than a server-side push round trip because nothing has to be minted,
 * routed and delivered through a third party.
 */
@AndroidEntryPoint
class RealtimeNotificationService : Service() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var notificationHelper: NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_ID, buildQuietNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (listeningJob?.isActive != true) {
            listeningJob = scope.launch { listen() }
        }
        // If the system kills us, bring the socket back when there is memory again.
        return START_STICKY
    }

    private suspend fun listen() {
        // Wait for the persisted session so the realtime socket can authenticate.
        val signedIn = authRepository.authState.first { it !is AuthState.Loading }
        if (signedIn !is AuthState.SignedIn) {
            stopSelf()
            return
        }
        val myId = signedIn.userId

        while (scope.isActive) {
            try {
                chatRepository.allMessageEvents().collect { event ->
                    when (event) {
                        is MessageEvent.Inserted -> notifyFor(event.message, myId)
                        else -> Unit
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Realtime channel dropped, reconnecting", error)
            }
            // The flow ends if the channel errors; back off briefly before retrying
            // so a flapping network does not spin the CPU.
            delay(RECONNECT_DELAY_MS)
        }
    }

    private suspend fun notifyFor(message: com.minichat.app.domain.model.Message, myId: String) {
        if (message.senderId == myId) return
        if (message.isDeleted || message.isSystem) return

        val settings = userPreferences.settings.first()
        if (!settings.notificationsEnabled) return

        // Muted conversations still update the inbox, they just stay silent.
        val summary = runCatching { chatRepository.loadConversation(message.conversationId) }
            .getOrNull()
            ?.let { if (it is com.minichat.app.core.util.AppResult.Success) it.data else null }

        if (summary?.isMuted == true) return

        val senderName = message.senderName.takeIf { it.isNotBlank() }
            ?: summary?.displayName
            ?: "New message"

        val preview = when {
            message.isVoice -> "🎙 Voice message"
            message.content.isBlank() -> "Attachment"
            else -> message.content
        }

        notificationHelper.showMessageNotification(
            conversationId = message.conversationId,
            senderName = if (summary?.isGroup == true) summary.displayName else senderName,
            messagePreview = preview,
            showPreview = settings.previewInNotifications,
            soundEnabled = settings.soundEnabled,
            vibrationEnabled = settings.vibrationEnabled,
        )
    }

    /**
     * The service needs *a* notification to be foreground, but a message service
     * should not advertise itself: minimum importance, no sound, no icon badge.
     */
    private fun buildQuietNotification() =
        NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Mini Chat")
            .setContentText("Keeping your messages up to date")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        listeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RealtimeNotify"
        private const val FOREGROUND_ID = 0x4D43 // "MC"
        private const val RECONNECT_DELAY_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, RealtimeNotificationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "Could not start notification service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RealtimeNotificationService::class.java)) }
        }
    }
}

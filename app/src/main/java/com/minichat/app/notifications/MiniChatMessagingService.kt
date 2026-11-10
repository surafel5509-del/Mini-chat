package com.minichat.app.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.minichat.app.core.datastore.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives FCM pushes sent by the `push-notification` Supabase Edge Function.
 *
 * The payload carries `conversation_id`, `sender_name`, `preview` and `muted`. Muted
 * conversations are dropped on the device as well as on the server, and the user's sound /
 * vibration / preview preferences are applied last so the phone always wins.
 */
@AndroidEntryPoint
class MiniChatMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var pushTokenManager: PushTokenManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch { pushTokenManager.registerToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val conversationId = data["conversation_id"]
        val senderName = data["sender_name"] ?: message.notification?.title ?: "New message"
        val preview = data["preview"] ?: message.notification?.body ?: "Sent you a message"
        val muted = data["muted"].toBoolean()

        if (conversationId == null || muted) {
            Log.d(TAG, "Dropping push (conversationId=$conversationId, muted=$muted)")
            return
        }

        scope.launch {
            val settings = userPreferences.settings.first()
            if (!settings.notificationsEnabled) return@launch
            notificationHelper.showMessageNotification(
                conversationId = conversationId,
                senderName = senderName,
                messagePreview = preview,
                showPreview = settings.previewInNotifications,
                soundEnabled = settings.soundEnabled,
                vibrationEnabled = settings.vibrationEnabled,
            )
        }
    }

    private companion object {
        const val TAG = "MiniChatMessaging"
    }
}

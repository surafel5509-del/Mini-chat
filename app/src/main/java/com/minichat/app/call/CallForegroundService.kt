package com.minichat.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.minichat.app.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint

/**
 * Keeps a connected call alive (microphone + camera) while the app is in the background, as
 * required by Android 11+ background-execution limits for media capture.
 *
 * The service is intentionally dumb: it owns no call state, it just holds the process in the
 * foreground with the correct service types and an ongoing notification.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Call in progress"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Tap to return to the call"
        val notification = NotificationHelper.ongoingCallNotification(this, callId, title, subtitle)

        val isVideo = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Declaring the camera type without holding the camera permission throws on Android 14+,
            // so only video calls claim it.
            if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, type)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 4242
        const val ACTION_STOP = "com.minichat.app.call.STOP"
        private const val EXTRA_CALL_ID = "extra_call_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_VIDEO = "extra_video"

        fun start(
            context: Context,
            callId: String,
            title: String,
            subtitle: String,
            isVideo: Boolean,
        ) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_VIDEO, isVideo)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        }
    }
}

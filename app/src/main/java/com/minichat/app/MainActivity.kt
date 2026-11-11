package com.minichat.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.minichat.app.core.presence.PresenceTracker
import com.minichat.app.core.util.onSuccess
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.navigation.MiniChatNavHost
import com.minichat.app.notifications.RealtimeNotificationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val rootViewModel: RootViewModel by viewModels()

    @Inject lateinit var presenceTracker: PresenceTracker
    @Inject lateinit var authRepository: com.minichat.app.domain.repository.AuthRepository

    private var deepLinkConversationId by mutableStateOf<String?>(null)
    private var recoveryLinkPending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash screen until the persisted session has been resolved, so the app never
        // flashes the welcome screen for an already signed-in user (or the wrong theme).
        splashScreen.setKeepOnScreenCondition {
            rootViewModel.uiState.value.authState is com.minichat.app.domain.repository.AuthState.Loading
        }

        handleIntent(intent)

        setContent {
            val state by rootViewModel.uiState.collectAsStateWithLifecycle()

            val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { /* result handled by the OS; notifications simply stay off if denied */ }

            LaunchedEffect(Unit) {
                // Notifications are opt-in at the system level; asking here (rather
                // than at first message) means the prompt appears with context.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MiniChatTheme(themeMode = state.settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MiniChatNavHost(
                        rootViewModel = rootViewModel,
                        deepLinkConversationId = deepLinkConversationId,
                        onDeepLinkConsumed = { deepLinkConversationId = null },
                        passwordResetPending = recoveryLinkPending,
                        onPasswordResetOpened = { recoveryLinkPending = false },
                    )
                }
            }
        }

        observeSessionForPresence()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "minichat") return
        when (data.host) {
            "chat" -> deepLinkConversationId = data.pathSegments.firstOrNull()

            "auth-callback" -> {
                // Supabase appends the session to the fragment: #access_token=…&refresh_token=…
                val raw = data.fragment ?: data.query ?: return
                val params = raw.split('&').mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
                }.toMap()
                val accessToken = params["access_token"] ?: return
                val refreshToken = params["refresh_token"] ?: return

                // The same deep link serves two emails. Supabase tags it with
                // type=recovery for a password reset and type=signup for address
                // confirmation (it also sends type=email_change and type=invite).
                // Only a recovery link should drop the user on the "choose a new
                // password" screen — a brand-new user would otherwise be asked to
                // reset a password they had just created, and never reach the app.
                val isRecovery = params["type"] == "recovery"
                lifecycleScope.launch {
                    authRepository.completeRecoveryLink(accessToken, refreshToken)
                        .onSuccess { recoveryLinkPending = isRecovery }
                }
            }
        }
    }

    private fun observeSessionForPresence() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                rootViewModel.uiState.collect { state ->
                    if (state.authState is com.minichat.app.domain.repository.AuthState.SignedIn) {
                        presenceTracker.start(lifecycleScope)
                        // Hold the realtime socket so messages arrive while the
                        // app is not on screen.
                        if (state.settings.notificationsEnabled) {
                            RealtimeNotificationService.start(this@MainActivity)
                        }
                    } else {
                        presenceTracker.stop()
                        RealtimeNotificationService.stop(this@MainActivity)
                    }
                }
            }
        }
    }
}

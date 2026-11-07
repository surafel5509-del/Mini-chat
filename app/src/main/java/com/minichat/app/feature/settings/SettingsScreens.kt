package com.minichat.app.feature.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.BuildConfig
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.design.components.ConfirmDialog
import com.minichat.app.design.components.EmptyState
import com.minichat.app.design.components.GhostButton
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.MiniChatMark
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.components.SectionHeader
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.components.SwitchRow
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.design.theme.ThemeMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onAppearance: () -> Unit,
    onNotifications: () -> Unit,
    onPrivacy: () -> Unit,
    onBlockedUsers: () -> Unit,
    onAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val (settings, profile) = viewModel.combined.collectAsStateWithLifecycle().value
    var showLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        MiniChatTopBar(title = "Settings", onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(indication = LocalIndication.current, onClick = onEditProfile)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniChatAvatar(
                displayName = profile?.displayName ?: "You",
                username = profile?.username.orEmpty(),
                avatarUrl = profile?.avatarUrl,
                size = 56.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.displayName ?: "Your profile",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = profile?.handle ?: "Tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
            Text(
                text = "Edit",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MiniChatColors.AmberSecondary,
            )
        }

        SectionHeader("Account")
        SettingsRow(
            icon = Icons.Rounded.Person,
            title = "Edit profile",
            subtitle = "Photo, name, username and bio",
            onClick = onEditProfile,
        )
        SettingsRow(
            icon = Icons.Rounded.Email,
            title = "Email address",
            subtitle = profile?.let { "Signed in" } ?: "—",
            onClick = null,
        )
        SettingsRow(
            icon = Icons.Rounded.Block,
            title = "Blocked users",
            onClick = onBlockedUsers,
        )

        SectionHeader("Privacy")
        SettingsRow(
            icon = Icons.Rounded.Lock,
            title = "Privacy & safety",
            subtitle = "Read receipts, typing indicators",
            onClick = onPrivacy,
        )

        SectionHeader("Notifications")
        SwitchRow(
            icon = Icons.Rounded.Notifications,
            title = "Push notifications",
            subtitle = "New messages on this device",
            checked = settings.notificationsEnabled,
            onCheckedChange = viewModel::setNotifications,
        )
        SettingsRow(
            icon = Icons.Rounded.VolumeUp,
            title = "Sounds & vibration",
            onClick = onNotifications,
        )

        SectionHeader("Appearance")
        SettingsRow(
            icon = Icons.Rounded.Palette,
            title = "Theme",
            subtitle = when (settings.themeMode) {
                ThemeMode.Light -> "Light"
                ThemeMode.Dark -> "Dark"
                ThemeMode.System -> "Follow system"
            },
            onClick = onAppearance,
        )

        SectionHeader("About")
        SettingsRow(
            icon = Icons.Rounded.Info,
            title = "About MINI CHAT",
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
            onClick = onAbout,
        )
        SettingsRow(
            icon = Icons.Rounded.Logout,
            title = "Sign out",
            onClick = { showLogout = true },
            iconTint = MiniChatTheme.extra.danger,
            iconBackground = MiniChatTheme.extra.danger.copy(alpha = 0.12f),
            titleColor = MiniChatTheme.extra.danger,
        )
        Spacer(Modifier.height(34.dp))
    }

    if (showLogout) {
        ConfirmDialog(
            title = "Sign out?",
            message = "You'll need to sign in again to read your messages.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                showLogout = false
                viewModel.signOut()
            },
            onDismiss = { showLogout = false },
        )
    }
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "Appearance", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "Choose how MINI CHAT looks. Your choice is remembered on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(20.dp))

            ThemeOption(
                title = "Light",
                description = "Bright surfaces with yellow accents",
                icon = Icons.Rounded.LightMode,
                selected = settings.themeMode == ThemeMode.Light,
                onClick = { viewModel.setThemeMode(ThemeMode.Light) },
            )
            Spacer(Modifier.height(10.dp))
            ThemeOption(
                title = "Dark",
                description = "Deep charcoal, easy on the eyes",
                icon = Icons.Rounded.DarkMode,
                selected = settings.themeMode == ThemeMode.Dark,
                onClick = { viewModel.setThemeMode(ThemeMode.Dark) },
            )
            Spacer(Modifier.height(10.dp))
            ThemeOption(
                title = "Follow system",
                description = "Match your device setting automatically",
                icon = Icons.Rounded.SettingsBrightness,
                selected = settings.themeMode == ThemeMode.System,
                onClick = { viewModel.setThemeMode(ThemeMode.System) },
            )
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MiniChatColors.YellowPrimary else MiniChatTheme.extra.divider,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(indication = LocalIndication.current, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiniChatColors.YellowPrimary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MiniChatColors.OnYellow, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) MiniChatColors.YellowPrimary else MiniChatTheme.extra.divider,
                    shape = CircleShape,
                )
                .padding(4.dp),
        ) {
            if (selected) {
                Box(modifier = Modifier.fillMaxSize().background(MiniChatColors.YellowPrimary, CircleShape))
            }
        }
    }
}

@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "Notifications", onBack = onBack)
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SwitchRow(
                icon = Icons.Rounded.Notifications,
                title = "Push notifications",
                subtitle = "Master switch for this device",
                checked = settings.notificationsEnabled,
                onCheckedChange = viewModel::setNotifications,
            )
            SwitchRow(
                icon = Icons.Rounded.VolumeUp,
                title = "Sound",
                checked = settings.soundEnabled,
                onCheckedChange = viewModel::setSound,
                enabled = settings.notificationsEnabled,
            )
            SwitchRow(
                icon = Icons.Rounded.Vibration,
                title = "Vibration",
                checked = settings.vibrationEnabled,
                onCheckedChange = viewModel::setVibration,
                enabled = settings.notificationsEnabled,
            )
            SwitchRow(
                icon = Icons.Rounded.Visibility,
                title = "Show message previews",
                subtitle = "Hide text on the lock screen if you prefer",
                checked = settings.previewInNotifications,
                onCheckedChange = viewModel::setPreviews,
                enabled = settings.notificationsEnabled,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Muted conversations never notify you, even when the master switch is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onBlockedUsers: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "Privacy & safety", onBack = onBack)
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SwitchRow(
                icon = Icons.Rounded.Visibility,
                title = "Read receipts",
                subtitle = "Show the other person when you've read their message",
                checked = settings.readReceiptsEnabled,
                onCheckedChange = viewModel::setReadReceipts,
            )
            SwitchRow(
                icon = Icons.Rounded.Person,
                title = "Typing indicators",
                subtitle = "Let the other person see when you're typing",
                checked = settings.typingIndicatorsEnabled,
                onCheckedChange = viewModel::setTypingIndicators,
            )
            SettingsRow(
                icon = Icons.Rounded.Block,
                title = "Blocked users",
                onClick = onBlockedUsers,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Messages are protected by row level security in Postgres: only the two members of a conversation can ever read its messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
fun BlockedUsersScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.blocked.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadBlockedUsers() }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "Blocked users", onBack = onBack)

        when {
            state.isLoading -> MiniChatLoader()
            state.error != null -> InlineError(message = state.error!!, modifier = Modifier.padding(20.dp))
            state.users.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Block,
                title = "Nobody is blocked",
                message = "When you block someone they can't message you and their messages stay hidden.",
            )

            else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                state.users.forEach { blocked ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniChatAvatar(
                            displayName = blocked.profile.displayName,
                            username = blocked.profile.username,
                            avatarUrl = blocked.profile.avatarUrl,
                            size = 46.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = blocked.profile.displayName,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = blocked.profile.handle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MiniChatTheme.extra.textSecondary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiniChatTheme.extra.surfaceMuted)
                                .clickable(indication = LocalIndication.current) {
                                    viewModel.unblock(blocked)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Unblock",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MiniChatTopBar(title = "About", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            MiniChatMark(size = 88.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "MINI CHAT",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Fast. Simple. Connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "A lightweight, native Android messenger built with Kotlin, Jetpack Compose and Supabase. Messages, reactions and read receipts travel over Supabase Realtime; every conversation is protected by Postgres row level security.",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            InfoPill("Version", BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(8.dp))
            InfoPill("Build", if (BuildConfig.DEBUG) "Debug" else "Release")
            Spacer(Modifier.height(26.dp))
            GhostButton(text = "Back", onClick = onBack)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MiniChatTheme.extra.surfaceMuted)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

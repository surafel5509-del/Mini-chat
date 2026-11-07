package com.minichat.app.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.core.util.Validators
import com.minichat.app.design.components.ConfirmDialog
import com.minichat.app.design.components.GhostButton
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.components.ReportUserDialog
import com.minichat.app.design.components.SettingsRow
import com.minichat.app.design.components.SectionHeader
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLogout by remember { mutableStateOf(false) }

    if (state.isLoading && state.profile == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            MiniChatTopBar(title = "Profile", onBack = onBack)
            MiniChatLoader()
        }
        return
    }

    val profile = state.profile
    if (profile == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            MiniChatTopBar(title = "Profile", onBack = onBack)
            InlineError(
                message = state.error ?: "We couldn't load your profile.",
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MiniChatTopBar(
            title = "Profile",
            onBack = onBack,
            actions = {
                com.minichat.app.design.components.MiniChatIconButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            MiniChatAvatar(
                displayName = profile.displayName,
                username = profile.username,
                avatarUrl = profile.avatarUrl,
                size = 104.dp,
                borderWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = profile.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatColors.AmberSecondary,
            )
            if (!profile.bio.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = profile.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiniChatTheme.extra.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                text = "Edit profile",
                icon = Icons.Rounded.Edit,
                onClick = onEditProfile,
                fillWidth = false,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiniChatTheme.extra.surfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MiniChatTheme.extra.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = TimeFormat.lastSeen(profile.lastSeenAt?.toString(), isOnline = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
            Spacer(Modifier.height(26.dp))
        }

        SectionHeader("Account")
        SettingsRow(
            icon = Icons.Rounded.Edit,
            title = "Edit profile",
            subtitle = "Name, photo and bio",
            onClick = onEditProfile,
        )
        SettingsRow(
            icon = Icons.Rounded.Block,
            title = "Blocked users",
            subtitle = if (state.blockedCount == 0) "Nobody blocked" else "${state.blockedCount} blocked",
            onClick = onOpenSettings,
        )
        SettingsRow(
            icon = Icons.Rounded.Settings,
            title = "Settings",
            onClick = onOpenSettings,
        )
        SettingsRow(
            icon = Icons.Rounded.Logout,
            title = "Sign out",
            onClick = { showLogout = true },
            iconTint = MiniChatTheme.extra.danger,
            iconBackground = MiniChatTheme.extra.danger.copy(alpha = 0.12f),
            titleColor = MiniChatTheme.extra.danger,
        )
        Spacer(Modifier.height(28.dp))
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
fun UserProfileScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showReport by remember { mutableStateOf(false) }
    var showBlock by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "Profile", onBack = onBack)

        val profile = state.profile
        if (profile == null) {
            MiniChatLoader()
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            MiniChatAvatar(
                displayName = profile.displayName,
                username = profile.username,
                avatarUrl = profile.avatarUrl,
                size = 106.dp,
                online = TimeFormat.isOnline(profile.lastSeenAt?.toString()),
                showOnlineIndicator = true,
                borderWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = profile.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatColors.AmberSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = TimeFormat.lastSeen(
                    profile.lastSeenAt?.toString(),
                    TimeFormat.isOnline(profile.lastSeenAt?.toString()),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            if (!profile.bio.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = profile.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiniChatTheme.extra.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
            if (state.isBlocked) {
                GhostButton(
                    text = "Unblock",
                    onClick = viewModel::unblockUser,
                    fillWidth = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "You blocked this person. Unblock to message again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            } else {
                PrimaryButton(
                    text = "Send message",
                    icon = Icons.Rounded.Message,
                    onClick = { onMessage(profile.id) },
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "Block user",
                    icon = Icons.Rounded.Block,
                    onClick = { showBlock = true },
                )
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Report user",
                icon = Icons.Rounded.Flag,
                onClick = { showReport = true },
            )

            state.info?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.success,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(14.dp))
                InlineError(message = it)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showReport) {
        ReportUserDialog(
            displayName = state.profile?.displayName.orEmpty(),
            onDismiss = { showReport = false },
            onReport = { reason ->
                viewModel.report(reason)
                showReport = false
            },
        )
    }
    if (showBlock) {
        ConfirmDialog(
            title = "Block ${state.profile?.displayName.orEmpty()}?",
            message = "They won't be able to message you, and you won't see their messages.",
            confirmLabel = "Block",
            destructive = true,
            onConfirm = {
                viewModel.blockUser()
                showBlock = false
            },
            onDismiss = { showBlock = false },
        )
    }
}

@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                val extension = context.contentResolver.getType(uri)
                    ?.substringAfterLast('/')
                    ?.let { if (it == "jpeg") "jpg" else it }
                    ?: "jpg"
                viewModel.uploadAvatar(bytes, extension)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        MiniChatTopBar(title = "Edit profile", onBack = onBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { pickImage.launch("image/*") },
                ) {
                    MiniChatAvatar(
                        displayName = state.displayName.ifBlank { "You" },
                        username = state.username,
                        avatarUrl = state.profile?.avatarUrl,
                        size = 104.dp,
                        borderWidth = 3.dp,
                    )
                    if (state.isUploadingAvatar) {
                        Box(
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(MiniChatTheme.extra.scrimOverlay),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = MiniChatColors.YellowPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(start = 78.dp, top = 76.dp)
                        .size(32.dp)
                        .background(MiniChatColors.YellowPrimary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { pickImage.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = "Change photo",
                        tint = MiniChatColors.OnYellow,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            MiniChatTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = "Display name",
                placeholder = "How friends see you",
                error = state.nameError,
                leadingIcon = Icons.Rounded.Person,
            )
            Spacer(Modifier.height(16.dp))
            MiniChatTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = "Username",
                placeholder = "lowercase_letters",
                error = state.usernameError,
                leadingIcon = Icons.Rounded.Edit,
                supportingText = "3–24 characters: lowercase letters, numbers, underscores",
            )
            Spacer(Modifier.height(16.dp))
            MiniChatTextField(
                value = state.bio,
                onValueChange = viewModel::onBioChange,
                label = "Bio",
                placeholder = "A sentence about you",
                error = state.bioError,
                singleLine = false,
                minLines = 3,
                maxLines = 4,
                supportingText = "${state.bio.length}/${Validators.MAX_BIO_LENGTH}",
            )

            state.error?.let {
                Spacer(Modifier.height(14.dp))
                InlineError(message = it)
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "Save changes",
                onClick = { viewModel.saveProfile(onSaved) },
                loading = state.isSaving,
                enabled = state.canSave,
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ProfileStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiniChatTheme.extra.divider),
    )
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MiniChatTheme.extra.textSecondary,
    )
    Spacer(Modifier.height(6.dp))
}

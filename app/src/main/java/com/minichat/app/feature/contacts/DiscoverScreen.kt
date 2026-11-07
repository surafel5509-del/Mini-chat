package com.minichat.app.feature.contacts

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.EmptyState
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatSearchField
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.components.ReportUserDialog
import com.minichat.app.design.components.SectionHeader
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Profile

@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    var reportUser by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(navigation) {
        navigation?.let { destination ->
            viewModel.consumeNavigation()
            onOpenConversation(destination.conversationId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        MiniChatTopBar(
            title = "New chat",
            subtitle = "Find people by name or @username",
            onBack = onBack,
        )

        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            MiniChatSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search MINI CHAT",
                leadingIcon = Icons.Rounded.Search,
            )
        }

        state.error?.let { error ->
            Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                InlineError(message = error)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading && state.results.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MiniChatColors.YellowPrimary, strokeWidth = 3.dp)
                    }
                }

                state.isEmptyResult -> EmptyState(
                    icon = Icons.Rounded.PersonSearch,
                    title = "No one found",
                    message = "Check the spelling, or try their @username.",
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                    ) {
                        if (state.showRecents && state.recent.isNotEmpty()) {
                            item { SectionHeader("Recent") }
                            items(items = state.recent, key = { it.id }) { profile ->
                                PersonRow(
                                    profile = profile,
                                    busy = state.openingUserId == profile.id,
                                    onClick = { viewModel.startConversation(profile) },
                                    onLongClick = { onOpenProfile(profile.id) },
                                    onReport = { reportUser = profile },
                                )
                            }
                        }
                        if (!state.showRecents) {
                            item { SectionHeader("Results") }
                            items(items = state.results, key = { it.id }) { profile ->
                                PersonRow(
                                    profile = profile,
                                    busy = state.openingUserId == profile.id,
                                    onClick = { viewModel.startConversation(profile) },
                                    onLongClick = { onOpenProfile(profile.id) },
                                    onReport = { reportUser = profile },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    reportUser?.let { profile ->
        ReportUserDialog(
            displayName = profile.displayName,
            onDismiss = { reportUser = null },
            onReport = { reason ->
                viewModel.report(profile, reason)
                reportUser = null
            },
        )
    }
}

@Composable
private fun PersonRow(
    profile: Profile,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = profile.displayName,
            username = profile.username,
            avatarUrl = profile.avatarUrl,
            size = 48.dp,
            online = com.minichat.app.core.util.TimeFormat.isOnline(profile.lastSeenAt?.toString()),
            showOnlineIndicator = true,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.handle,
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MiniChatColors.AmberSecondary,
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiniChatColors.YellowPrimary.copy(alpha = 0.20f))
                    .clickable(indication = LocalIndication.current, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Say hi",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (MiniChatTheme.extra.isDark) MiniChatColors.YellowPrimary else MiniChatColors.OnYellow,
                )
            }
        }
    }
}

package com.minichat.app.feature.call.group

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.MiniChatSearchField
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.domain.model.CallType

/**
 * Compose a group call: pick invitees, optionally name it, then start it as video or voice.
 */
@Composable
fun GroupCallSetupScreen(
    onBack: () -> Unit,
    onCallStarted: (String) -> Unit,
    viewModel: GroupCallSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.startedCallId) {
        state.startedCallId?.let {
            viewModel.consumeStartedCall()
            onCallStarted(it)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "New group call", onBack = onBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MiniChatColors.YellowPrimary),
                    decorationBox = { inner ->
                        if (state.title.isEmpty()) {
                            Text(
                                text = "Call name (optional)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                modifier = Modifier.padding(vertical = 14.dp),
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            MiniChatSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search people",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.selected.isEmpty()) {
                    "Invite at least one person"
                } else {
                    "${state.selected.size} invited"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        if (state.error != null) {
            InlineError(
                message = state.error!!,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoading -> MiniChatLoader()

                state.people.isEmpty() -> Text(
                    text = "No one to invite yet. Start a chat first, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(20.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.people, key = { it.id }) { person ->
                        val selected = state.selected.contains(person.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggle(person.id) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiniChatAvatar(
                                displayName = person.displayName,
                                username = person.username,
                                avatarUrl = person.avatarUrl,
                                size = 44.dp,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = person.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = person.handle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MiniChatColors.YellowPrimary
                                        else MaterialTheme.colorScheme.surface,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF14161A),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton(
                text = if (state.starting) "Starting…" else "Start video call",
                icon = Icons.Rounded.Videocam,
                enabled = state.canStart,
                loading = state.starting,
                onClick = { viewModel.start(CallType.Video) },
            )
            PrimaryButton(
                text = "Start voice call",
                icon = Icons.Rounded.Call,
                enabled = state.canStart,
                onClick = { viewModel.start(CallType.Voice) },
            )
        }
    }
}

package com.minichat.app.feature.chat

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.MiniChatLoader
import com.minichat.app.design.components.MiniChatSearchField
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.MiniChatTopBar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Profile

/** "New group": pick a name, say what it's about, and tick the people to add. */
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onCreated: (conversationId: String) -> Unit,
    viewModel: NewGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdConversationId) {
        state.createdConversationId?.let(onCreated)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MiniChatTopBar(title = "New group", subtitle = "Add people to start chatting", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 6.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MiniChatTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = "Group name",
                    placeholder = "Weekend crew",
                    leadingIcon = Icons.Rounded.Groups,
                )
            }
            item {
                MiniChatTextField(
                    value = state.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = "Description (optional)",
                    placeholder = "What's this group about?",
                    singleLine = false,
                    maxLines = 3,
                )
            }

            if (state.selected.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(items = state.selected, key = { it.id }) { person ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(62.dp)
                                    .clickable { viewModel.toggle(person) },
                            ) {
                                MiniChatAvatar(
                                    displayName = person.displayName,
                                    username = person.username,
                                    avatarUrl = person.avatarUrl,
                                    size = 46.dp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = person.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MiniChatTheme.extra.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                MiniChatSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = "Search people",
                )
            }

            state.error?.let { error ->
                item { InlineError(message = error) }
            }

            item {
                Text(
                    text = if (state.query.isBlank()) "RECENT PEOPLE" else "RESULTS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MiniChatTheme.extra.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.isLoading) {
                item { MiniChatLoader() }
            } else if (state.people.isEmpty()) {
                item {
                    Text(
                        text = "Nobody to show yet. Start a chat with someone first, or search by name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                }
            } else {
                items(items = state.people, key = { it.id }) { person ->
                    PersonPickerRow(
                        person = person,
                        selected = state.selected.any { it.id == person.id },
                        onClick = { viewModel.toggle(person) },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            PrimaryButton(
                text = if (state.selected.isEmpty()) {
                    "Create group"
                } else {
                    "Create group with ${state.selected.size}"
                },
                enabled = state.canCreate,
                loading = state.creating,
                onClick = viewModel::create,
            )
        }
    }
}

@Composable
private fun PersonPickerRow(
    person: Profile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = person.displayName,
            username = person.username,
            avatarUrl = person.avatarUrl,
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = person.displayName.ifBlank { person.username },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = person.handle,
                style = MaterialTheme.typography.labelSmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MiniChatColors.YellowPrimary
                    } else {
                        MiniChatTheme.extra.surfaceMuted
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MiniChatColors.OnYellow,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

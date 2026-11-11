package com.minichat.app.feature.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Profile

/**
 * Two-step group creation, Telegram style.
 *
 * Step 1 picks people and shows them as a chip row so the selection is visible
 * without a separate summary. Step 2 names the group. Keeping both steps on one
 * screen means back never loses the selection.
 */
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is GroupViewModel.Event.Created) onCreated(event.conversationId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        GroupTopBar(
            title = if (step == 1) "Add members" else "New group",
            subtitle = if (step == 1) {
                if (state.selected.isEmpty()) "Select at least one person"
                else "${state.selected.size} selected"
            } else {
                "Give it a name and you're done"
            },
            onBack = { if (step == 1) onBack() else step = 1 },
            trailing = {
                if (step == 1) {
                    TextAction(
                        text = "Next",
                        enabled = state.selected.isNotEmpty(),
                        onClick = { step = 2 },
                    )
                }
            },
        )

        if (state.error != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                InlineError(message = state.error!!)
            }
        }

        if (step == 1) {
            MemberPicker(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onToggle = viewModel::toggleMember,
            )
        } else {
            GroupDetails(
                state = state,
                onTitleChange = viewModel::onTitleChange,
                onDescriptionChange = viewModel::onDescriptionChange,
                onCreate = viewModel::createGroup,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1
// ---------------------------------------------------------------------------

@Composable
private fun MemberPicker(
    state: GroupViewModel.UiState,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Selected chips
        AnimatedVisibility(
            visible = state.selected.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LazyRowOfSelected(
                profiles = state.candidates.filter { it.id in state.selected },
                onRemove = onToggle,
            )
        }

        SearchRow(value = state.query, onValueChange = onQueryChange)

        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MiniChatColors.AmberSecondary)
            }

            state.filteredCandidates.isEmpty() -> EmptyPicker()
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.filteredCandidates, key = { it.id }) { profile ->
                    PickablePersonRow(
                        profile = profile,
                        selected = profile.id in state.selected,
                        onClick = { onToggle(profile.id) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LazyRowOfSelected(profiles: List<Profile>, onRemove: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 10.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(profiles, key = { it.id }) { profile ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .clickable { onRemove(profile.id) },
            ) {
                Box {
                    MiniChatAvatar(
                        displayName = profile.displayName,
                        username = profile.username,
                        avatarUrl = profile.avatarUrl,
                        size = 48.dp,
                    )
                    // A small x badge reads as "tap to remove".
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(17.dp)
                            .clip(CircleShape)
                            .background(MiniChatTheme.extra.danger),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = profile.displayName.substringBefore(' '),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MiniChatTheme.extra.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchRow(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MiniChatTheme.extra.surfaceMuted)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MiniChatTheme.extra.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "Search people",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiniChatColors.AmberSecondary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PickablePersonRow(profile: Profile, selected: Boolean, onClick: () -> Unit) {
    val highlight by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "pick",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                MiniChatColors.YellowPrimary.copy(alpha = 0.12f * highlight),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = profile.displayName,
            username = profile.username,
            avatarUrl = profile.avatarUrl,
            size = 46.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
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
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MiniChatColors.YellowPrimary
                    else androidx.compose.ui.graphics.Color.Transparent,
                )
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = MiniChatTheme.extra.textSecondary.copy(alpha = 0.45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MiniChatColors.OnYellow,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyPicker() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MiniChatTheme.extra.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Groups,
                contentDescription = null,
                tint = MiniChatTheme.extra.textSecondary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "No one to add yet",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Start a direct chat first, or search for someone by name.",
            style = MaterialTheme.typography.bodySmall,
            color = MiniChatTheme.extra.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Step 2
// ---------------------------------------------------------------------------

@Composable
private fun GroupDetails(
    state: GroupViewModel.UiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // A preview of the avatar people will see.
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state.title.isBlank()) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = MiniChatColors.OnYellow,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Text(
                    text = state.title.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MiniChatColors.OnYellow,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        MiniChatTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = "Group name",
            placeholder = "Weekend plans",
            singleLine = true,
        )

        Spacer(Modifier.height(14.dp))

        MiniChatTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = "Description (optional)",
            placeholder = "What's this group about?",
            singleLine = false,
        )

        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Members",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${state.selected.size + 1} including you",
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Compact member strip so the last screen still shows who is coming.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.candidates
                .filter { it.id in state.selected }
                .take(6)
                .forEach {
                    MiniChatAvatar(
                        displayName = it.displayName,
                        username = it.username,
                        avatarUrl = it.avatarUrl,
                        size = 36.dp,
                    )
                }
            if (state.selected.size > 6) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MiniChatTheme.extra.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+${state.selected.size - 6}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MiniChatTheme.extra.textSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton(
            text = "Create group",
            onClick = onCreate,
            enabled = state.canCreate,
            loading = state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

@Composable
fun GroupTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatTheme.extra.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun TextAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) MiniChatColors.AmberSecondary else MiniChatTheme.extra.textSecondary,
        )
    }
}

/**
 * Name field for the group sheet. Kept separate from the create screen's
 * `MiniChatTextField` so the sheet can be compact without the label column.
 */
@Composable
fun GroupNameField(value: String, onValueChange: (String) -> Unit) {
    MiniChatTextField(
        value = value,
        onValueChange = onValueChange,
        label = "Group name",
        placeholder = "Weekend plans",
        singleLine = true,
    )
}

@Composable
fun GroupDescriptionField(value: String, onValueChange: (String) -> Unit) {
    MiniChatTextField(
        value = value,
        onValueChange = onValueChange,
        label = "Description",
        placeholder = "What's this group about?",
        singleLine = false,
    )
}

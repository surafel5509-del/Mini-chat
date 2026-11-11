package com.minichat.app.feature.groups

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Shield
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.ConfirmDialog
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.ConversationMember
import com.minichat.app.domain.model.GroupRole

/**
 * Group info and administration.
 *
 * Everything destructive is owner/admin gated twice: the UI hides what the
 * caller cannot do, and the RPC re-checks in Postgres before touching a row.
 */
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addingMembers by remember { mutableStateOf(false) }
    var actionMember by remember { mutableStateOf<ConversationMember?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                GroupViewModel.Event.LeftGroup, GroupViewModel.Event.DeletedGroup -> onLeft()
                else -> Unit
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        GroupTopBar(
            title = "Group info",
            subtitle = null,
            onBack = onBack,
            trailing = {
                if (state.canManage) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { editing = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit group",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )

        state.error?.let { error ->
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                InlineError(message = error)
            }
        }

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MiniChatColors.AmberSecondary)
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---- header ----
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniChatAvatar(
                        displayName = state.title.ifBlank { "Group" },
                        username = "",
                        avatarUrl = state.avatarUrl,
                        size = 96.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.title.ifBlank { "Group" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when (state.memberCount) {
                            1 -> "1 member"
                            else -> "${state.memberCount} members"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                    if (state.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiniChatTheme.extra.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            // ---- admin actions ----
            if (state.canManage) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        ActionRow(
                            icon = Icons.Rounded.Add,
                            label = "Add members",
                            onClick = { addingMembers = true },
                        )
                        ActionRow(
                            icon = Icons.Rounded.Edit,
                            label = if (state.isOwner) "Edit name and description" else "Edit description",
                            onClick = { editing = true },
                        )
                    }
                }
            }

            // ---- members ----
            item {
                SectionLabel("Members")
            }

            items(state.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    canManage = state.canManage,
                    canActOnRoles = state.isOwner,
                    onTap = {
                        if (state.isOwner && member.role != GroupRole.Owner) {
                            actionMember = member
                        }
                    },
                )
            }

            // ---- leave / delete ----
            item {
                Spacer(Modifier.height(18.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ActionRow(
                        icon = Icons.Rounded.ExitToApp,
                        label = "Leave group",
                        tintColor = MiniChatTheme.extra.danger,
                        onClick = { confirmLeave = true },
                    )
                    if (state.isOwner) {
                        ActionRow(
                            icon = Icons.Rounded.Delete,
                            label = "Delete group",
                            tintColor = MiniChatTheme.extra.danger,
                            onClick = { confirmDelete = true },
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave group?",
            message = if (state.isOwner) {
                "You're the owner. Ownership passes to an admin, or to the longest-standing member if there are none."
            } else {
                "You'll stop receiving messages from this group."
            },
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                viewModel.leave()
            },
            onDismiss = { confirmLeave = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete group?",
            message = "This removes the group and its messages for everyone. It can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteGroup()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (editing) {
        EditGroupSheet(
            title = state.title,
            description = state.description,
            isOwner = state.isOwner,
            isSaving = state.isSaving,
            onTitleChange = viewModel::onTitleChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onSave = {
                viewModel.saveChanges()
                editing = false
            },
            onDismiss = { editing = false },
        )
    }

    if (addingMembers) {
        AddMembersSheet(
            candidates = state.candidates,
            selected = state.selected,
            isSaving = state.isSaving,
            onToggle = viewModel::toggleMember,
            onAdd = {
                viewModel.addMembers(state.selected.toList())
                addingMembers = false
            },
            onDismiss = { addingMembers = false },
        )
    }

    actionMember?.let { member ->
        MemberActionsSheet(
            member = member,
            onPromote = {
                viewModel.promote(member.userId)
                actionMember = null
            },
            onDemote = {
                viewModel.demote(member.userId)
                actionMember = null
            },
            onRemove = {
                viewModel.removeMember(member.userId)
                actionMember = null
            },
            onDismiss = { actionMember = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MiniChatTheme.extra.textSecondary,
        modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tintColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tintColor,
        )
    }
}

@Composable
private fun MemberRow(
    member: ConversationMember,
    canManage: Boolean,
    canActOnRoles: Boolean,
    onTap: () -> Unit,
) {
    val roleBadge = when (member.role) {
        GroupRole.Owner -> "Owner"
        GroupRole.Admin -> "Admin"
        GroupRole.Member -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canActOnRoles && member.role != GroupRole.Owner, onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniChatAvatar(
            displayName = member.profile.displayName,
            username = member.profile.username,
            avatarUrl = member.profile.avatarUrl,
            size = 44.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.profile.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (roleBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = roleBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                        color = MiniChatColors.OnYellow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiniChatColors.YellowPrimary)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = member.profile.handle,
                style = MaterialTheme.typography.bodySmall,
                color = MiniChatTheme.extra.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canManage && member.role != GroupRole.Owner) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Member options",
                tint = MiniChatTheme.extra.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Inline editor for the group's name and description.
 *
 * Creating a group and renaming one are the same RPC underneath, so the screen
 * reuses the same validation and just skips the member picker.
 */
@Composable
private fun EditGroupSheet(
    title: String,
    description: String,
    isOwner: Boolean,
    isSaving: Boolean,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "Edit group",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            if (isOwner) {
                GroupNameField(value = title, onValueChange = onTitleChange)
                Spacer(Modifier.height(12.dp))
            }
            GroupDescriptionField(value = description, onValueChange = onDescriptionChange)

            Spacer(Modifier.height(20.dp))
            com.minichat.app.design.components.PrimaryButton(
                text = "Save changes",
                onClick = onSave,
                enabled = title.isNotBlank(),
                loading = isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Adds people to an existing group; only admins can open this. */
@Composable
private fun AddMembersSheet(
    candidates: List<com.minichat.app.domain.model.Profile>,
    selected: Set<String>,
    isSaving: Boolean,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Add members",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
            )

            if (candidates.isEmpty()) {
                Text(
                    text = "Everyone you've chatted with is already in this group.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiniChatTheme.extra.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(candidates, key = { it.id }) { profile ->
                        PickablePersonRow(
                            profile = profile,
                            selected = profile.id in selected,
                            onClick = { onToggle(profile.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            com.minichat.app.design.components.PrimaryButton(
                text = if (selected.isEmpty()) "Select people" else "Add ${selected.size}",
                onClick = onAdd,
                enabled = selected.isNotEmpty(),
                loading = isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Bottom sheet with the three rank changes available to an owner. */
@Composable
private fun MemberActionsSheet(
    member: ConversationMember,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = member.profile.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            if (member.role == GroupRole.Member) {
                ActionRow(
                    icon = Icons.Rounded.Shield,
                    label = "Make admin",
                    onClick = onPromote,
                )
            } else {
                ActionRow(
                    icon = Icons.Rounded.Shield,
                    label = "Dismiss as admin",
                    onClick = onDemote,
                )
            }
            ActionRow(
                icon = Icons.Rounded.PersonRemove,
                label = "Remove from group",
                tintColor = MiniChatTheme.extra.danger,
                onClick = onRemove,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

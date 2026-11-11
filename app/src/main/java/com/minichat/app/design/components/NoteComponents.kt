package com.minichat.app.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.FriendNote
import com.minichat.app.domain.model.Note

/**
 * The "Notes" story row at the top of the chat list.
 *
 * Instagram-style notes: a small rounded card per person with their photo, their
 * name, and the note itself. The first card always belongs to me — it either
 * shows my live note (tap to edit) or invites me to share one.
 */
@Composable
fun NoteStoryRow(
    myNote: Note?,
    myDisplayName: String,
    myUsername: String,
    myAvatarUrl: String?,
    friends: List<FriendNote>,
    onOpenComposer: () -> Unit,
    onOpenFriend: (FriendNote) -> Unit,
    modifier: Modifier = Modifier,
    myAvatarUrlOverride: String? = null,
) {
    if (myNote == null && friends.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                ),
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
            if (friends.isNotEmpty()) {
                Text(
                    text = "· ${friends.size} ${if (friends.size == 1) "friend" else "friends"} sharing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MiniChatTheme.extra.textSecondary.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (myNote == null) "Share a note" else "Edit note",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MiniChatColors.AmberSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenComposer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            // My own card is always first.
            item(key = "note-me") {
                MyNoteCard(
                    note = myNote,
                    displayName = myDisplayName,
                    username = myUsername,
                    avatarUrl = myAvatarUrlOverride ?: myAvatarUrl,
                    onClick = onOpenComposer,
                )
            }

            items(items = friends, key = { it.author.id }) { friend ->
                FriendNoteCard(friend = friend, onClick = { onOpenFriend(friend) })
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * My note card. Reads as "an empty speech bubble waiting for words" when I have
 * no note, and as a filled card with a live countdown when I do.
 */
@Composable
private fun MyNoteCard(
    note: Note?,
    displayName: String,
    username: String,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    val hasNote = note != null && note.isLive()
    Column(
        modifier = Modifier
            .width(112.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (hasNote) {
                    Brush.linearGradient(
                        listOf(
                            MiniChatColors.YellowPrimary.copy(alpha = 0.95f),
                            MiniChatColors.AmberSecondary.copy(alpha = 0.92f),
                        ),
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            MiniChatTheme.extra.surfaceMuted,
                            MiniChatTheme.extra.surfaceMuted,
                        ),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            MiniChatAvatar(
                displayName = displayName,
                username = username,
                avatarUrl = avatarUrl,
                size = 42.dp,
            )
            if (!hasNote) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .background(MiniChatColors.YellowPrimary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MiniChatColors.OnYellow,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = if (hasNote) note!!.body else "Share a note",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (hasNote) FontWeight.SemiBold else FontWeight.Medium,
                lineHeight = 13.sp,
            ),
            color = if (hasNote) MiniChatColors.OnYellow else MiniChatTheme.extra.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (hasNote) "${note!!.hoursLeft().coerceAtLeast(1)}h left" else "Your note",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = if (hasNote) {
                MiniChatColors.OnYellow.copy(alpha = 0.7f)
            } else {
                MiniChatTheme.extra.textSecondary.copy(alpha = 0.7f)
            },
        )
    }
}

/** A friend's note: soft surface, tinted ring, a tappable quoted bubble. */
@Composable
private fun FriendNoteCard(
    friend: FriendNote,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MiniChatTheme.extra.divider, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiniChatAvatar(
            displayName = friend.author.displayName,
            username = friend.author.username,
            avatarUrl = friend.author.avatarUrl,
            size = 42.dp,
            borderWidth = 2.dp,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = friend.note.body,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = friend.author.displayName.substringBefore(' '),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MiniChatTheme.extra.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The note displayed on a profile page: a "handwritten" sticky note pinned under
 * the avatar. Shows live notes only; returns nothing when there is no note.
 */
@Composable
fun ProfileNoteCard(
    note: Note?,
    isMine: Boolean,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    authorName: String = "This person",
) {
    val live = note != null && note.isLive()

    if (!live && !isMine) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (live) {
            Box(
                modifier = Modifier
                    .rotate(-1.6f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MiniChatColors.YellowSoft,
                                MiniChatColors.YellowPrimary.copy(alpha = 0.85f),
                            ),
                        ),
                    )
                    .clickable(onClick = if (isMine) onShare else {{}})
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = MiniChatColors.OnYellow.copy(alpha = 0.55f),
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isMine) "YOUR NOTE" else "NOTE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 9.sp,
                            ),
                            color = MiniChatColors.OnYellow.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = note!!.body,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MiniChatColors.OnYellow,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isMine) {
                            "${note.hoursLeft().coerceAtLeast(1)}h left · tap to change"
                        } else {
                            "${note.hoursLeft().coerceAtLeast(1)}h left"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MiniChatColors.OnYellow.copy(alpha = 0.62f),
                    )
                }
            }
        } else {
            // I have no live note yet — invite me to share one.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiniChatTheme.extra.surfaceMuted)
                    .clickable(onClick = onShare)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MiniChatColors.AmberSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Share a note",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MiniChatColors.AmberSecondary,
                )
            }
        }
    }
}

/**
 * Full-screen note viewer shown when a friend's note card is tapped.
 * Deliberately lightweight: one big card, the author, and a dismiss target.
 */
@Composable
fun FriendNoteSheetContent(
    friend: FriendNote,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiniChatAvatar(
            displayName = friend.author.displayName,
            username = friend.author.username,
            avatarUrl = friend.author.avatarUrl,
            size = 76.dp,
            online = com.minichat.app.core.util.TimeFormat.isOnline(friend.author.lastSeenAt?.toString()),
            showOnlineIndicator = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = friend.author.displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = friend.author.handle,
            style = MaterialTheme.typography.bodySmall,
            color = MiniChatColors.AmberSecondary,
        )
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MiniChatColors.YellowSoft,
                            MiniChatColors.YellowPrimary.copy(alpha = 0.9f),
                        ),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Text(
                text = friend.note.body,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MiniChatColors.OnYellow,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Expires in ${friend.note.hoursLeft().coerceAtLeast(1)}h",
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
        )

        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                GhostButton(text = "Close", onClick = onDismiss)
            }
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(
                    text = "Reply",
                    icon = Icons.Rounded.Edit,
                    onClick = onReply,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** Tiny inline pill used inside the chat header when the peer has a live note. */
@Composable
fun PeerNotePill(
    note: Note,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.9f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MiniChatColors.YellowPrimary.copy(alpha = 0.16f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "💬", fontSize = 11.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = note.body,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MiniChatColors.AmberSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

/** Colour helper so the composer matches the brand gradient. */
internal val NoteAccentBrush: Brush
    get() = Brush.linearGradient(
        listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
    )

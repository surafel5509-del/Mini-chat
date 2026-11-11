package com.minichat.app.feature.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.components.GhostButton
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatAvatar
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import com.minichat.app.domain.model.Note

/** Preset lifetimes offered by the composer (minutes). */
private val durationOptions = listOf(
    "30 min" to 30,
    "1 hour" to 60,
    "4 hours" to 240,
    "24 hours" to 1440,
)

private const val MAX_NOTE_LENGTH = 80

/**
 * The "share a note" composer.
 *
 * Mirrors the Instagram flow: a short thought on a card, a duration, and go.
 * Validation happens twice — here for instant feedback, and again inside the
 * `set_my_note` database function which is the real authority.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteComposerSheet(
    existing: Note?,
    displayName: String,
    username: String,
    avatarUrl: String?,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (body: String, minutes: Int) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var minutes by remember {
        mutableStateOf(
            existing?.let { note ->
                durationOptions.map { it.second }
                    .minByOrNull { kotlin.math.abs(it - note.hoursLeft() * 60) } ?: 1440
            } ?: 1440,
        )
    }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(top = 12.dp, bottom = 20.dp),
        ) {
            // Drag affordance.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MiniChatTheme.extra.divider),
            )
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniChatAvatar(
                    displayName = displayName,
                    username = username,
                    avatarUrl = avatarUrl,
                    size = 44.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (existing == null) "Share a note" else "Update your note",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Visible to friends for the time you pick",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // The note preview — exactly how it will appear on the profile card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MiniChatColors.YellowSoft,
                                MiniChatColors.YellowPrimary.copy(alpha = 0.92f),
                            ),
                        ),
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Column {
                    BasicTextField(
                        value = body,
                        onValueChange = { if (it.length <= MAX_NOTE_LENGTH) body = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        textStyle = TextStyle(
                            color = MiniChatColors.OnYellow,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 25.sp,
                        ),
                        cursorBrush = SolidColor(MiniChatColors.OnYellow),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (body.isEmpty()) {
                                    Text(
                                        text = "What's on your mind?",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MiniChatColors.OnYellow.copy(alpha = 0.42f),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${body.length}/$MAX_NOTE_LENGTH",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MiniChatColors.OnYellow.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "HOW LONG?",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                durationOptions.forEach { (label, value) ->
                    val selected = minutes == value
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                if (selected) {
                                    MiniChatColors.YellowPrimary
                                } else {
                                    MiniChatTheme.extra.surfaceMuted
                                },
                            )
                            .then(
                                if (selected) Modifier else Modifier.border(
                                    1.dp,
                                    MiniChatTheme.extra.divider,
                                    RoundedCornerShape(13.dp),
                                ),
                            )
                            .clickable { minutes = value }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (selected) MiniChatColors.OnYellow else MiniChatTheme.extra.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    InlineError(message = error.orEmpty())
                }
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (existing == null) "Share note" else "Update note",
                onClick = { onSave(body, minutes) },
                enabled = body.isNotBlank(),
                loading = isSaving,
            )

            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !isSaving, onClick = onDelete)
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MiniChatTheme.extra.danger,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Delete note",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MiniChatTheme.extra.danger,
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                GhostButton(text = "Cancel", onClick = onDismiss)
            }
        }
    }
}

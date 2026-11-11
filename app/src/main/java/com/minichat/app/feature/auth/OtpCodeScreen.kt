package com.minichat.app.feature.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MarkEmailRead
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.components.TextButtonSmall
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

/**
 * Six-digit code entry.
 *
 * One hidden text field drives six visual boxes — the standard approach on
 * Android because it keeps IME behaviour, paste and autofill from SMS/email
 * working while still looking like discrete cells. Autofocus opens the keyboard
 * immediately, and the sixth digit submits without needing a tap.
 */
@Composable
fun OtpCodeScreen(
    email: String,
    code: String,
    isLoading: Boolean,
    error: String?,
    resendIn: Int,
    shakeSignal: Int,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shake = remember { Animatable(0f) }

    // A rejected code nudges the row sideways.
    LaunchedEffect(shakeSignal) {
        if (shakeSignal > 0) {
            repeat(3) {
                shake.animateTo(if (it % 2 == 0) 9f else -9f, tween(55))
            }
            shake.animateTo(0f, tween(55))
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    AuthScaffold(
        title = "",
        subtitle = "",
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MiniChatColors.YellowPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MarkEmailRead,
                    contentDescription = null,
                    tint = MiniChatColors.AmberSecondary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Check your email",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "We sent a 6-digit code to",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // The boxes, shifted by the shake animation.
            Box(modifier = Modifier.offsetShake(shake.value)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .clickable { focusRequester.requestFocus() },
                ) {
                    repeat(OtpCodeScreenDefaults.LENGTH) { index ->
                        val digit = code.getOrNull(index)?.toString().orEmpty()
                        val isCursor = index == code.length && !isLoading
                        CodeBox(
                            digit = digit,
                            highlighted = isCursor,
                            hasError = error != null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // The real input: invisible, spans the row, owns the IME.
                BasicTextField(
                    value = code,
                    onValueChange = { value ->
                        val digits = value.filter { it.isDigit() }.take(OtpCodeScreenDefaults.LENGTH)
                        onCodeChange(digits)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = androidx.compose.ui.graphics.Color.Transparent),
                    cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester),
                )
            }

            Spacer(Modifier.height(14.dp))

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MiniChatTheme.extra.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                Text(
                    text = "Codes expire in 10 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }

            Spacer(Modifier.height(28.dp))

            PrimaryButton(
                text = "Verify email",
                onClick = {
                    focusManager.clearFocus()
                    onVerify()
                },
                enabled = code.length == OtpCodeScreenDefaults.LENGTH && !isLoading,
                loading = isLoading,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))

            if (resendIn > 0) {
                Text(
                    text = "Resend code in ${resendIn}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Didn't get it? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatTheme.extra.textSecondary,
                    )
                    TextButtonSmall(
                        text = "Resend code",
                        onClick = onResend,
                        color = MiniChatColors.AmberSecondary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "Check spam or promotions if it isn't there in a minute.",
                style = MaterialTheme.typography.labelSmall,
                color = MiniChatTheme.extra.textSecondary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CodeBox(
    digit: String,
    highlighted: Boolean,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        hasError -> MiniChatTheme.extra.danger
        highlighted -> MiniChatColors.AmberSecondary
        digit.isNotEmpty() -> MiniChatColors.YellowPrimary
        else -> MiniChatTheme.extra.textSecondary.copy(alpha = 0.25f)
    }

    Box(
        modifier = modifier
            .height(62.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (digit.isNotEmpty() || highlighted) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MiniChatTheme.extra.surfaceMuted.copy(alpha = 0.6f)
                },
            )
            .border(
                width = if (highlighted || hasError) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (digit.isNotEmpty()) {
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (highlighted) {
            // A soft caret so the active box is obvious.
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(22.dp)
                    .background(MiniChatColors.AmberSecondary, RoundedCornerShape(1.dp)),
            )
        }
    }
}

object OtpCodeScreenDefaults {
    const val LENGTH = 6
}

/** Applies the shake translation without pulling in animation extras. */
private fun Modifier.offsetShake(x: Float): Modifier = this.offset(x = x.dp)

/** Full-screen spinner for the moment between code accepted and profile ready. */
@Composable
fun OtpSuccessOverlay(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MiniChatColors.AmberSecondary)
    }
}

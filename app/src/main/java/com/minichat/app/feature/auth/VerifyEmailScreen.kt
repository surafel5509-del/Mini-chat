package com.minichat.app.feature.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minichat.app.design.components.GhostButton
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

/**
 * "Check your inbox" screen for link-based email verification.
 *
 * Supabase mails a confirmation link; tapping it activates the account and the
 * app picks the session up on next launch. This screen exists so the user is not
 * left on a spinner wondering what to do next — it names the address the mail
 * went to and offers a resend when nothing arrives.
 */
@Composable
fun VerifyEmailPendingScreen(
    email: String,
    isResending: Boolean,
    resendIn: Int,
    error: String?,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    AuthScaffold(
        title = "Confirm your email",
        subtitle = "One tap in your inbox and your account is ready.",
        onBack = onBack,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MiniChatColors.YellowPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MarkEmailRead,
                contentDescription = null,
                tint = MiniChatColors.AmberSecondary,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "We sent a confirmation link to",
            style = MaterialTheme.typography.bodyMedium,
            color = MiniChatTheme.extra.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = email,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        if (error != null) {
            InlineError(message = error)
            Spacer(Modifier.height(16.dp))
        }

        // The three things that actually help when a link does not show up.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MiniChatTheme.extra.surfaceMuted)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HintRow("Open the link on this device")
            HintRow("Check spam, promotions and Updates")
            HintRow("Links expire after 24 hours")
        }

        Spacer(Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton(
                text = "Back to sign in",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = when {
                    isResending -> "Sending…"
                    resendIn > 0 -> "Resend in ${resendIn}s"
                    else -> "Resend link"
                },
                icon = Icons.Rounded.Refresh,
                onClick = onResend,
                enabled = !isResending && resendIn == 0,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Already confirmed? Sign in and you're in.",
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HintRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MiniChatColors.YellowPrimary, CircleShape),
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

/**
 * Recovery screen: the reset link is emailed, and this explains what to do.
 * Kept separate from [VerifyEmailPendingScreen] because the next step differs.
 */
@Composable
fun ResetLinkSentScreen(
    email: String,
    onBack: () -> Unit,
) {
    AuthScaffold(
        title = "Check your inbox",
        subtitle = "We sent a password reset link to $email.",
        onBack = onBack,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MiniChatColors.YellowPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Send,
                contentDescription = null,
                tint = MiniChatColors.AmberSecondary,
                modifier = Modifier.size(34.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "Open the link in that email to choose a new password. " +
                "It brings you straight back into Mini Chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MiniChatTheme.extra.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(26.dp))

        GhostButton(
            text = "Back to sign in",
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = "No email? Check spam, then try again in a few minutes.",
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp),
        )
    }
}

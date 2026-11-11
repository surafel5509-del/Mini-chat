package com.minichat.app.feature.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.GhostButton
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuthScaffold(
        title = "Reset password",
        subtitle = "Enter the email on your account and we'll send you a secure reset link.",
        onBack = onBack,
    ) {
        if (state.resetLinkSent) {
            Text(
                text = "Sent ✨",
                style = MaterialTheme.typography.headlineSmall,
                color = MiniChatColors.AmberSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "If ${state.email} matches an account, a reset link is on its way. Open it on this phone to choose a new password.",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            GhostButton(text = "Back to sign in", onClick = onBack)
            return@AuthScaffold
        }

        if (state.formError != null) {
            InlineError(message = state.formError!!)
            Spacer(Modifier.height(16.dp))
        }

        MiniChatTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = "Email",
            placeholder = "you@example.com",
            leadingIcon = Icons.Rounded.AlternateEmail,
            error = state.emailError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            text = "Send reset link",
            onClick = viewModel::sendPasswordReset,
            loading = state.isLoading,
            enabled = state.email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "For security, we never reveal whether an email is registered.",
            style = MaterialTheme.typography.bodySmall,
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

@Composable
fun ResetPasswordScreen(
    onDone: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.passwordUpdated) {
        if (state.passwordUpdated) onDone()
    }

    AuthScaffold(
        title = "Choose a new password",
        subtitle = "Pick something strong that you don't use anywhere else.",
    ) {
        if (state.formError != null) {
            InlineError(message = state.formError!!)
            Spacer(Modifier.height(16.dp))
        }

        PasswordField(
            value = password,
            onValueChange = {
                password = it
                mismatch = null
            },
            visible = visible,
            onToggleVisibility = { visible = !visible },
            error = state.passwordError,
            label = "New password",
        )
        Spacer(Modifier.height(16.dp))

        PasswordField(
            value = confirm,
            onValueChange = {
                confirm = it
                mismatch = null
            },
            visible = visible,
            onToggleVisibility = { visible = !visible },
            error = mismatch,
            label = "Confirm password",
            placeholder = "Repeat your new password",
        )
        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = "Update password",
            onClick = {
                if (password != confirm) {
                    mismatch = "Passwords don't match"
                } else {
                    viewModel.updatePassword(password)
                }
            },
            loading = state.isLoading,
            enabled = password.isNotBlank() && confirm.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

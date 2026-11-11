package com.minichat.app.feature.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
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
fun RegisterScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthViewModel.Event.NavigateHome) onRegistered()
        }
    }

    // Email link confirmation.
    val verificationEmail = state.verificationEmail
    if (verificationEmail != null) {
        VerifyEmailPendingScreen(
            email = verificationEmail,
            isResending = state.isLoading,
            resendIn = state.resendIn,
            error = state.verificationError,
            onResend = viewModel::resendVerification,
            onBack = {
                viewModel.dismissVerification()
                onSignIn()
            },
        )
        return
    }

    AuthScaffold(
        title = "Create your account",
        subtitle = "One account, every conversation. It takes about twenty seconds.",
        onBack = onBack,
        footer = {
            AuthFooterLink(
                message = "Already have an account?",
                action = "Sign in",
                onClick = onSignIn,
            )
        },
    ) {
        if (state.formError != null) {
            InlineError(message = state.formError!!)
            Spacer(Modifier.height(16.dp))
        }

        MiniChatTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = "Display name",
            placeholder = "How friends will see you",
            leadingIcon = Icons.Rounded.Badge,
            error = state.nameError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
            ),
        )
        Spacer(Modifier.height(16.dp))

        MiniChatTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = "Email",
            placeholder = "you@example.com",
            leadingIcon = Icons.Rounded.AlternateEmail,
            error = state.emailError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(16.dp))

        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            visible = passwordVisible,
            onToggleVisibility = { passwordVisible = !passwordVisible },
            error = state.passwordError,
            label = "Password",
            placeholder = "At least 8 characters",
        )

        Spacer(Modifier.height(10.dp))
        PasswordStrength(password = state.password)
        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = "Create account",
            onClick = viewModel::register,
            loading = state.isLoading,
            enabled = state.email.isNotBlank() && state.password.isNotBlank() && state.displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        AuthLegalNote()
    }
}

@Composable
private fun PasswordStrength(password: String) {
    val score = when {
        password.isEmpty() -> 0f
        password.length < 8 -> 0.33f
        password.any { it.isDigit() } && password.any { it.isLetter() } && password.length >= 10 -> 1f
        password.any { it.isDigit() } && password.any { it.isLetter() } -> 0.72f
        else -> 0.5f
    }
    val label = when {
        password.isEmpty() -> "Use 8+ characters with letters and numbers"
        score < 0.5f -> "Weak — add more characters"
        score < 0.9f -> "Good — add a symbol or more length"
        else -> "Strong password"
    }

    Column {
        LinearProgressIndicator(
            progress = { score },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = when {
                score < 0.5f -> MiniChatTheme.extra.danger
                score < 0.9f -> MiniChatColors.AmberSecondary
                else -> MiniChatTheme.extra.success
            },
            trackColor = MiniChatTheme.extra.surfaceMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MiniChatTheme.extra.textSecondary,
        )
    }
}

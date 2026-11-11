package com.minichat.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minichat.app.design.components.InlineError
import com.minichat.app.design.components.MiniChatTextField
import com.minichat.app.design.components.PrimaryButton
import com.minichat.app.design.components.TextButtonSmall
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    onForgotPassword: () -> Unit,
    onRegister: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthViewModel.Event.NavigateHome) onSignedIn()
        }
    }

    AuthScaffold(
        title = "Welcome back",
        subtitle = "Sign in to pick up right where you left off.",
        onBack = onBack,
        footer = {
            AuthFooterLink(
                message = "New to MINI CHAT?",
                action = "Create an account",
                onClick = onRegister,
            )
        },
    ) {
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
            placeholder = "Your password",
        )

        Spacer(Modifier.height(4.dp))
        TextButtonSmall(
            text = "Forgot your password?",
            onClick = onForgotPassword,
            color = MiniChatColors.AmberSecondary,
        )

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = "Sign in",
            onClick = {
                keyboard?.hide()
                viewModel.signIn()
            },
            loading = state.isLoading,
            enabled = state.email.isNotBlank() && state.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        // Signing in before confirming the address is the most common dead end:
        // offer the code right here instead of sending the user hunting through
        // their inbox for a link that no longer exists.
        if (state.formError?.contains("not confirmed", ignoreCase = true) == true ||
            state.formError?.contains("verify", ignoreCase = true) == true
        ) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Not confirmed yet? ",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = MiniChatTheme.extra.textSecondary,
                )
                TextButtonSmall(
                    text = "Email me a link",
                    onClick = viewModel::startEmailVerification,
                    color = MiniChatColors.AmberSecondary,
                )
            }
        }
    }
}

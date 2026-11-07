package com.minichat.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import com.minichat.app.design.components.MiniChatIconButton
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff

/**
 * Shared authentication layout: soft brand glow at the top, an optional back affordance, a bold
 * title block and a scrolling, keyboard-aware form area.
 */
@Composable
fun AuthScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Brand glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MiniChatColors.YellowSoft.copy(alpha = if (MiniChatTheme.extra.isDark) 0.10f else 0.75f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(10.dp))
            if (onBack != null) {
                MiniChatIconButton(
                    icon = Icons.Rounded.ArrowBackIosNew,
                    contentDescription = "Back",
                    onClick = onBack,
                    containerColor = MaterialTheme.colorScheme.surface,
                    iconSize = 17.dp,
                    size = 42.dp,
                )
                Spacer(Modifier.height(26.dp))
            } else {
                Spacer(Modifier.height(34.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
            Spacer(Modifier.height(30.dp))

            content()

            Spacer(Modifier.height(26.dp))
            footer?.invoke()
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun AuthFooterLink(
    message: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MiniChatTheme.extra.textSecondary,
        )
        Spacer(Modifier.height(0.dp))
        Text(
            text = " $action",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MiniChatColors.AmberSecondary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    error: String?,
    label: String = "Password",
    placeholder: String = "At least 8 characters",
) {
    com.minichat.app.design.components.MiniChatTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        error = error,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        leadingIcon = androidx.compose.material.icons.Icons.Rounded.Lock,
        trailingIcon = if (visible) {
            androidx.compose.material.icons.Icons.Rounded.VisibilityOff
        } else {
            androidx.compose.material.icons.Icons.Rounded.Visibility
        },
        onTrailingIconClick = onToggleVisibility,
        trailingIconDescription = if (visible) "Hide password" else "Show password",
    )
}

/** Small "powered by" legal line shown under the auth CTAs. */
@Composable
fun AuthLegalNote(modifier: Modifier = Modifier) {
    Text(
        text = "By continuing you agree to keep it kind. MINI CHAT stores your messages securely and never sells your data.",
        style = MaterialTheme.typography.labelSmall,
        color = MiniChatTheme.extra.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    )
}

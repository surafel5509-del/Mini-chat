package com.minichat.app.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minichat.app.design.theme.MiniChatTheme

/**
 * Header used by inner screens. Kept deliberately minimal: back affordance, title, optional
 * subtitle and a slot for up to two actions.
 */
@Composable
fun MiniChatTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                MiniChatIconButton(
                    icon = Icons.Rounded.ArrowBackIosNew,
                    contentDescription = "Back",
                    onClick = onBack,
                    containerColor = MaterialTheme.colorScheme.surface,
                    iconSize = 17.dp,
                    size = 40.dp,
                )
                Spacer(Modifier.width(6.dp))
            } else if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MiniChatTheme.extra.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        }
    }
}

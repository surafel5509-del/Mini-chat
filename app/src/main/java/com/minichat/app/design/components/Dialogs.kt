package com.minichat.app.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minichat.app.core.util.Validators
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (destructive) MiniChatTheme.extra.danger else MiniChatColors.AmberSecondary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
        },
    )
}

private val reportReasons = listOf(
    "Spam or unwanted advertising",
    "Harassment or bullying",
    "Inappropriate content",
    "Impersonation",
    "Something else",
)

@Composable
fun ReportUserDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onReport: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val finalReason = (selected?.takeIf { it != "Something else" } ?: reason).trim()
    val error = if (selected == "Something else" || selected == null) {
        Validators.validateReportReason(finalReason)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Report $displayName",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = "Reports are reviewed by the MINI CHAT team. This person won't be told.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiniChatTheme.extra.textSecondary,
                )
                Spacer(Modifier.height(14.dp))
                reportReasons.forEach { option ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .then(Modifier).inkClickable { selected = option },
                    ) {
                        Text(
                            text = (if (selected == option) "●  " else "○  ") + option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected == option) {
                                MiniChatColors.AmberSecondary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                if (selected == null || selected == "Something else") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        placeholder = { Text("Tell us more (optional)") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(110.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onReport(finalReason.ifBlank { "User report" }) },
                enabled = finalReason.length >= 3,
            ) {
                Text(
                    text = "Send report",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (finalReason.length >= 3) {
                        MiniChatTheme.extra.danger
                    } else {
                        MiniChatTheme.extra.textSecondary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

private fun Modifier.inkClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
fun MessageInfoDialog(
    title: String,
    lines: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                lines.forEach { (label, value) ->
                    LabeledValue(label = label, value = value, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

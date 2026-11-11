package com.minichat.app.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minichat.app.design.theme.MiniChatColors
import com.minichat.app.design.theme.MiniChatTheme

/**
 * MINI CHAT mark: a rounded yellow superellipse holding a two-bubble glyph.
 * Drawn with Canvas so it stays crisp at every density and needs no bitmap assets.
 */
@Composable
fun MiniChatMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    cornerPercent: Int = 30,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(MiniChatColors.YellowPrimary, MiniChatColors.AmberSecondary),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
                shape = RoundedCornerShape(percent = cornerPercent),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height

            // Back bubble (soft, offset) — the "other" person.
            val back = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = w * 0.72f,
                        bottom = h * 0.62f,
                        cornerRadius = CornerRadius(w * 0.22f),
                    ),
                )
            }
            drawPath(back, color = Color.White.copy(alpha = 0.55f))

            // Front bubble — the primary voice.
            installBubble(
                path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = w * 0.26f,
                            top = h * 0.34f,
                            right = w,
                            bottom = h,
                            cornerRadius = CornerRadius(w * 0.24f),
                        ),
                    )
                },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.installBubble(path: Path) {
    drawPath(path, color = MiniChatColors.OnYellow)
}

/** Mark + wordmark, used in the welcome screen, home header and about screen. */
@Composable
fun MiniChatLogo(
    modifier: Modifier = Modifier,
    markSize: Dp = 40.dp,
    showTagline: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniChatMark(size = markSize)
        Column {
            Text(
                text = "MINI CHAT",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (showTagline) {
                Text(
                    text = "Fast. Simple. Connected.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MiniChatTheme.extra.textSecondary,
                )
            }
        }
    }
}

@Composable
fun MiniChatWordmark(modifier: Modifier = Modifier, markSize: Dp = 84.dp) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MiniChatMark(size = markSize)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MINI CHAT",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Fast. Simple. Connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MiniChatTheme.extra.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

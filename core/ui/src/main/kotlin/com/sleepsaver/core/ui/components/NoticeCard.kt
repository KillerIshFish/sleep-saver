package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** How a [NoticeCard] is tinted: INFO in tide, WARNING in amber. Never red. */
enum class NoticeTone { INFO, WARNING }

/**
 * A card that explains something with an optional fix: "Alarm notifications are turned off / Turn on the "Alarm"
 * category in notification settings. / Open settings". The action shows when both [actionLabel] and [onAction] are
 * set. A WARNING only tints the icon amber; the card itself stays grey.
 */
@Composable
fun NoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: NoticeTone = NoticeTone.INFO,
) {
    val tint = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.primary
        NoticeTone.WARNING -> MaterialTheme.colorScheme.tertiary
    }
    SleepSaverCard(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (actionLabel != null && onAction != null) {
                    // Pulled left by the button's inner padding so its label lines up with the text above, and
                    // overlapping the card's bottom padding with its empty lower edge (the touch target stays 48 dp).
                    TextLinkButton(
                        actionLabel,
                        onClick = onAction,
                        modifier = Modifier
                            .padding(top = Spacing.xs)
                            .offset(x = (-12).dp)
                            .trimBottom(),
                    )
                }
            }
        }
    }
}

/** Lets a 48 dp text button's empty lower edge overlap the padding below it, so the card isn't bottom-heavy. */
private fun Modifier.trimBottom(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height - TextButtonSlack.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/** The space a 48 dp text button leaves under its one-line label. */
private val TextButtonSlack = 12.dp

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun NoticeCardPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        NoticeCard(
            title = "Notifications are off",
            body = "Alarms need notifications to show the Stop button.",
            icon = Icons.Rounded.NotificationsOff,
            actionLabel = "Allow",
            onAction = {},
            tone = NoticeTone.WARNING,
        )
        NoticeCard(title = "Planned keel 84", body = "Free days wake 1h 40m later than alarm days.")
    }
}

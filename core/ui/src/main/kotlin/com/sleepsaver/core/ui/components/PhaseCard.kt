package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.formatShift
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.ZoneId

object PhaseCardDefaults {
    /** Width of a card in a horizontally scrolling row. */
    val CompactWidth = 170.dp
}

/**
 * One energy phase: icon, title, time range and the shift vs yesterday ("↑13m earlier"; its line is kept empty
 * when [shift] is null so cards in a row stay the same height).
 * [highlighted] marks the current phase with an accent border, a soft glow and a "Now" tag.
 * The card sizes to its content; use `Modifier.width(PhaseCardDefaults.CompactWidth)` in a row. Its text wraps at
 * large font sizes; to keep a row's cards equally tall then, give the row `Modifier.height(IntrinsicSize.Max)`
 * and each card `Modifier.fillMaxHeight()`.
 */
@Composable
fun PhaseCard(
    type: PhaseType,
    timeRange: String,
    modifier: Modifier = Modifier,
    shift: String? = null,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val tide = MaterialTheme.colorScheme.primary
    val tint = type.tint
    Column(
        modifier = modifier
            .clip(shape)
            .background(SleepSaverTheme.colors.card)
            .then(
                if (highlighted) {
                    Modifier
                        .drawBehind {
                            drawRect(
                                Brush.radialGradient(
                                    colors = listOf(tide.copy(alpha = 0.22f), Color.Transparent),
                                    center = Offset(size.width * 0.15f, 0f),
                                    radius = size.maxDimension * 0.9f,
                                ),
                            )
                        }
                        .border(1.5.dp, tide.copy(alpha = 0.7f), shape)
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(type.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            if (highlighted) {
                Text(
                    text = stringResource(R.string.ss_phase_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tide)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Lines wrap rather than cut off: at large font sizes a compact card can't fit "9:30 AM – 12:30 PM".
        Text(
            text = type.title(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = timeRange,
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        // Kept (empty) without a shift so cards in a row line up.
        Text(
            text = shift.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = SleepSaverTheme.colors.textTertiary,
        )
    }
}

/** [PhaseCard] for a computed [Phase], times shown in [zone] using the app's clock format. */
@Composable
fun PhaseCard(
    phase: Phase,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    PhaseCard(
        type = phase.type,
        timeRange = timeRange(phase.start, phase.end, zone),
        modifier = modifier,
        shift = formatShift(phase.shiftVsYesterday),
        highlighted = highlighted,
        onClick = onClick,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun PhaseCardPreview() = SleepSaverTheme {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhaseCard(
            PhaseType.MORNING_PEAK, "9:30 AM – 12:30 PM", Modifier.width(PhaseCardDefaults.CompactWidth),
            shift = "↑13m earlier", highlighted = true,
        )
        PhaseCard(PhaseType.AFTERNOON_DIP, "2:00 – 4:30 PM", Modifier.width(PhaseCardDefaults.CompactWidth), shift = "same as yesterday")
    }
}

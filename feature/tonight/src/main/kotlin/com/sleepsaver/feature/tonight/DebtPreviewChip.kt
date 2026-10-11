package com.sleepsaver.feature.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.patterns.DebtPreview
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.formatHours1
import com.sleepsaver.core.ui.format.formatSignedHours1
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Debt preview (an estimate): "Asleep by 11:10 PM → debt tomorrow about 3.1 h (−0.4 h)". A decrease is tide; an
 * increase or no change stays grey, never red. With fewer than `anchor.debtPreviewMinNights` nights it says so
 * instead: "Debt preview needs 3 nights (1 so far)". Screen readers hear "Estimate" first.
 */
@Composable
internal fun DebtPreviewChip(
    preview: DebtPreview,
    today: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    config: SleepConfig = SleepConfig.DEFAULT,
) {
    val better = preview.enoughHistory && preview.change.roundedToTenthsOfHour() < 0
    val tint = if (better) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textSecondary
    val text = if (preview.enoughHistory) {
        val day = if (preview.wakeDate == today.plusDays(1)) {
            stringResource(R.string.tonight_debt_tomorrow)
        } else {
            stringResource(R.string.tonight_debt_on_day, formatDayShort(preview.wakeDate.dayOfWeek))
        }
        stringResource(
            R.string.tonight_debt_preview,
            clockTime(preview.asleepBy, zone),
            day,
            formatHours1(preview.projectedDebt),
            formatSignedHours1(preview.change),
        ).keepTimesTogether()
    } else {
        stringResource(R.string.tonight_debt_needs_nights, config.anchor.debtPreviewMinNights, preview.presentNights)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SleepSaverTheme.colors.cardRaised, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Insights,
            contentDescription = stringResource(R.string.tonight_estimate),
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** Tenths of an hour, rounded like the chip shows them, so "−0.0" never reads as a decrease. */
private fun Duration.roundedToTenthsOfHour(): Long {
    val ms = toMillis()
    val tenths = (Math.abs(ms) + 180_000) / 360_000
    return if (ms < 0) -tenths else tenths
}

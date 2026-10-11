package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * [TimeScrubber] for a time of day, not an instant: a wake time, a bedtime, "Just this once".
 * - `overnight = false` runs from 00:00 to the last step before midnight (23:55 for 5-minute steps).
 * - `overnight = true` runs through the night, 4:00 PM to 8:00 AM, so 12:30 AM sits after 11:30 PM and dragging
 *   later from 11:55 PM goes on to midnight instead of jumping back to the morning. For bedtimes.
 * A value outside the range is shown as it is and moves into the range when scrubbed.
 */
@Composable
fun LocalTimeScrubber(
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    overnight: Boolean = false,
    stepMinutes: Int = 5,
    label: String? = null,
) {
    TimeScrubber(
        value = value.onScrubber(overnight),
        onValueChange = { onValueChange(it.timeOfDay()) },
        zone = ZoneOffset.UTC,
        modifier = modifier,
        min = if (overnight) OvernightStart.onScrubber(overnight = true) else LocalTime.MIDNIGHT.onScrubber(overnight = false),
        max = if (overnight) {
            OvernightEnd.onScrubber(overnight = true)
        } else {
            LocalTime.MIDNIGHT.minusMinutes(stepMinutes.toLong()).onScrubber(overnight = false)
        },
        stepMinutes = stepMinutes,
        label = label,
    )
}

/** The scrubber works on instants: a fixed UTC day stands in for "any day", so local time = UTC time. */
private val ScrubberDay: LocalDate = LocalDate.of(2000, 1, 3)

/** The overnight range: 4:00 PM on the scrubber day to 8:00 AM the next. */
private val OvernightStart: LocalTime = LocalTime.of(16, 0)
private val OvernightEnd: LocalTime = LocalTime.of(8, 0)

/** Overnight, morning times (before noon) belong to the next day, so they sort after the evening. */
private fun LocalTime.onScrubber(overnight: Boolean): Instant {
    val day = if (overnight && isBefore(LocalTime.NOON)) ScrubberDay.plusDays(1) else ScrubberDay
    return day.atTime(this).toInstant(ZoneOffset.UTC)
}

private fun Instant.timeOfDay(): LocalTime = atZone(ZoneOffset.UTC).toLocalTime()

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun LocalTimeScrubberPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        LocalTimeScrubber(value = LocalTime.of(6, 30), onValueChange = {}, label = "Wake time")
        LocalTimeScrubber(value = LocalTime.of(23, 15), onValueChange = {}, overnight = true, label = "Bedtime")
    }
}

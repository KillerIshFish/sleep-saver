package com.sleepsaver.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.ZoneId

/** true = "23:47", false = "11:47 PM". The app provides it from the clock-format setting (or the system's). */
val LocalUse24HourClock = staticCompositionLocalOf { false }

/** [formatClockTime] using [LocalUse24HourClock]. */
@Composable
@ReadOnlyComposable
fun clockTime(instant: Instant, zone: ZoneId): String = formatClockTime(instant, zone, LocalUse24HourClock.current)

/** [formatTimeRange] using [LocalUse24HourClock]. */
@Composable
@ReadOnlyComposable
fun timeRange(start: Instant, end: Instant, zone: ZoneId): String =
    formatTimeRange(start, end, zone, LocalUse24HourClock.current)

/** [formatClockAxis] using [LocalUse24HourClock]. */
@Composable
@ReadOnlyComposable
fun clockAxis(instant: Instant, zone: ZoneId): String = formatClockAxis(instant, zone, LocalUse24HourClock.current)

package com.sleepsaver.feature.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatShortDate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Copy is English-only for now (like core:ui's formatters): keep day and month names in English too.
private val English = Locale.US

/** "Tuesday, Oct 6". */
internal fun formatHeaderDate(date: LocalDate): String =
    "${date.dayOfWeek.getDisplayName(TextStyle.FULL, English)}, " +
        "${date.month.getDisplayName(TextStyle.SHORT, English)} ${date.dayOfMonth}"

/** "Synced just now" / "Synced 12 minutes ago" / "Synced today at 9:42 AM" / "Synced Mon, Oct 5 at 9:42 AM". */
@Composable
@ReadOnlyComposable
internal fun lastSyncedText(lastSync: Instant?, now: Instant, zone: ZoneId): String {
    if (lastSync == null) return stringResource(R.string.sleep_source_never_synced)
    val ago = Duration.between(lastSync, now)
    val minutes = ago.toMinutes().toInt()
    val syncDate = lastSync.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> stringResource(R.string.sleep_source_synced_now)
        minutes < 60 -> pluralStringResource(R.plurals.sleep_source_synced_minutes, minutes, minutes)
        syncDate == today -> stringResource(R.string.sleep_source_synced_today, clockTime(lastSync, zone))
        else -> stringResource(R.string.sleep_source_synced_on, formatShortDate(syncDate, today), clockTime(lastSync, zone))
    }
}

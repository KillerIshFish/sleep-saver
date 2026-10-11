package com.sleepsaver.core.alarm

import android.content.Context
import android.text.format.DateFormat
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * core:alarm's own copy (it can't use core:ui): clock times with DateTimeFormatter, and the ring's title and reason
 * line (SPEC_V2 §5.7). Never a stage claim: "a restless moment", "your wake time".
 */
internal object AlarmText {
    private val h12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val h24: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.US)

    /** "6:30 AM" (no-break space before AM), or "6:30" / "18:30" in 24-hour style. */
    fun clock(at: Instant, zone: ZoneId, use24h: Boolean): String = (if (use24h) h24 else h12).format(at.atZone(zone))

    /** The app's clock setting as a 24-hour flag (SYSTEM follows the phone). */
    fun use24h(context: Context, format: ClockFormat): Boolean = when (format) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }

    fun title(context: Context, kind: RingKind): String = context.getString(
        when (kind) {
            RingKind.PLANNED -> R.string.alarm_ring_title_planned
            RingKind.NAP -> R.string.alarm_ring_title_catnap
            RingKind.TEST -> R.string.alarm_ring_title_test
        },
    )

    /** The ring's reason line: "A restless moment · 18 min before 6:30 AM", "Your wake time", … */
    fun reason(context: Context, ringing: MirroredRinging, zone: ZoneId, use24h: Boolean): String {
        val target = ringing.alarm?.target
        return when (ringing.ringReason) {
            RingReason.RESTLESS -> if (target == null) {
                context.getString(R.string.alarm_ring_wake_time)
            } else {
                context.getString(R.string.alarm_ring_restless, minutes(context, minutesEarly(ringing.since, target)), clock(target, zone, use24h))
            }
            RingReason.DEADLINE, RingReason.PLACEMENT_FALLBACK, RingReason.SENSING_UNAVAILABLE, RingReason.FIXED_TIME ->
                context.getString(R.string.alarm_ring_wake_time)
            RingReason.SNOOZE_END -> context.getString(R.string.alarm_ring_snooze_over)
            RingReason.CATNAP_END -> context.getString(R.string.alarm_ring_catnap)
            RingReason.TEST -> context.getString(R.string.alarm_ring_test)
            RingReason.CATCH_UP -> if (target == null) {
                context.getString(R.string.alarm_ring_wake_time)
            } else {
                context.getString(R.string.alarm_ring_catch_up, clock(target, zone, use24h))
            }
            RingReason.BACKUP -> context.getString(R.string.alarm_ring_backup)
        }
    }

    /** Whole clock minutes between the ring and the wake time, both truncated to the minute (never negative). */
    fun minutesEarly(ringAt: Instant, target: Instant): Long =
        maxOf(0L, Duration.between(ringAt.truncatedTo(ChronoUnit.MINUTES), target.truncatedTo(ChronoUnit.MINUTES)).toMinutes())

    private fun minutes(context: Context, minutes: Long): String = context.getString(R.string.alarm_minutes, minutes.toInt())
}

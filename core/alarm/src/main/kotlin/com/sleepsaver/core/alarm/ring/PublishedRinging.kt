package com.sleepsaver.core.alarm.ring

import com.sleepsaver.core.alarm.AlarmText
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import java.time.Duration
import java.time.ZoneId

/**
 * What NightController.state.ringing shows of this ring, with the snooze [offer] as of now: the target (none for a
 * test ring), the wake date and how early it rang (planned rings only). null for a ring without an alarm.
 */
internal fun MirroredRinging.published(offer: SnoozeOffer, use24h: Boolean, zone: ZoneId): Ringing? {
    val alarm = alarm ?: return null
    return Ringing(
        kind = kind,
        reason = ringReason,
        since = since,
        target = alarm.target.takeIf { kind != RingKind.TEST },
        wakeDate = alarm.wakeDate.takeIf { kind == RingKind.PLANNED },
        earlyBy = Duration.ofMinutes(AlarmText.minutesEarly(since, alarm.target)).takeIf { kind == RingKind.PLANNED } ?: Duration.ZERO,
        snooze = offer,
        style = alarm.wakeStyle,
        use24h = use24h,
        zone = zone,
    )
}

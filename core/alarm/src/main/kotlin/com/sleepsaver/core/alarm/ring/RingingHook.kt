package com.sleepsaver.core.alarm.ring

import com.sleepsaver.core.alarm.AlarmText
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import java.time.Duration
import java.time.ZoneId

/**
 * This ring as `NightController.state.ringing` shows it to the ringing screen, offering [offer] as its snooze. Null
 * without its alarm (nothing to show). Used for the ring AlarmRingService sounds and for a fallback ring that only
 * notification 303 stands for.
 */
internal fun MirroredRinging.toRinging(offer: SnoozeOffer, use24h: Boolean, zone: ZoneId): Ringing? {
    val ringAlarm = alarm ?: return null
    val planned = kind == RingKind.PLANNED
    return Ringing(
        kind = kind,
        reason = ringReason,
        since = since,
        target = ringAlarm.target.takeIf { kind != RingKind.TEST },
        wakeDate = ringAlarm.wakeDate.takeIf { planned },
        earlyBy = if (planned) Duration.ofMinutes(AlarmText.minutesEarly(since, ringAlarm.target)) else Duration.ZERO,
        snooze = offer,
        style = ringAlarm.wakeStyle,
        use24h = use24h,
        zone = zone,
    )
}

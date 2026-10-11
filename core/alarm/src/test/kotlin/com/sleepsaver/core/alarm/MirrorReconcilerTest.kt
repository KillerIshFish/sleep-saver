package com.sleepsaver.core.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredEvent
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredHush
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.config.SleepConfig
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MirrorReconcilerTest {

    private val config = SleepConfig.DEFAULT
    private val reconciler = MirrorReconciler(config)
    private val zone = ZoneId.of("America/New_York")
    private val wednesday = LocalDate.of(2026, 10, 7)
    private val thursday = wednesday.plusDays(1)
    private val today = alarm(wednesday, "06:30")
    private val tomorrow = alarm(thursday, "06:30")
    private val base = MirrorState(armed = today, upcoming = listOf(tomorrow))

    private fun at(time: String, date: LocalDate = wednesday): Instant = ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant()

    private fun alarm(date: LocalDate, time: String): MirroredAlarm {
        val target = ZonedDateTime.of(date, LocalTime.parse(time), zone)
        return MirroredAlarm(
            occurrenceId = "${date}T$time",
            date = date.toString(),
            localTime = time,
            targetEpochMs = target.toInstant().toEpochMilli(),
            offsetSec = target.offset.totalSeconds,
            mode = "SURFACING",
            baseWindowMin = 30,
            windowStartEpochMs = target.toInstant().minus(Duration.ofMinutes(30)).toEpochMilli(),
            debtAware = false,
            style = "STANDARD",
            tone = "SOFT_CHIMES",
            volume = 0.8f,
            snoozeStyle = "TAPERING",
            snoozeFixedMin = 9,
            snoozeFixedCount = 3,
            overrunMin = 10,
            zThreshold = 2.5,
            expectedOnsetEpochMs = null,
        )
    }

    private fun ringing(alarm: MirroredAlarm, since: Instant, isBackup: Boolean = false) = MirroredRinging(
        ringKind = RingKind.PLANNED.name,
        trigger = "PLAN",
        occurrenceId = alarm.occurrenceId,
        sinceEpochMs = since.toEpochMilli(),
        snoozesUsed = 0,
        reason = RingReason.DEADLINE.name,
        alarm = alarm,
        eventId = null,
        isBackup = isBackup,
        expiresAtEpochMs = (since + Duration.ofMinutes(12)).toEpochMilli(),
        event = MirroredEvent.firstRing(RingKind.PLANNED, alarm.occurrenceId, alarm, since, RingReason.DEADLINE),
    )

    private fun snooze(alarm: MirroredAlarm, until: Instant) = MirroredExtra(
        trigger = ExtraTrigger.SNOOZE.name,
        atEpochMs = until.toEpochMilli(),
        ringKind = RingKind.PLANNED.name,
        occurrenceId = alarm.occurrenceId,
        snoozesUsed = 1,
        reason = RingReason.SNOOZE_END.name,
        alarm = alarm,
        expiresAtEpochMs = (until + Duration.ofMinutes(10)).toEpochMilli(),
        event = MirroredEvent.firstRing(RingKind.PLANNED, alarm.occurrenceId, alarm, alarm.target, RingReason.DEADLINE),
    )

    @Test
    fun `a ring 20 min ago with no service is timed out, gets its backup, and the next alarm is armed`() {
        // Timed out at 06:40, backup due at 06:45: 5 min overdue is within missedGrace, so it rings now.
        val now = at("06:50")

        val r = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), now, ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.ringing).isNull()
        assertThat(r.state.pendingEvents.single().outcome).isEqualTo(RingOutcome.TIMED_OUT.name)
        assertThat(r.state.handledIds).contains(today.occurrenceId)
        val backup = checkNotNull(r.state.extra)
        assertThat(backup.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(backup.isBackup).isTrue()
        assertThat(backup.at).isEqualTo(now + config.alarm.catchUpDelay)
        assertThat(r.actions).contains(AlarmAction.ArmExtra(backup.at))
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `a ring 2 h ago with no service is MISSED and handled, with no backup hours late`() {
        val now = at("08:30")

        val r = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), now, ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.ringing).isNull()
        assertThat(r.state.extra).isNull()
        assertThat(r.actions.filterIsInstance<AlarmAction.ArmExtra>()).isEmpty()
        val missed = r.state.pendingEvents.single()
        assertThat(missed.outcome).isEqualTo(RingOutcome.MISSED.name)
        assertThat(missed.occurrenceId).isEqualTo(today.occurrenceId)
        assertThat(r.state.handledIds).contains(today.occurrenceId)
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `the backup is still rung at exactly missedGrace overdue and dropped just after`() {
        val due = at("06:45") // 06:30 + ringTimeout + backupRingDelay
        val atEdge = due + config.alarm.missedGrace
        val past = atEdge.plusSeconds(1)

        val edge = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), atEdge, ringServiceRunning = false, nightServiceRunning = false)
        val late = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), past, ringServiceRunning = false, nightServiceRunning = false)

        assertThat(edge.state.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(late.state.extra).isNull()
        assertThat(late.state.pendingEvents.single().outcome).isEqualTo(RingOutcome.MISSED.name)
    }

    @Test
    fun `a ring 3 min ago resumes in 5 s, and its occurrence gets no catch-up`() {
        val now = at("06:33")

        val r = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), now, ringServiceRunning = false, nightServiceRunning = false)

        val resume = checkNotNull(r.state.extra)
        assertThat(resume.extraTrigger).isEqualTo(ExtraTrigger.RESUME)
        assertThat(resume.at).isEqualTo(now + config.alarm.catchUpDelay)
        assertThat(resume.occurrenceId).isEqualTo(today.occurrenceId)
        assertThat(resume.event).isNotNull()
        assertThat(r.state.ringing).isNull()
        assertThat(r.state.pendingEvents).isEmpty()
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `a fallback ring (no service could start) waits on its notification, then times out`() {
        val fallback = ringing(today, at("06:30")).copy(fallback = true)

        val waiting = reconciler.reconcile(base.copy(ringing = fallback), at("06:33"), ringServiceRunning = false, nightServiceRunning = false)
        val over = reconciler.reconcile(base.copy(ringing = fallback), at("06:41"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(waiting.state.ringing).isEqualTo(fallback)
        assertThat(waiting.state.extra).isNull()
        assertThat(waiting.state.pendingEvents).isEmpty()
        assertThat(over.state.ringing).isNull()
        assertThat(over.state.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(over.state.pendingEvents.single().outcome).isEqualTo(RingOutcome.TIMED_OUT.name)
    }

    @Test
    fun `with the ring service running, its own ring gets no catch-up and no event`() {
        val r = reconciler.reconcile(base.copy(ringing = ringing(today, at("06:30"))), at("06:31"), ringServiceRunning = true, nightServiceRunning = false)

        assertThat(r.state.ringing).isNotNull()
        assertThat(r.state.extra).isNull()
        assertThat(r.state.pendingEvents).isEmpty()
        assertThat(r.state.handledIds).isEmpty()
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `a pending snooze of the armed occurrence blocks a catch-up`() {
        val r = reconciler.reconcile(base.copy(extra = snooze(today, at("06:39"))), at("06:31"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.extra?.extraTrigger).isEqualTo(ExtraTrigger.SNOOZE)
        assertThat(r.state.extra?.at).isEqualTo(at("06:39"))
        assertThat(r.state.pendingEvents).isEmpty()
        assertThat(r.actions).contains(AlarmAction.ArmExtra(at("06:39")))
    }

    @Test
    fun `a wake time missed within grace catches up in 5 s`() {
        val now = at("06:40")

        val r = reconciler.reconcile(base, now, ringServiceRunning = false, nightServiceRunning = false)

        val catchUp = checkNotNull(r.state.extra)
        assertThat(catchUp.extraTrigger).isEqualTo(ExtraTrigger.CATCH_UP)
        assertThat(catchUp.ringReason).isEqualTo(RingReason.CATCH_UP)
        assertThat(catchUp.at).isEqualTo(now + config.alarm.catchUpDelay)
        assertThat(catchUp.alarm).isEqualTo(today)
        assertThat(r.state.armed).isEqualTo(tomorrow)
        assertThat(r.state.pendingEvents).isEmpty()
    }

    @Test
    fun `a wake time missed past grace is recorded MISSED and handled`() {
        val r = reconciler.reconcile(base, at("06:50"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.extra).isNull()
        val missed = r.state.pendingEvents.single()
        assertThat(missed.outcome).isEqualTo(RingOutcome.MISSED.name)
        assertThat(missed.occurrenceId).isEqualTo(today.occurrenceId)
        assertThat(r.state.handledIds).containsExactly(today.occurrenceId)
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `a handled occurrence whose wake time passed is simply left behind`() {
        val r = reconciler.reconcile(base.copy(handledIds = listOf(today.occurrenceId)), at("06:40"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.extra).isNull()
        assertThat(r.state.pendingEvents).isEmpty()
        assertThat(r.state.armed).isEqualTo(tomorrow)
    }

    @Test
    fun `a snooze end passed beyond grace is dropped as MISSED`() {
        val r = reconciler.reconcile(MirrorState(extra = snooze(today, at("06:39"))), at("07:30"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.extra).isNull()
        assertThat(r.actions).contains(AlarmAction.Cancel(PendingAlarm.RING_EXTRA))
        assertThat(r.state.pendingEvents.single().outcome).isEqualTo(RingOutcome.MISSED.name)
        assertThat(r.state.handledIds).contains(today.occurrenceId)
    }

    @Test
    fun `a backup ring that died past its timeout is MISSED with no further ring`() {
        val r = reconciler.reconcile(
            MirrorState(ringing = ringing(today, at("06:45"), isBackup = true)),
            at("07:30"),
            ringServiceRunning = false,
            nightServiceRunning = false,
        )

        assertThat(r.state.extra).isNull()
        assertThat(r.state.pendingEvents.single().outcome).isEqualTo(RingOutcome.MISSED.name)
    }

    @Test
    fun `an expired night is cleared and its movement log closed`() {
        val night = MirroredNight(anchorId = 4, anchoredAtEpochMs = at("22:00", wednesday.minusDays(1)).toEpochMilli(), offsetSec = -14_400, wholeNightLog = true, logId = 9, endsAtEpochMs = at("12:00").toEpochMilli())

        val r = reconciler.reconcile(MirrorState(night = night), at("13:00"), ringServiceRunning = false, nightServiceRunning = false)

        assertThat(r.state.night).isNull()
        assertThat(r.logsToClose).containsExactly(9L to at("12:00"))
        assertThat(r.endedNight).isEqualTo(night) // a NightService still logging it stops
        assertThat(reconciler.reconcile(MirrorState(night = night), at("11:00"), false, false).endedNight).isNull()
    }

    @Test
    fun `Hush without a running night service is cleared, and kept while it runs`() {
        val hush = MirroredHush(color = "PINK", volume = 0.25f, timerMin = 45, endsAtEpochMs = at("23:45").toEpochMilli(), playId = 3)

        val dead = reconciler.reconcile(MirrorState(hush = hush), at("23:00"), ringServiceRunning = false, nightServiceRunning = false)
        val alive = reconciler.reconcile(MirrorState(hush = hush), at("23:00"), ringServiceRunning = false, nightServiceRunning = true)

        assertThat(dead.state.hush).isNull()
        assertThat(dead.hushPlaysToEnd).containsExactly(3L to at("23:00"))
        assertThat(alive.state.hush).isEqualTo(hush)
    }

    @Test
    fun `a zone change re-resolves the armed alarm to the same local time`() {
        val london = ZoneId.of("Europe/London")

        val r = reconciler.reconcile(base, at("01:00"), ringServiceRunning = false, nightServiceRunning = false, zone = london)

        val expected = ZonedDateTime.of(wednesday, LocalTime.of(6, 30), london).toInstant()
        assertThat(r.state.armed?.target).isEqualTo(expected)
        assertThat(r.state.armed?.windowStart).isEqualTo(expected - Duration.ofMinutes(30))
        assertThat(r.state.armed?.occurrenceId).isEqualTo(today.occurrenceId)
    }
}

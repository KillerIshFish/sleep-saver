package com.sleepsaver.core.testing

import com.sleepsaver.core.data.hooks.ActiveCatnap
import com.sleepsaver.core.data.hooks.ActiveNight
import com.sleepsaver.core.data.hooks.HushState
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.hooks.SensingState
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmOccurrence
import com.sleepsaver.core.domain.alarm.AlarmSource
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.alarm.SurfacingWindow
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.nightsteps.EveningResult
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.NightStepsPlanner
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepLog
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.nightsteps.routineFor
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.KeelBand
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.core.domain.patterns.OddWay
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.domain.patterns.PlanCheck
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.MorningPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Literal v2 samples for ViewModel and screenshot tests: week plans, planned alarms, Night steps, patterns, alarm
 * history and every night state. Built from literal values and A1's implemented helpers only; nothing here calls a
 * stubbed (B1/B2) engine, so the samples work before those engines exist. Dates follow [SampleData]: today is
 * Tue 2026-10-06 in New York, the planned wake time 06:45 and the target bedtime 23:00.
 */
object PlanSamples {
    val zone: ZoneId get() = SampleData.zone
    val today: LocalDate get() = SampleData.today
    val wakeTime: LocalTime = LocalTime.of(6, 45)
    val bedtime: LocalTime = LocalTime.of(23, 0)

    private val config = SleepConfig.DEFAULT

    /** [time] on [date] in [zone]. */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId = this.zone): Instant = ZonedDateTime.of(date, time, zone).toInstant()

    // ---------------------------------------------------------------- Week plan

    /** Mon–Fri SURFACING at 06:45 (window 30 min, Standard); Sat–Sun no alarm, Calm; every evening the default. */
    fun weekPlan(): WeekPlan = WeekPlan(
        DayOfWeek.values().map { day ->
            val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
            DayPlan(
                day = day,
                morning = MorningPlan(
                    wakeTime = if (weekend) null else wakeTime,
                    alarm = AlarmSettings(
                        mode = if (weekend) AlarmMode.OFF else AlarmMode.SURFACING,
                        window = Duration.ofMinutes(30),
                        style = if (weekend) WakeStyle.CALM else WakeStyle.STANDARD,
                    ),
                ),
                evening = EveningPlan(),
            )
        },
    )

    fun oneOffs(dayOff: LocalDate? = null, justOnce: DateOverride? = null, pause: PlanPause? = null): OneOffs = OneOffs(
        overrides = listOfNotNull(dayOff?.let { DateOverride(it, OverrideKind.DAY_OFF) }, justOnce),
        pause = pause,
    )

    /**
     * Tomorrow's alarm by default. A debt-aware night widens the window by debtAwareWidening (capped at windowMax);
     * FIXED has a zero-length window. Planned bedtime 23:00 the evening before.
     */
    fun plannedAlarm(
        date: LocalDate = today.plusDays(1),
        time: LocalTime = wakeTime,
        mode: AlarmMode = AlarmMode.SURFACING,
        window: Duration = Duration.ofMinutes(30),
        debtAware: Boolean = false,
        style: WakeStyle = WakeStyle.STANDARD,
        zone: ZoneId = this.zone,
    ): PlannedAlarm {
        val target = ZonedDateTime.of(date, time, zone)
        val settings = AlarmSettings(mode = mode, window = window, style = style)
        val length = when {
            mode != AlarmMode.SURFACING -> Duration.ZERO
            debtAware -> minOf(window + config.alarm.debtAwareWidening, config.alarm.windowMax)
            else -> window
        }
        return PlannedAlarm(
            occurrence = AlarmOccurrence(date, time, target.toInstant(), target.offset, settings, AlarmSource.DAY_PLAN),
            window = SurfacingWindow(target.toInstant() - length, target.toInstant(), debtAware),
            plannedBedtime = at(date.minusDays(1), bedtime, zone),
            debtStatus = if (debtAware) DebtStatus.MODERATE else DebtStatus.LOW,
            latency = config.sleepLatency,
            need = Duration.ofHours(8),
        )
    }

    /** Debt preview inputs: SampleData's nights with [weekPlan] merged into the settings. */
    fun debtPreviewInputs(): DebtPreviewInputs =
        DebtPreviewInputs(nights = SampleData.nights(), settings = UserSleepSettings(week = weekPlan()), zone = zone)

    // ---------------------------------------------------------------- Night steps

    /** The seven new-install Night steps (ids 1..7), Screens down, Tomorrow list and Slow tide selected. */
    fun activities(): List<RoutineActivity> = listOf(
        Technique.SCREENS_DOWN to "Screens down",
        Technique.TOMORROW_LIST to "Tomorrow list",
        Technique.SLOW_TIDE to "Slow tide breathing",
        Technique.READ_ON_PAPER to "Read on paper",
        Technique.STRETCHING to "Stretching",
        Technique.WARM_SHOWER to "Warm shower or bath",
        Technique.JOURNALING to "Journaling",
    ).mapIndexed { index, (technique, label) ->
        val timing = config.steps.timing(technique)
        RoutineActivity(
            id = index + 1L,
            label = label,
            emoji = technique.emoji,
            isCustom = false,
            selected = technique.defaultOn,
            technique = technique,
            lead = timing.lead,
            duration = timing.duration,
        )
    }

    /**
     * Tonight's checklist (NightStepsPlanner, A1): the selected [activities], bedtime 23:00, the Night steps window
     * from the wind-down phase start (B − melatoninHalfWidth − windDownLength = 21:30). The first [done] steps in
     * time order are checked.
     */
    fun nightStepsSession(
        nightOf: LocalDate = today,
        bedtime: LocalTime = this.bedtime,
        done: Int = 1,
        enabled: Boolean = true,
        fromSettingsOnly: Boolean = false,
        activities: List<RoutineActivity> = activities(),
        zone: ZoneId = this.zone,
    ): NightStepsSession {
        val b = ZonedDateTime.of(nightOf, bedtime, zone).let { if (bedtime < LocalTime.NOON) it.plusDays(1) else it }.toInstant()
        val windowStart = b - config.melatoninHalfWidth - config.windDownLength
        val routine = routineFor(EveningPlan(), activities).sortedByDescending { it.lead }
        val logs = routine.take(done).map { activity ->
            val doneAt = b - activity.lead + Duration.ofMinutes(1)
            StepLog(nightOf, activity.id, activity.technique, doneAt, zone.rules.getOffset(doneAt))
        }
        return NightStepsPlanner(config).plan(nightOf, windowStart, b, routine, logs, enabled, fromSettingsOnly)
    }

    /**
     * 14 evenings ending tonight (OPEN): three before the first log, one with steps switched off, two bridged misses.
     * "4 of the last 5 nights with steps", quiet streak 7.
     */
    fun adherence(tonight: LocalDate = today): StepAdherence {
        val states = listOf(
            EveningState.NEUTRAL, EveningState.NEUTRAL, EveningState.NEUTRAL,
            EveningState.DONE, EveningState.DONE, EveningState.MISSED, EveningState.DONE,
            EveningState.NEUTRAL, EveningState.DONE, EveningState.DONE, EveningState.MISSED,
            EveningState.DONE, EveningState.DONE, EveningState.OPEN,
        )
        val evenings = states.mapIndexed { index, state ->
            val evening = tonight.minusDays((states.size - 1 - index).toLong())
            val stepsOff = state == EveningState.NEUTRAL && index == 7
            val planned = if (stepsOff) 0 else 3
            val done = when (state) {
                EveningState.DONE -> if (index % 2 == 0) 3 else 2
                EveningState.OPEN -> 1
                else -> 0
            }
            EveningResult(evening, planned, done, state)
        }
        return StepAdherence(evenings = evenings, doneRecent = 4, countedRecent = 5, quietStreak = 7)
    }

    val suggestions: List<StepSuggestion> = listOf(
        StepSuggestion(Technique.BODY_SCAN, SuggestionReason.SLOW_TO_SETTLE),
        StepSuggestion(Technique.COOL_DARK_ROOM, SuggestionReason.RESTLESS_NIGHTS),
    )

    // ---------------------------------------------------------------- Patterns

    fun keelReady(): KeelResult = KeelResult.Ready(
        score = 84,
        band = KeelBand.EVEN,
        validDays = 14,
        onsetSd = Duration.ofMinutes(28),
        wakeSd = Duration.ofMinutes(17),
        asOf = today,
    )

    fun keelCalibrating(): KeelResult = KeelResult.Calibrating(validDays = 4, needed = config.keel.minValidDays)

    /** Weekend lie-ins: mid-sleep 75 min later on free days. */
    fun socialJetlag(): SocialJetlag =
        SocialJetlag(shift = Duration.ofMinutes(75), freeNights = 8, workNights = 20, freeDaysFromPlan = true)

    fun planCheck(warn: Boolean): PlanCheck = if (warn) {
        PlanCheck(
            plannedKeel = 66,
            plannedBand = KeelBand.CHOPPY,
            freeDayWakeShift = Duration.ofMinutes(105),
            plannedSocialJetlag = Duration.ofMinutes(95),
            warnFreeDayShift = true,
            warnSocialJetlag = true,
        )
    } else {
        PlanCheck(
            plannedKeel = 91,
            plannedBand = KeelBand.EVEN,
            freeDayWakeShift = Duration.ofMinutes(20),
            plannedSocialJetlag = Duration.ofMinutes(15),
            warnFreeDayShift = false,
            warnSocialJetlag = false,
        )
    }

    /** One CLEAR, one POSSIBLE and one NOT_ENOUGH row. */
    fun markEffects(): List<MarkEffect> = listOf(
        MarkEffect(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, withNights = 9, withoutNights = 41, difference = -42.0, low = -61.0, high = -24.0),
        MarkEffect(Mark.LATE_CAFFEINE, EffectMetric.AWAKE, PatternLabel.POSSIBLE, withNights = 7, withoutNights = 43, difference = 8.0, low = 1.5, high = 14.0),
        MarkEffect(Mark.STRESS, EffectMetric.MORNING_FEEL, PatternLabel.NOT_ENOUGH, withNights = 4, withoutNights = 46, difference = null, low = null, high = null),
    )

    fun oddNight(wakeDate: LocalDate = today): OddNight = OddNight(
        wakeDate = wakeDate,
        ways = setOf(OddWay.LESS_SLEEP, OddWay.LATER_ONSET),
        totalDelta = Duration.ofMinutes(-70),
        awakeDelta = Duration.ofMinutes(5),
        onsetDelta = Duration.ofMinutes(75),
    )

    /** Drop anchor at 22:55 on the evening before [wakeDate]. */
    fun anchor(wakeDate: LocalDate = today, id: Long = 1, wholeNightLog: Boolean = false): Anchor {
        val at = at(wakeDate.minusDays(1), LocalTime.of(22, 55))
        return Anchor(id, at, zone.rules.getOffset(at), wakeDate, AnchorSource.TONIGHT, wholeNightLog)
    }

    /** "You fell asleep about 15 min after dropping anchor." */
    fun anchorStats(wakeDate: LocalDate = today): AnchorNightStats = AnchorNightStats(
        wakeDate = wakeDate,
        anchor = anchor(wakeDate),
        latency = Duration.ofMinutes(14),
        latencyShown = Duration.ofMinutes(15),
        efficiency = 0.91,
    )

    /** Literal Garmin-prior bins (30 min): deep likely early after onset, rare near the end. */
    fun deepPrior(): DeepPrior = DeepPrior(
        bin = Duration.ofMinutes(30),
        sinceOnset = listOf(0.30, 0.55, 0.50, 0.35, 0.25, 0.18, 0.12, 0.08, 0.06, 0.05, 0.04, 0.03, 0.03, 0.02, 0.02, 0.02),
        beforeWake = listOf(0.03, 0.04, 0.05, 0.07, 0.10, 0.14, 0.20, 0.28, 0.36, 0.45, 0.50, 0.42, 0.30, 0.20, 0.10, 0.05),
        nightsUsed = 14,
    )

    // ---------------------------------------------------------------- Alarm history

    /**
     * This morning's planned 06:45 alarm, stopped 2 min after it rang. RESTLESS rang at 06:27 (18 min early);
     * FIXED_TIME is a FIXED alarm (zero window); every other reason rang at the wake time. TEST and CATNAP_END give
     * a TEST / NAP event instead (no occurrence id, zero window), as `lastTestRing` and the history expect.
     */
    fun alarmEvent(reason: RingReason = RingReason.RESTLESS, wakeDate: LocalDate = today): AlarmEvent {
        val target = at(wakeDate, wakeTime)
        val kind = when (reason) {
            RingReason.TEST -> RingKind.TEST
            RingReason.CATNAP_END -> RingKind.NAP
            else -> RingKind.PLANNED
        }
        val fixed = reason == RingReason.FIXED_TIME || kind != RingKind.PLANNED
        val firstRing = if (reason == RingReason.RESTLESS) at(wakeDate, LocalTime.of(6, 27)) else target
        return AlarmEvent(
            id = 1,
            kind = kind,
            occurrenceId = if (kind == RingKind.PLANNED) AlarmOccurrence.occurrenceId(wakeDate, wakeTime) else null,
            wakeDate = wakeDate,
            target = target,
            windowStart = if (fixed) target else target - Duration.ofMinutes(30),
            mode = if (fixed) AlarmMode.FIXED else AlarmMode.SURFACING,
            style = WakeStyle.STANDARD,
            firstRingAt = firstRing,
            reason = reason,
            maxZ = if (reason == RingReason.RESTLESS) 3.4 else if (fixed) null else 1.6,
            stoppedAt = firstRing + Duration.ofMinutes(2),
            outcome = RingOutcome.STOPPED,
            sensingStart = if (fixed) null else target - Duration.ofMinutes(45),
            offset = zone.rules.getOffset(target),
        )
    }

    /** Wake landing of [alarmEvent] (RESTLESS) with the watch's [stage] at the ring. */
    fun landing(stage: LandingStage = LandingStage.LIGHT, wakeDate: LocalDate = today): WakeLanding = WakeLanding(
        event = alarmEvent(RingReason.RESTLESS, wakeDate),
        stageAtRing = stage,
        majorityBefore = stage,
        minutesEarly = 18,
        windowDeepShare = 0.1,
        windowNonDeepMinutes = 27,
        hasStages = true,
    )

    fun tuning(): AlarmTuning {
        val changedAt = at(today, LocalTime.of(8, 0))
        return AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, changedAt, zone.rules.getOffset(changedAt))
    }

    // ---------------------------------------------------------------- Night states

    /** The NightController states screens are drawn in. */
    enum class NightVariant {
        IDLE,
        ANCHORED,
        ANCHORED_WITH_LOG,
        HUSH_PLAYING,
        CATNAP_PENDING,
        RINGING_PLANNED,
        RINGING_CATNAP,
        RINGING_TEST,
        SNOOZED,
        TEST_PENDING,
    }

    /**
     * [variant] for the night of [nightOf] (anchored 22:55, wake time 06:45 the next morning). Catnap and test
     * variants happen on [nightOf] itself (catnap 14:00 → 14:25, test ring at 10:21).
     */
    fun nightState(
        variant: NightVariant = NightVariant.IDLE,
        nightOf: LocalDate = today,
        use24h: Boolean = false,
        zone: ZoneId = this.zone,
    ): NightState {
        val wakeDate = nightOf.plusDays(1)
        val anchoredAt = at(nightOf, LocalTime.of(22, 55), zone)
        val night = ActiveNight(anchorId = 1, anchoredAt = anchoredAt, offset = zone.rules.getOffset(anchoredAt), wholeNightLog = false)
        val target = at(wakeDate, wakeTime, zone)
        val restlessAt = at(wakeDate, LocalTime.of(6, 27), zone)
        val catnapStart = at(nightOf, LocalTime.of(14, 0), zone)
        val catnapRing = at(nightOf, LocalTime.of(14, 25), zone)
        return when (variant) {
            NightVariant.IDLE -> NightState()
            NightVariant.ANCHORED -> NightState(night = night)
            NightVariant.ANCHORED_WITH_LOG -> NightState(
                night = night.copy(wholeNightLog = true),
                sensing = SensingState(SensingMode.WHOLE_NIGHT, since = anchoredAt, running = true),
            )
            NightVariant.HUSH_PLAYING -> NightState(
                night = night,
                hush = HushState(HushColor.PINK, volume = config.hush.defaultVolume, timer = config.hush.defaultTimer, endsAt = anchoredAt + config.hush.defaultTimer),
            )
            NightVariant.CATNAP_PENDING -> NightState(catnap = ActiveCatnap(catnapStart, catnapRing, Duration.ofMinutes(20)))
            NightVariant.RINGING_PLANNED -> NightState(
                night = night,
                ringing = Ringing(
                    kind = RingKind.PLANNED,
                    reason = RingReason.RESTLESS,
                    since = restlessAt,
                    target = target,
                    wakeDate = wakeDate,
                    earlyBy = Duration.between(restlessAt, target),
                    snooze = SnoozeOffer(until = restlessAt + Duration.ofMinutes(9), lastOne = false),
                    style = WakeStyle.STANDARD,
                    use24h = use24h,
                    zone = zone,
                ),
            )
            NightVariant.RINGING_CATNAP -> NightState(
                ringing = Ringing(
                    kind = RingKind.NAP,
                    reason = RingReason.CATNAP_END,
                    since = catnapRing,
                    target = catnapRing,
                    wakeDate = null,
                    earlyBy = Duration.ZERO,
                    // A catnap snoozes FIXED(catnap.snooze × catnap.snoozeCount) (§4.4 T16): one 5-min snooze, the last.
                    snooze = SnoozeOffer(until = catnapRing + config.catnap.snooze, lastOne = config.catnap.snoozeCount <= 1),
                    style = WakeStyle.STANDARD,
                    use24h = use24h,
                    zone = zone,
                ),
            )
            NightVariant.RINGING_TEST -> NightState(
                ringing = Ringing(
                    kind = RingKind.TEST,
                    reason = RingReason.TEST,
                    since = at(nightOf, LocalTime.of(10, 21), zone),
                    target = null,
                    wakeDate = null,
                    earlyBy = Duration.ZERO,
                    snooze = SnoozeOffer(until = null, lastOne = false),
                    style = WakeStyle.STANDARD,
                    use24h = use24h,
                    zone = zone,
                ),
            )
            NightVariant.SNOOZED -> NightState(night = night, snoozedUntil = restlessAt + Duration.ofMinutes(9))
            NightVariant.TEST_PENDING -> NightState(testPendingAt = at(nightOf, LocalTime.of(10, 21), zone))
        }
    }
}

package com.sleepsaver.feature.review.common

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.feelEditable
import com.sleepsaver.core.domain.journal.markEditable
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.feature.review.edit.NightEditorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** Today's date on the device clock. */
internal fun TimeProvider.today(): LocalDate = now().atZone(zone()).toLocalDate()

/** The route's ISO date, or today when it doesn't parse (a hand-typed deep link). */
internal fun TimeProvider.parseWakeDate(value: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { today() }

/**
 * Opens the editor on [wakeDate]'s night. A missing night starts at typical wake − need, using the schedule's
 * typical wake for that day when there is one, else the onboarding answer.
 */
internal suspend fun loadEditor(
    wakeDate: LocalDate,
    sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    insightsRepository: InsightsRepository,
    timeProvider: TimeProvider,
): NightEditorState {
    val night = sleepRepository.night(wakeDate).first()
    val settings = settingsRepository.current().sleep
    val typicalWake = if (night?.isPresent == true) {
        settings.typicalWake
    } else {
        runCatching { withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { insightsRepository.schedule(wakeDate).first() } }
            .getOrNull()?.typicalWake?.toLocalTime() ?: settings.typicalWake
    }
    return NightEditorState.from(wakeDate, night, settings.sleepNeed, typicalWake, timeProvider.zone(), timeProvider.now())
}

private const val SCHEDULE_TIMEOUT_MS = 1_500L

/** What can still be changed for one wake date's journal, as of [now] (the latest minute tick). */
internal data class JournalWindow(val today: LocalDate, val now: Instant, val feelEditable: Boolean, val marksEditable: Boolean)

/**
 * [wakeDate]'s journal window, re-checked every minute: Morning feel stays editable all of its own day and the next
 * one until that day's wake time (`feelEditable`, §2.7), marks for markEditDays (`markEditable`). Without schedule
 * inputs (still loading, or they failed) feel locks at midnight.
 */
internal fun journalWindow(
    wakeDate: LocalDate,
    timeProvider: TimeProvider,
    patternsRepository: PatternsRepository,
    config: SleepConfig = SleepConfig.DEFAULT,
): Flow<JournalWindow> = combine(
    patternsRepository.debtPreviewInputs.onStart { emit(null) }.catch { emit(null) },
    timeProvider.minuteTicks,
) { inputs, now ->
    val today = now.atZone(timeProvider.zone()).toLocalDate()
    JournalWindow(
        today = today,
        now = now,
        feelEditable = feelEditable(wakeDate, today, now, todayWake(inputs, today, config)),
        marksEditable = markEditable(wakeDate, today, config),
    )
}.distinctUntilChanged()

/**
 * The wake instant of [today]: `ScheduleTimes.wake(today, …)` (plan-aware: a set time, else the usual wake) in the
 * inputs' zone. null without inputs.
 */
internal fun todayWake(inputs: DebtPreviewInputs?, today: LocalDate, config: SleepConfig = SleepConfig.DEFAULT): Instant? {
    inputs ?: return null
    val wake = ScheduleTimes(config).wake(today, inputs.nights, inputs.settings.timing).first
    return ZonedDateTime.of(today, wake, inputs.zone).toInstant()
}

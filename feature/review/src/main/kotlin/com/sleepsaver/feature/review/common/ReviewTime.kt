package com.sleepsaver.feature.review.common

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.feature.review.edit.NightEditorState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

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
    return NightEditorState.from(wakeDate, night, settings.sleepNeed, typicalWake, timeProvider.zone())
}

private const val SCHEDULE_TIMEOUT_MS = 1_500L

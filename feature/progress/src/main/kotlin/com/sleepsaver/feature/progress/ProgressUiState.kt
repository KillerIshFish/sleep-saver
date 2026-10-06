package com.sleepsaver.feature.progress

import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.feature.progress.chart.DebtChart
import com.sleepsaver.feature.progress.chart.QualitySummary
import com.sleepsaver.feature.progress.chart.SleepTimesChart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * A 14-day window of wake dates ending on [to]. Window 0 ends today; each step back moves 14 days.
 * [canGoBack] is false once the window already holds the oldest night; [canGoForward] is false on window 0.
 */
data class ProgressWindow(
    val from: LocalDate,
    val to: LocalDate,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
) {
    /** Oldest first. */
    val dates: List<LocalDate> get() = (0 until LENGTH_DAYS).map { from.plusDays(it.toLong()) }

    companion object {
        const val LENGTH_DAYS = 14

        /** The window [windowsBack] steps before the one ending on [today]. */
        fun of(today: LocalDate, windowsBack: Int, oldestNight: LocalDate?): ProgressWindow {
            val back = windowsBack.coerceAtLeast(0)
            val to = today.minusDays(back.toLong() * LENGTH_DAYS)
            val from = to.minusDays(LENGTH_DAYS - 1L)
            return ProgressWindow(
                from = from,
                to = to,
                canGoBack = oldestNight != null && oldestNight < from,
                canGoForward = back > 0,
            )
        }
    }
}

/** A row of "All sleep times", newest first. Times are local to the offsets the night was recorded in. */
data class NightRow(
    val wakeDate: LocalDate,
    val fellAsleep: LocalTime?,
    val wokeUp: LocalTime?,
    val total: Duration?,
    val isMissing: Boolean,
    val isEdited: Boolean,
) {
    companion object {
        fun of(wakeDate: LocalDate, night: Night?): NightRow {
            val present = night?.takeIf { it.isPresent }
            return NightRow(
                wakeDate = wakeDate,
                fellAsleep = present?.fellAsleep?.localTime(present.fellAsleepOffset),
                wokeUp = present?.wokeUp?.localTime(present.wokeUpOffset),
                total = present?.totalSleep,
                isMissing = present == null,
                isEdited = present?.isEdited == true,
            )
        }

        private fun Instant.localTime(offset: ZoneOffset?): LocalTime? =
            offset?.let { atOffset(it).toLocalTime() }
    }
}

enum class ProgressTab { SLEEP_TIMES, SLEEP_DEBT, SLEEP_QUALITY }

/** Why there are no nights at all, so the empty state can point at the right fix. */
enum class NoNightsReason {
    /** Nothing has come in yet (new install, watch not synced, wrong source). */
    NO_DATA,

    /** Health Connect is missing or needs an update. */
    HEALTH_CONNECT_UNAVAILABLE,

    /** Sleep Saver isn't allowed to read sleep. */
    PERMISSION_DENIED,
}

sealed interface ProgressUiState {
    data object Loading : ProgressUiState

    /** No night with data at all: the whole tab is an empty state. */
    data class NoNights(val today: LocalDate, val reason: NoNightsReason = NoNightsReason.NO_DATA) : ProgressUiState

    data class Ready(
        val today: LocalDate,
        val window: ProgressWindow,
        val sleepTimes: SleepTimesChart,
        /** Newest first. */
        val nightRows: List<NightRow>,
        val debt: DebtChart,
        val quality: QualitySummary,
    ) : ProgressUiState {
        /** False when the window holds no night with data (paged back past a gap). */
        val hasNightsInWindow: Boolean get() = nightRows.any { !it.isMissing }
    }
}

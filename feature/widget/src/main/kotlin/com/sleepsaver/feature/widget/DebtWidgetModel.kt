package com.sleepsaver.feature.widget

import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.PhaseType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** What the widget shows. Equal models render identically, so the widget is redrawn only when this changes. */
internal sealed interface DebtWidgetModel {

    /** No nights to show: the user still has to connect (or sync) in the app. */
    data object Disconnected : DebtWidgetModel

    data class Debt(
        /** The displayed debt in tenths of an hour ("3.2" = 32). */
        val debtTenths: Int,
        val status: DebtStatus,
        /** The phase we're in, else the next one today; null once today's phases are over. */
        val phase: PhaseLine?,
        val clockFormat: ClockFormat,
    ) : DebtWidgetModel

    companion object {
        fun from(state: InsightsState, clockFormat: ClockFormat): DebtWidgetModel = when (state) {
            is InsightsState.Ready -> from(state.insights, clockFormat)
            InsightsState.Loading, InsightsState.NoNights -> Disconnected
        }

        fun from(insights: DailyInsights, clockFormat: ClockFormat): Debt {
            val schedule = insights.schedule
            val current = schedule.currentPhase(insights.now)
            val phase = if (current != null) {
                PhaseLine(current.type, isCurrent = true, time = current.end.localTime(insights.zone))
            } else {
                schedule.upcomingPhases(insights.now).firstOrNull()
                    ?.let { PhaseLine(it.type, isCurrent = false, time = it.start.localTime(insights.zone)) }
            }
            return Debt(
                debtTenths = (insights.debt.debt.toMinutes() / 6.0).roundToInt(),
                status = insights.status,
                phase = phase,
                clockFormat = clockFormat,
            )
        }

        private fun Instant.localTime(zone: ZoneId): LocalTime = atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
    }
}

/** "Morning peak · until 11:20 AM" ([isCurrent], [time] = end) or "Afternoon dip · from 1:30 PM" ([time] = start). */
internal data class PhaseLine(val type: PhaseType, val isCurrent: Boolean, val time: LocalTime)

/**
 * The widget's models over time. Loading is skipped so a cold start doesn't flash the "connect" message;
 * the minute-by-minute insights refresh only produces a new model when something displayed changes.
 */
internal fun debtWidgetModels(state: Flow<InsightsState>, settings: Flow<AppSettings>): Flow<DebtWidgetModel> =
    combine(
        state.filter { it !is InsightsState.Loading },
        settings.map { it.clockFormat }.distinctUntilChanged(),
    ) { insights, clockFormat -> DebtWidgetModel.from(insights, clockFormat) }
        .distinctUntilChanged()

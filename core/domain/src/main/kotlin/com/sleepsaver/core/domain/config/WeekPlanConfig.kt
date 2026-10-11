package com.sleepsaver.core.domain.config

import java.time.Duration
import java.time.LocalTime

/**
 * Week plan and one-offs (docs/PLAN.md §3.11). Example: `config.week.windDownMaxLength` caps a day's wind-down at
 * 150 min, so a 3 h routine is stored as 2.5 h.
 */
data class WeekPlanConfig(
    /** Wind-down phase length range: never below minPhaseLength, so the phase is never stretched into the melatonin window. */
    val windDownMinLength: Duration = Duration.ofMinutes(30),
    val windDownMaxLength: Duration = Duration.ofMinutes(150),
    /** The wind-down length picker steps by this. */
    val windDownStep: Duration = Duration.ofMinutes(15),
    /** One-off rows this many days old still feed the schedule (night owls, Process S history). */
    val overrideHistoryDays: Long = 3,
    /** One-off rows older than this are deleted on the next write. */
    val overridePruneDays: Long = 7,
    /** "Pause alarms until …": the furthest date offered. */
    val pauseMaxDays: Long = 60,
    /** Pre-filled alarm times (usual wake → set time) and preset times round to this. */
    val timeRounding: Duration = Duration.ofMinutes(5),
    /** Time pickers in the editor step by this. */
    val timeStep: Duration = Duration.ofMinutes(5),
    /** Dates considered when looking for the next alarm: today .. today + 7. */
    val alarmLookaheadDays: Int = 8,
    /** At most this many upcoming alarms (AlarmPlanner.upcoming's default limit, ScheduleRepository.upcomingAlarms). */
    val upcomingLimit: Int = 7,
    /** NightRules.isNight ignores a bedtime more than this before the night's end (a stale schedule). */
    val nightBedtimeMaxLead: Duration = Duration.ofHours(18),
    /** Tonight's MORNING phase lasts until this local time. */
    val morningPhaseEnd: LocalTime = LocalTime.NOON,
)

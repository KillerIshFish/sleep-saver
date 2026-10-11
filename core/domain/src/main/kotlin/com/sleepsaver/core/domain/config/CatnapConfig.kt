package com.sleepsaver.core.domain.config

import java.time.Duration
import java.time.LocalTime

/**
 * Catnap (docs/PLAN.md §3.16). Example: a 20-min catnap started at 14:00 rings at 14:25 ([fallAsleep] + length).
 */
data class CatnapConfig(
    val lengths: List<Duration> = listOf(Duration.ofMinutes(10), Duration.ofMinutes(20), Duration.ofMinutes(30)),
    /** Added before the nap length: time to fall asleep. */
    val fallAsleep: Duration = Duration.ofMinutes(5),
    /** Naps this long or longer may leave you groggy (flagged). */
    val groggyFrom: Duration = Duration.ofMinutes(30),
    /** The coffee-nap hint is offered only for naps up to this long (and before the caffeine cutoff). */
    val coffeeMaxLength: Duration = Duration.ofMinutes(20),
    /** Ringing later than this before the target bedtime is CLOSE_TO_BEDTIME. */
    val latestBeforeBedtime: Duration = Duration.ofHours(6),
    /** Starting sooner than this after waking is EARLY. */
    val earliestAfterWake: Duration = Duration.ofHours(3),
    /** The Tonight catnap card shows between these local times (or while a catnap runs). */
    val cardFrom: LocalTime = LocalTime.of(9, 0),
    val cardUntil: LocalTime = LocalTime.of(18, 0),
    /** A catnap ring offers this snooze, this many times. */
    val snooze: Duration = Duration.ofMinutes(5),
    val snoozeCount: Int = 1,
)

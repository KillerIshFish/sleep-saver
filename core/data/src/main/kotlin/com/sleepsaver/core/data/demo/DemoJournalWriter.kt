package com.sleepsaver.core.data.demo

import androidx.room.withTransaction
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.RoutineActivityStore
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStageType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Synthetic journal content for the demo nights, so Marks statistics, Morning feel and Night steps tracking have
 * something to show in demo mode. Written once, right after demo mode sets the real journal rows aside (and removed
 * with them when it is turned off). Deterministic: the same nights always get the same rows.
 *
 *  - Marks on about 20 % of nights, following the demo sleep so the statistics find something: Late caffeine on every
 *    short night (under 6.5 h in bed, every 8–10 days) and Alcohol on the [ALCOHOL_NIGHTS] other nights with the most
 *    time awake. So at least two marks reach 6 nights per side.
 *  - Morning feel on about 70 % of nights, higher after more sleep (Foggy under 6 h … Bright from 8 h).
 *  - Night steps on about 60 % of evenings: at least half of the selected activities, checked before that night.
 */
@Singleton
internal class DemoJournalWriter internal constructor(
    private val database: SleepSaverDatabase,
    private val store: RoutineActivityStore,
    private val demoSource: FakeSleepDataSource,
    private val timeProvider: TimeProvider,
    private val seed: Long,
) {
    @Inject constructor(
        database: SleepSaverDatabase,
        store: RoutineActivityStore,
        demoSource: FakeSleepDataSource,
        timeProvider: TimeProvider,
    ) : this(database, store, demoSource, timeProvider, DEFAULT_SEED)

    /** Writes marks, Morning feel and Night steps logs for every demo night (the tables are expected to be empty). */
    suspend fun writeAll() {
        val zone = timeProvider.zone()
        val nights = demoNights(demoSource.generate(zone = zone).sessions, zone)
        store.seedIfEmpty()
        val steps = database.routineActivityDao().getAll().filter { it.selected }
        val alcohol = nights.filter { !it.short }.sortedWith(compareByDescending<DemoNight> { it.awake }.thenBy { it.wakeDate })
            .take(ALCOHOL_NIGHTS)
            .mapTo(HashSet()) { it.wakeDate }

        val marks = ArrayList<NightMarkEntity>()
        val feel = ArrayList<MorningFeelEntity>()
        val logs = ArrayList<NightStepLogEntity>()
        for (night in nights) {
            val rng = Random(seed * SEED_MIX + night.wakeDate.toEpochDay())
            val mark = when {
                night.short -> Mark.LATE_CAFFEINE
                night.wakeDate in alcohol -> Mark.ALCOHOL
                else -> null
            }
            if (mark != null) marks += mark(night.wakeDate, mark, night.start.minus(MARK_LEAD), zone)

            if (rng.nextDouble() < FEEL_SHARE) {
                val at = night.end.plus(Duration.ofMinutes(rng.nextLong(3, 41)))
                feel += MorningFeelEntity(night.wakeDate.toString(), feelLevel(night.asleep, rng), at.toEpochMilli(), offsetSec(at, zone), FeelSource.REVIEW.name)
            }

            if (steps.isNotEmpty() && rng.nextDouble() < STEPS_SHARE) {
                val count = rng.nextInt(ceil(steps.size * DONE_SHARE).toInt().coerceAtLeast(1), steps.size + 1)
                steps.shuffled(rng).take(count).forEach { activity ->
                    val doneAt = night.start.minus(Duration.ofMinutes(activity.leadMin.toLong())).plus(Duration.ofMinutes(rng.nextLong(0, 10)))
                    val evening = night.wakeDate.minusDays(1).toString()
                    logs += NightStepLogEntity(evening, activity.id, activity.techniqueKey, doneAt.toEpochMilli(), offsetSec(doneAt, zone))
                }
            }
        }
        database.withTransaction {
            database.nightMarkDao().insertAll(marks)
            database.morningFeelDao().insertAll(feel)
            database.nightStepLogDao().insertAll(logs)
        }
    }

    /** One demo night: in bed from the first night session's start to the last one's end. */
    private class DemoNight(val wakeDate: LocalDate, val start: Instant, val end: Instant, val awake: Duration) {
        val inBed: Duration get() = Duration.between(start, end)
        val asleep: Duration get() = inBed - awake
        val short: Boolean get() = inBed < SHORT_NIGHT
    }

    /** Night sessions end before noon on their wake date (naps run in the afternoon). */
    private fun demoNights(sessions: List<RawSleepSession>, zone: ZoneId): List<DemoNight> = sessions
        .filter { it.end.atZone(zone).toLocalTime() < LocalTime.NOON }
        .groupBy { it.end.atZone(zone).toLocalDate() }
        .map { (wakeDate, parts) ->
            val awake = parts.flatMap { it.stages }
                .filter { it.type == SleepStageType.AWAKE }
                .fold(Duration.ZERO) { sum, stage -> sum + Duration.between(stage.start, stage.end) }
            DemoNight(wakeDate, parts.minOf { it.start }, parts.maxOf { it.end }, awake)
        }
        .sortedBy { it.wakeDate }

    private fun mark(wakeDate: LocalDate, mark: Mark, at: Instant, zone: ZoneId) =
        NightMarkEntity(wakeDate.toString(), mark.name, at.toEpochMilli(), offsetSec(at, zone))

    /** 1–2 under 6 h asleep, 2–3 under 7 h, 3–4 under 8 h, else 4–5. */
    private fun feelLevel(asleep: Duration, rng: Random): Int {
        val base = when {
            asleep < Duration.ofHours(6) -> 1
            asleep < Duration.ofHours(7) -> 2
            asleep < Duration.ofHours(8) -> 3
            else -> 4
        }
        return base + rng.nextInt(0, 2)
    }

    private fun offsetSec(at: Instant, zone: ZoneId): Int = zone.rules.getOffset(at).totalSeconds

    private companion object {
        const val DEFAULT_SEED = 20_261_011L
        const val SEED_MIX = 1_000_003L
        const val ALCOHOL_NIGHTS = 6
        const val FEEL_SHARE = 0.7
        const val STEPS_SHARE = 0.6
        /** Matches NightStepsConfig.doneShare, so a demo evening with logs counts as done. */
        val DONE_SHARE = SleepConfig.DEFAULT.steps.doneShare
        val SHORT_NIGHT: Duration = Duration.ofMinutes(390)
        /** Marks are set in the evening, this long before the night starts. */
        val MARK_LEAD: Duration = Duration.ofHours(2)
    }
}

package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.AnchorStatsCalculator
import com.sleepsaver.core.domain.patterns.PersonalLatency
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Drop anchor latencies, from one [AnchorStatsCalculator] pass over (anchors, nights, today): the personal median
 * that replaces `sleepLatency` in bedtimes, alarm windows and the debt preview once it is active, and the valid
 * latencies themselves (Night steps suggestions). Example: anchors 15, 22 and 12 min before onset on the last three
 * nights → latencies [12, 22, 15] min (most recent first), median 15 min, not yet active (fewer than 7 values).
 *
 * DI edges: [SleepRepository], the database (anchors) and [TimeProvider] only, so every repository may depend on it.
 * Both outputs share one upstream that never fails: inputs fall back, a failed pass keeps the previous one.
 */
@Singleton
internal class PersonalLatencySource(
    sleepRepository: SleepRepository,
    anchors: Flow<List<Anchor>>,
    timeProvider: TimeProvider,
    scope: CoroutineScope,
    defaultDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(
        sleepRepository: SleepRepository,
        database: SleepSaverDatabase,
        timeProvider: TimeProvider,
        @ApplicationScope scope: CoroutineScope,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : this(
        sleepRepository,
        database.anchorDao().observeAll().map { rows -> rows.map { it.toDomain() } },
        timeProvider,
        scope,
        defaultDispatcher,
    )

    private val calculator = AnchorStatsCalculator(SleepConfig.DEFAULT)
    private val anchorConfig = SleepConfig.DEFAULT.anchor

    /** One pass's results. */
    data class Latencies(val personal: PersonalLatency, val values: List<Duration>)

    private val pass: Flow<Latencies> = combine(
        sleepRepository.nights.orFallback(TAG, "Nights") { emptyList() },
        anchors.orFallback(TAG, "Anchors query") { emptyList() },
        timeProvider.localDates(TAG),
    ) { nights, anchors, today ->
        guarded(TAG, "Personal latency") { compute(anchors, nights, today) }
    }
        .orLastGood(NONE)
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)

    /** The personal median (`active` once there are personalMinValues latencies). */
    val latency: Flow<PersonalLatency> = pass.map { it.personal }.distinctUntilChanged()

    /** Valid latencies of the nights in (today − personalLookbackDays, today], most recent first. */
    val latencies: Flow<List<Duration>> = pass.map { it.values }.distinctUntilChanged()

    /** The same pass from explicit inputs (the alarm plan reads them fresh from storage). */
    fun compute(anchors: List<Anchor>, nights: List<Night>, today: LocalDate): Latencies {
        val from = today.minusDays(anchorConfig.personalLookbackDays)
        val values = nights
            .filter { it.isPresent && it.wakeDate.isAfter(from) && !it.wakeDate.isAfter(today) }
            .associateBy { it.wakeDate }
            .values
            .sortedByDescending { it.wakeDate }
            .mapNotNull { night -> calculator.choose(anchors, night)?.let { calculator.night(it, night).latency } }
        return Latencies(calculator.personalLatency(anchors, nights, today), values)
    }

    private companion object {
        const val TAG = "PersonalLatency"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        val NONE = Latencies(PersonalLatency(values = 0, median = null, active = false), emptyList())
    }
}

// ---------------------------------------------------------------- Time helpers shared by the v2 repositories

/**
 * The local date in the device zone, re-read on every minute tick and emitted only when it changes (a zone change
 * restarts the ticks, so it is picked up at once). Never fails: without ticks it is today's date, once.
 */
internal fun TimeProvider.localDates(tag: String): Flow<LocalDate> = deviceDays(tag).map { it.date }.distinctUntilChanged()

/** The device zone and the local date there, emitted when either changes (see [localDates]). */
internal fun TimeProvider.deviceDays(tag: String): Flow<DeviceDate> = minuteTicks
    .orFallback(tag, "Minute ticks") { now() }
    .map { now -> zone().let { zone -> DeviceDate(zone, now.atZone(zone).toLocalDate()) } }
    .distinctUntilChanged()

/** A local date and the zone it was read in. */
internal data class DeviceDate(val zone: ZoneId, val date: LocalDate)

/** The offset of the device zone right now (what a row written now is stored with). */
internal fun TimeProvider.currentOffset(): ZoneOffset = zone().rules.getOffset(now())

/** Today in the device zone. */
internal fun TimeProvider.today(): LocalDate = now().atZone(zone()).toLocalDate()

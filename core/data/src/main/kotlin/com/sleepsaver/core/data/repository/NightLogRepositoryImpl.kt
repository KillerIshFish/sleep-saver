package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.MovementLogEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.data.mapping.toEntityOrNull
import com.sleepsaver.core.data.mapping.toPlayEntity
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.alarm.WakeLandingCalculator
import com.sleepsaver.core.domain.alarm.ZTuner
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.schedule.NightRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened at night: Drop anchors, phone movement, alarm history, the restless-trigger tuning and Hush plays.
 * Anchors and Hush plays belong to the wake date of their moment (NightRules.wakeDateOf: 23:10 Monday → Tuesday).
 *
 * DI edges (§3.4): the database, [SleepRepository] (Wake landing reads the watch's night), [SettingsRepository] (no
 * tuning in demo mode) and [TimeProvider].
 */
@Singleton
class NightLogRepositoryImpl @Inject constructor(
    database: SleepSaverDatabase,
    private val sleepRepository: SleepRepository,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : NightLogRepository {

    private val config = SleepConfig.DEFAULT
    private val anchorDao = database.anchorDao()
    private val movementDao = database.movementLogDao()
    private val eventDao = database.alarmEventDao()
    private val tuningDao = database.alarmTuningDao()
    private val hushDao = database.hushPlayDao()
    private val landingCalculator = WakeLandingCalculator(config)
    private val tuner = ZTuner(config)

    /** One tuning step at a time, so two refreshes can't both apply the same step. */
    private val tuningLock = Mutex()

    // ---------------------------------------------------------------- Anchors

    /**
     * The latest anchor while its night lasts: its wake date is the one now belongs to, anchor + nightMaxLength hasn't
     * passed, and that wake date's planned alarm hasn't ended (stopped, timed out, missed or cancelled).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeAnchor: Flow<Anchor?> = combine(
        anchorDao.observeLatest().orFallback(TAG, "Latest anchor query") { null },
        timeProvider.minuteTicks.orFallback(TAG, "Minute ticks") { timeProvider.now() },
    ) { latest, now ->
        val offset = timeProvider.zone().rules.getOffset(now)
        latest?.toDomain()?.takeIf { anchor ->
            anchor.wakeDate == NightRules.wakeDateOf(now, offset, config) && now < anchor.at + config.anchor.nightMaxLength
        }
    }
        .distinctUntilChanged()
        .flatMapLatest { anchor ->
            if (anchor == null) {
                flowOf(null)
            } else {
                eventDao.observePlannedFor(anchor.wakeDate.toString())
                    .orFallback(TAG, "Alarm event query") { null }
                    .map { event -> anchor.takeIf { event?.outcome == null } }
            }
        }
        .distinctUntilChanged()

    override fun anchors(wakeDate: LocalDate): Flow<List<Anchor>> = anchorDao.observeFor(wakeDate.toString())
        .orFallback(TAG, "Anchors query") { emptyList() }
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    override val allAnchors: Flow<List<Anchor>> = anchorDao.observeAll()
        .orFallback(TAG, "Anchors query") { emptyList() }
        .map { rows -> rows.map { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun addAnchor(at: Instant, offset: ZoneOffset, source: AnchorSource, wholeNightLog: Boolean): Long {
        val anchor = Anchor(
            id = 0,
            at = at,
            offset = offset,
            wakeDate = NightRules.wakeDateOf(at, offset, config),
            source = source,
            wholeNightLog = wholeNightLog,
        )
        return anchorDao.insert(anchor.toEntity())
    }

    override suspend fun deleteAnchor(id: Long) = anchorDao.delete(id)

    // ---------------------------------------------------------------- Movement

    override suspend fun startMovementLog(
        start: Instant,
        offset: ZoneOffset,
        mode: String,
        wakeDate: LocalDate,
        anchorId: Long?,
        occurrenceId: String?,
    ): Long = movementDao.insert(
        MovementLogEntity(
            startEpochMs = start.toEpochMilli(),
            endEpochMs = null,
            offsetSec = offset.totalSeconds,
            mode = mode,
            wakeDate = wakeDate.toString(),
            anchorId = anchorId,
            occurrenceId = occurrenceId,
        ),
    )

    override suspend fun addEpochs(logId: Long, epochs: List<MovementEpoch>) {
        val rows = epochs.mapNotNull { it.toEntityOrNull(logId) }
        if (rows.isNotEmpty()) movementDao.insertEpochs(rows)
    }

    /**
     * A log with epochs ends no later than its last stored epoch's end: a log closed late (reconcile after the night
     * expired, a service that died hours ago) doesn't claim hours of sensing it never did. Never before its start.
     */
    override suspend fun endMovementLog(logId: Long, end: Instant) {
        var endMs = end.toEpochMilli()
        movementDao.lastEpochStart(logId)?.let { last -> endMs = minOf(endMs, last + config.surfacing.epoch.toMillis()) }
        movementDao.startOf(logId)?.let { start -> endMs = maxOf(endMs, start) }
        movementDao.setEnd(logId, endMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun movement(wakeDate: LocalDate): Flow<List<MovementTrace>> = movementDao.observeFor(wakeDate.toString())
        .orFallback(TAG, "Movement logs query") { emptyList() }
        .flatMapLatest { logs ->
            if (logs.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(logs.map { movementDao.observeEpochs(it.id) }) { epochs ->
                    logs.mapIndexed { i, log -> log.toDomain(epochs[i]) }
                }
            }
        }
        .distinctUntilChanged()

    // ---------------------------------------------------------------- Alarm events

    /** Room's upsert returns −1 for an update, so an update answers with the event's own id. */
    override suspend fun recordAlarmEvent(event: AlarmEvent): Long {
        val entity = event.toEntity()
        if (event.id == 0L) return eventDao.upsert(entity)
        eventDao.upsert(entity)
        return event.id
    }

    override fun alarmEvent(wakeDate: LocalDate): Flow<AlarmEvent?> = eventDao.observePlannedFor(wakeDate.toString())
        .orFallback(TAG, "Alarm event query") { null }
        .map { it?.toDomainOrNull() }
        .distinctUntilChanged()

    override val recentAlarmEvents: Flow<List<AlarmEvent>> = eventDao.observeRecent(config.alarm.recentEventsLimit)
        .orFallback(TAG, "Alarm events query") { emptyList() }
        .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
        .distinctUntilChanged()

    override val lastTestRing: Flow<AlarmEvent?> = eventDao.observeLastTest()
        .orFallback(TAG, "Test ring query") { null }
        .map { it?.toDomainOrNull() }
        .distinctUntilChanged()

    override suspend fun handledOccurrenceIds(since: LocalDate): Set<String> = eventDao.handledIds(since.toString()).toSet()

    // ---------------------------------------------------------------- Wake landing and tuning

    override fun landing(wakeDate: LocalDate): Flow<WakeLanding?> = combine(
        alarmEvent(wakeDate),
        sleepRepository.night(wakeDate).orFallback(TAG, "Night") { null },
    ) { event, night -> event?.let { guarded(TAG, "Wake landing") { landingCalculator.land(it, night) } } }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    override val tuning: Flow<AlarmTuning?> = tuningDao.observe()
        .orFallback(TAG, "Tuning query") { null }
        .map { it?.toDomainOrNull() }
        .distinctUntilChanged()

    /**
     * ZTuner over the landings of the planned alarms of the last tuneLookbackDays wake dates; stores the new row when a
     * step is due. A no-op in demo mode (demo nights say nothing about this phone's alarm). Never throws: a failure is
     * logged and the tuning stays as it is.
     */
    override suspend fun refreshTuning() {
        tuningLock.withLock {
            guarded(TAG, "Tuning refresh") {
                if (settingsRepository.current().demoMode) return@withLock
                val now = timeProvider.now()
                val today = timeProvider.today()
                val since = today.minusDays(config.surfacing.tuneLookbackDays - 1)
                val events = eventDao.observePlannedFrom(since.toString()).first()
                    .mapNotNull { it.toDomainOrNull() }
                    .filter { !it.wakeDate.isAfter(today) }
                val nights = sleepRepository.nights.first().associateBy { it.wakeDate }
                val landings = withContext(defaultDispatcher) {
                    events.mapNotNull { event -> landingCalculator.land(event, nights[event.wakeDate]) }
                }
                val current = tuningDao.get()?.toDomainOrNull()
                tuner.next(current, landings, now, timeProvider.currentOffset())?.let { tuningDao.upsert(it.toEntity()) }
            }
        }
    }

    /** Stores a RESET row (zT back to the default); only rings after it count for the next step. */
    override suspend fun resetTuning() = tuningDao.upsert(tuner.reset(timeProvider.now(), timeProvider.currentOffset()).toEntity())

    // ---------------------------------------------------------------- Hush

    override suspend fun startHushPlay(start: Instant, offset: ZoneOffset, color: HushColor): Long =
        hushDao.insert(color.toPlayEntity(start, offset, NightRules.wakeDateOf(start, offset, config)))

    override suspend fun endHushPlay(id: Long, end: Instant) = hushDao.setEnd(id, end.toEpochMilli())

    private companion object {
        const val TAG = "NightLogRepository"
    }
}

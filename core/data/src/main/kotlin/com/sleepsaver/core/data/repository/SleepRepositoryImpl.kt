package com.sleepsaver.core.data.repository

import androidx.room.withTransaction
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.night.NightAssembler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nights are assembled from every stored raw session plus the user's edits, for the active source:
 * demo mode shows only the demo origin, otherwise the selected origin (or all) among Health Connect rows.
 */
@Singleton
class SleepRepositoryImpl @Inject constructor(
    private val database: SleepSaverDatabase,
    settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    @ApplicationScope scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : SleepRepository {

    private val sessionDao = database.sleepSessionDao()
    private val overrideDao = database.nightOverrideDao()
    private val manualNapDao = database.manualNapDao()
    private val addedAwakeDao = database.addedAwakeDao()
    private val vitalDao = database.vitalDao()
    private val assembler = NightAssembler(SleepConfig.DEFAULT)

    private val activeSource: Flow<ActiveSource> = settingsRepository.settings
        .orFallback(TAG, "Settings") { AppSettings() }
        .map { ActiveSource.of(it) }
        .distinctUntilChanged()

    /**
     * Never fails and always emits: a failed query reads as empty (see [orFallback]), and a failed assembly keeps
     * the previous nights, or gives none at all when the very first one fails, so whatever waits for nights
     * (insights, export, the debt breakdown) gets an answer.
     */
    override val nights: Flow<List<Night>> = combine(
        sessionDao.observeAllWithStages().orFallback(TAG, "Sessions query") { emptyList() },
        overrideDao.observeAll().orFallback(TAG, "Overrides query") { emptyList() },
        manualNapDao.observeAll().orFallback(TAG, "Manual naps query") { emptyList() },
        addedAwakeDao.observeAll().orFallback(TAG, "Added awake query") { emptyList() },
        activeSource,
    ) { sessions, overrides, naps, awake, source ->
        guarded(TAG, "Night assembly") {
            assembler.assemble(
                sessions = sessions.filter(source::includes).map { it.toDomain() },
                source = source.selection,
                overrides = overrides.map { it.toDomain() },
                manualNaps = naps.map { it.toDomain() },
                addedAwake = awake.map { it.toDomain() },
            )
        }
    }
        .orLastGood(emptyList())
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()
        .orFallback(TAG, "Nights") { emptyList() }
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    override fun night(wakeDate: LocalDate): Flow<Night?> =
        nights.map { all -> all.firstOrNull { it.wakeDate == wakeDate } }.distinctUntilChanged()

    override fun rawNight(wakeDate: LocalDate): Flow<Night?> {
        // A session that can belong to wakeDate ends less than two days from it in UTC, whatever its offset.
        val windowStart = wakeDate.minusDays(RAW_WINDOW_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val windowEnd = wakeDate.plusDays(RAW_WINDOW_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return combine(
            sessionDao.observeAllWithStages().orFallback(TAG, "Sessions query") { emptyList() },
            activeSource,
        ) { sessions, source ->
            guarded(TAG, "Raw night assembly") {
                assembler.assemble(
                    sessions = sessions
                        .filter { source.includes(it) && it.session.endEpochMs in windowStart..windowEnd }
                        .map { it.toDomain() },
                    source = source.selection,
                )
            }
        }
            .orLastGood(emptyList())
            .map { all -> all.firstOrNull { it.wakeDate == wakeDate } }
            .flowOn(defaultDispatcher)
            .distinctUntilChanged()
    }

    override val origins: Flow<List<String>> = sessionDao.observeOrigins(SourceKind.HEALTH_CONNECT.name)
        .orFallback(TAG, "Origins query") { emptyList() }

    override fun vitals(from: Instant, to: Instant): Flow<List<VitalSample>> = combine(
        vitalDao.observeRange(from.toEpochMilli(), to.toEpochMilli()).orFallback(TAG, "Vitals query") { emptyList() },
        activeSource,
    ) { rows, source -> rows.filter(source::includes).mapNotNull { it.toDomainOrNull() } }
        .distinctUntilChanged()

    override suspend fun saveOverride(override: NightOverride) {
        overrideDao.upsert(override.copy(updatedAt = timeProvider.now()).toEntity())
    }

    override suspend fun revert(wakeDate: LocalDate) {
        database.withTransaction {
            overrideDao.delete(wakeDate.toString())
            addedAwakeDao.deleteForNight(wakeDate.toString())
        }
    }

    override suspend fun addManualNight(wakeDate: LocalDate, fellAsleep: Instant, wokeUp: Instant, offset: ZoneOffset) {
        require(wokeUp.isAfter(fellAsleep)) { "wokeUp must be after fellAsleep" }
        val override = NightOverride(
            wakeDate = wakeDate,
            fellAsleep = fellAsleep,
            wokeUp = wokeUp,
            isManualNight = true,
            offset = offset,
            updatedAt = timeProvider.now(),
        )
        overrideDao.upsert(override.toEntity())
    }

    override suspend fun addNap(start: Instant, end: Instant, offset: ZoneOffset): Long {
        require(end.isAfter(start)) { "end must be after start" }
        return manualNapDao.insert(
            ManualNapEntity(
                startEpochMs = start.toEpochMilli(),
                endEpochMs = end.toEpochMilli(),
                offsetSec = offset.totalSeconds,
                createdAtEpochMs = timeProvider.now().toEpochMilli(),
            ),
        )
    }

    override suspend fun deleteNap(id: Long) = manualNapDao.delete(id)

    override suspend fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant): Long {
        require(end.isAfter(start)) { "end must be after start" }
        return addedAwakeDao.insert(
            AddedAwakeEntity(
                wakeDate = wakeDate.toString(),
                startEpochMs = start.toEpochMilli(),
                endEpochMs = end.toEpochMilli(),
                createdAtEpochMs = timeProvider.now().toEpochMilli(),
            ),
        )
    }

    override suspend fun deleteAwake(id: Long) = addedAwakeDao.delete(id)

    /** Demo mode reads only demo rows; otherwise demo rows (if any are left) are never mixed in. */
    private data class ActiveSource(val demo: Boolean, val selection: SourceSelection) {
        fun includes(row: SessionWithStages): Boolean =
            if (demo) row.session.sourceKind == SourceKind.DEMO.name || row.session.originPackage == SleepConfig.DEMO_ORIGIN
            else row.session.sourceKind != SourceKind.DEMO.name

        fun includes(row: VitalEntity): Boolean =
            if (demo) row.sourceKind == SourceKind.DEMO.name || row.originPackage == SleepConfig.DEMO_ORIGIN
            else row.sourceKind != SourceKind.DEMO.name

        companion object {
            fun of(settings: AppSettings) = if (settings.demoMode) {
                ActiveSource(demo = true, selection = SourceSelection.Single(SleepConfig.DEMO_ORIGIN))
            } else {
                ActiveSource(demo = false, selection = settings.sleep.source)
            }
        }
    }

    private companion object {
        const val TAG = "SleepRepository"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        const val RAW_WINDOW_DAYS = 3L
    }
}

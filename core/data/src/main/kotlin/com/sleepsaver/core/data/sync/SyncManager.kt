package com.sleepsaver.core.data.sync

import com.sleepsaver.core.data.db.SleepSessionDao
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalDao
import com.sleepsaver.core.data.demo.DemoDataWriter
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.HealthConnectSource
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.data.healthconnect.catchingOrNull
import com.sleepsaver.core.data.healthconnect.isRateLimit
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.data.source.ChangeBatch
import com.sleepsaver.core.data.source.SleepDataSource
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.VitalSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps Room in step with Health Connect (docs/PLAN.md §3.10). Syncs never overlap and never throw:
 * every failure comes back as a [SyncResult] error and is remembered in [state].
 *
 * Incremental syncs use the Changes API. Without a usable token (first run, expired, or the granted record
 * types changed since it was issued) the history is re-imported: token first, then 365 days (30 without the
 * history permission) in 30-day chunks, deleting stored sessions in that window that Health Connect no
 * longer has.
 */
@Singleton
class SyncManager @Inject constructor(
    private val gateway: HealthConnectGateway,
    @HealthConnectSource private val source: SleepDataSource,
    private val settingsRepository: SettingsRepository,
    // Providers: these are implemented by modules that may depend on sync themselves.
    private val insightsRepository: Provider<InsightsRepository>,
    private val notifier: Provider<MorningReviewNotifier>,
    private val sessionDao: SleepSessionDao,
    private val vitalDao: VitalDao,
    private val store: SyncStateStore,
    private val demoData: DemoDataWriter,
    private val timeProvider: TimeProvider,
    private val scheduler: SyncWorkScheduler,
    private val appVisibility: AppVisibility,
    @ApplicationScope appScope: CoroutineScope,
) : SyncRepository {

    private val config = SleepConfig.DEFAULT
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Set once a sync has finished, so the saved state loaded at start can't overwrite a fresher result. */
    private var finishedOnce = false

    init {
        appScope.launch {
            val saved = catchingOrNull { store.current() } ?: return@launch
            mutex.withLock {
                if (!finishedOnce) _state.update { it.copy(lastSuccessAt = saved.lastSuccessAt, lastError = saved.lastError) }
            }
        }
    }

    override suspend fun sync(trigger: SyncTrigger): SyncResult = mutex.withLock { runGuarded(trigger, forceImport = false) }

    override suspend fun importHistory(): SyncResult = mutex.withLock { runGuarded(trigger = null, forceImport = true) }

    override fun ensurePeriodicSync() = scheduler.ensurePeriodic()

    override fun startWaitingRetries() = scheduler.startWaitingRetries()

    override suspend fun resetSyncState() = mutex.withLock {
        store.clear()
        _state.value = SyncState()
    }

    /** True while last night hasn't arrived and it's still before SleepConfig.waitingGiveUpAt (never in demo mode). */
    suspend fun isWaitingForLastNight(): Boolean {
        val settings = catchingOrNull { settingsRepository.current() } ?: return false
        if (settings.demoMode) return false
        val zone = timeProvider.zone()
        val now = timeProvider.now()
        if (!now.atZone(zone).toLocalTime().isBefore(config.waitingGiveUpAt)) return false
        return catchingOrNull { !hasLastNight(now.atZone(zone).toLocalDate(), settings) } ?: false
    }

    // ------------------------------------------------------------ run

    /** [trigger] is null for an explicit [importHistory]. Caller holds [mutex]. */
    private suspend fun runGuarded(trigger: SyncTrigger?, forceImport: Boolean): SyncResult {
        _state.update { it.copy(status = SyncStatus.Syncing) }
        try {
            val result = try {
                runSync(trigger, forceImport)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult(success = false, error = e.toSyncError())
            }
            val error = result.error
            // Not being allowed to read in the background is expected, not a failure worth showing.
            if (error != null && error != SyncError.BACKGROUND_NOT_ALLOWED) {
                catchingOrNull { store.recordError(error) }
                finishedOnce = true
                _state.update { it.copy(lastError = error) }
            }
            return result
        } finally {
            _state.update { it.copy(status = SyncStatus.Idle) }
        }
    }

    private suspend fun runSync(trigger: SyncTrigger?, forceImport: Boolean): SyncResult {
        val settings = settingsRepository.current()
        if (settings.demoMode) {
            // Health Connect is left alone; the demo nights just keep rolling forward.
            demoData.extendToNow()
            return SyncResult(success = true)
        }
        when (gateway.status()) {
            HealthConnectStatus.AVAILABLE -> Unit
            HealthConnectStatus.UPDATE_REQUIRED -> return failure(SyncError.HEALTH_CONNECT_UPDATE_REQUIRED)
            HealthConnectStatus.NOT_INSTALLED, HealthConnectStatus.NOT_SUPPORTED ->
                return failure(SyncError.HEALTH_CONNECT_NOT_INSTALLED)
        }
        val permissions = gateway.permissionState()
        if (!permissions.sleepGranted) return failure(SyncError.PERMISSION_DENIED)
        if (trigger.isBackground() && !permissions.backgroundGranted && !appVisibility.isInForeground()) {
            return failure(SyncError.BACKGROUND_NOT_ALLOWED)
        }

        val zone = timeProvider.zone()
        val today = timeProvider.now().atZone(zone).toLocalDate()
        val hadLastNight = hasLastNight(today, settings)
        val scope = HealthConnectPermissions.changeFeedScope(permissions.granted)
        val saved = store.current()
        val token = saved.changesToken?.takeIf { saved.tokenSourceId == source.id && saved.tokenScope == scope }

        val changed = if (forceImport || token == null) {
            importLocked(permissions, scope)
        } else {
            when (val batch = source.changesSince(token)) {
                ChangeBatch.TokenExpired -> importLocked(permissions, scope)
                is ChangeBatch.Changes -> applyChanges(batch, scope)
            }
        }

        val now = timeProvider.now()
        store.recordSuccess(now)
        finishedOnce = true
        _state.update { it.copy(lastSuccessAt = now, lastError = null) }

        val hasLastNightNow = hasLastNight(today, settings)
        val arrived = !hadLastNight && hasLastNightNow
        if (trigger != null) afterSync(trigger, settings, permissions, today, now, arrived, hasLastNightNow)
        return SyncResult(success = true, sessionsChanged = changed, lastNightArrived = arrived)
    }

    /** Notifications and the retry chain. Hook failures never fail a sync that already succeeded. */
    private fun afterSync(
        trigger: SyncTrigger,
        settings: AppSettings,
        permissions: HealthPermissionState,
        today: LocalDate,
        now: Instant,
        arrived: Boolean,
        hasLastNight: Boolean,
    ) {
        val wakes = typicalWakes(today, settings)
        if (arrived) catchingOrNull { notifier.get().onLastNightArrived(today) }
        if (trigger.isForeground() && !permissions.backgroundGranted) {
            catchingOrNull { notifier.get().scheduleFallbackReminder(fallbackReminderAt(wakes, now, hasLastNight)) }
        }
        // A RETRY run must not replace its own chain; it asks WorkManager for a retry instead (SyncWorker).
        if (!hasLastNight && trigger != SyncTrigger.RETRY && isWaitingWindow(wakes, now)) {
            catchingOrNull { scheduler.startWaitingRetries() }
        }
    }

    // ------------------------------------------------------------ import

    private suspend fun importLocked(permissions: HealthPermissionState, scope: Set<String>): Int {
        _state.update { it.copy(status = SyncStatus.Importing(0f)) }
        // Token first: anything written while we read the history then shows up as a change next time.
        val token = source.newChangesToken()
        val now = timeProvider.now()
        val days = if (permissions.historyGranted) HISTORY_DAYS_WITH_PERMISSION else HISTORY_DAYS
        val windowStart = now.minus(Duration.ofDays(days))
        val chunks = generateSequence(windowStart) { it.plus(IMPORT_CHUNK) }.takeWhile { it.isBefore(now) }.toList()

        val seen = HashSet<String>()
        chunks.forEachIndexed { index, chunkStart ->
            val chunkEnd = minOf(chunkStart.plus(IMPORT_CHUNK), now)
            val sessions = source.readSleepSessions(chunkStart, chunkEnd)
            writeSessions(sessions)
            seen += sessions.map { it.id }
            writeVitals(source.readVitals(chunkStart, chunkEnd, sessions))
            _state.update { it.copy(status = SyncStatus.Importing((index + 1f) / chunks.size)) }
        }

        // Sessions deleted from Health Connect while we weren't following its changes.
        val stale = sessionDao.getWithStages(windowStart.toEpochMilli(), now.toEpochMilli())
            .map { it.session }
            .filter { it.sourceKind == SourceKind.HEALTH_CONNECT.name && it.startEpochMs >= windowStart.toEpochMilli() }
            .map { it.id }
            .filterNot { it in seen }
        deleteSessions(stale)

        if (token != null) store.saveToken(token, source.id, scope) else store.clearToken()
        store.setHistoryImportDone()
        return seen.size + stale.size
    }

    // ------------------------------------------------------------ incremental

    /** Returns sessions upserted + ids deleted (a deleted id may have been a vital: the feed doesn't say). */
    private suspend fun applyChanges(batch: ChangeBatch.Changes, scope: Set<String>): Int {
        val zone = timeProvider.zone()
        writeSessions(batch.upserted)
        deleteSessions(batch.deletedIds)
        writeVitals(batch.vitals)
        batch.deletedVitalIds.chunked(SQL_CHUNK).forEach { vitalDao.deleteByIds(it) }

        // Steps and sleeping heart rate aren't in the change feed: refresh them for the days that changed.
        val touched = batch.upserted.flatMap { listOf(it.start, it.end) } + batch.vitals.map { it.time }
        if (touched.isNotEmpty()) {
            val from = touched.min().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val to = touched.max().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            writeVitals(source.readVitals(from, to, batch.upserted))
        }
        store.saveToken(batch.nextToken, source.id, scope)
        return batch.upserted.size + batch.deletedIds.size
    }

    // ------------------------------------------------------------ Room

    private suspend fun writeSessions(sessions: List<RawSleepSession>) {
        if (sessions.isEmpty()) return
        val zone = timeProvider.zone()
        sessions.distinctBy { it.id }.chunked(SQL_CHUNK).forEach { chunk ->
            sessionDao.replace(chunk.map { it.toSessionWithStages(SourceKind.HEALTH_CONNECT, zone) })
        }
    }

    private suspend fun deleteSessions(ids: List<String>) {
        ids.chunked(SQL_CHUNK).forEach { chunk ->
            sessionDao.deleteByIds(chunk)
            vitalDao.deleteByIds(chunk.map(VitalIds::sleepingHeartRate))
        }
    }

    private suspend fun writeVitals(vitals: List<VitalSample>) {
        if (vitals.isEmpty()) return
        val zone = timeProvider.zone()
        vitalDao.upsert(vitals.distinctBy { it.id }.map { it.toVitalEntity(SourceKind.HEALTH_CONNECT, zone) })
    }

    // ------------------------------------------------------------ last night

    /** Whether a night session of the active source ends on [today] before the night cutoff (14:00). */
    private suspend fun hasLastNight(today: LocalDate, settings: AppSettings): Boolean {
        val zone = timeProvider.zone()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return sessionDao.getWithStages(from, to).any { isLastNight(it.session, today, settings.sleep.source) }
    }

    private fun isLastNight(session: SleepSessionEntity, today: LocalDate, source: SourceSelection): Boolean {
        if (session.sourceKind != SourceKind.HEALTH_CONNECT.name) return false
        if (source is SourceSelection.Single && session.originPackage != source.packageName) return false
        val start = Instant.ofEpochMilli(session.startEpochMs)
        val end = Instant.ofEpochMilli(session.endEpochMs)
        val duration = Duration.between(start, end)
        if (duration < config.minSessionDuration) return false
        val ingestZone = catchingOrNull { ZoneId.of(session.ingestZoneId) } ?: timeProvider.zone()
        val localStart = LocalDateTime.ofInstant(start, session.startOffsetSec.toOffset(ingestZone, start))
        val localEnd = LocalDateTime.ofInstant(end, session.endOffsetSec.toOffset(ingestZone, end))
        val startTime = localStart.toLocalTime()
        val isNap = duration < config.napMaxDuration &&
            !startTime.isBefore(config.napWindowStart) && startTime.isBefore(config.napWindowEnd)
        return !isNap && localEnd.toLocalDate() == today && localEnd.toLocalTime().isBefore(config.nightEndCutoff)
    }

    private fun Int?.toOffset(zone: ZoneId, at: Instant): ZoneOffset =
        this?.let(ZoneOffset::ofTotalSeconds) ?: zone.rules.getOffset(at)

    // ------------------------------------------------------------ schedule-based times

    private class TypicalWakes(val today: Instant, val tomorrow: Instant)

    /** W today and tomorrow: from the current energy schedule when it's ready, else the onboarding wake time. */
    private fun typicalWakes(today: LocalDate, settings: AppSettings): TypicalWakes {
        val zone = timeProvider.zone()
        val schedule = (catchingOrNull { insightsRepository.get().state.value } as? InsightsState.Ready)?.insights?.schedule
        if (schedule != null && schedule.date == today) {
            val tomorrowZone = schedule.tomorrowWakeZone
            val tomorrow = tomorrowZone.start.plus(Duration.between(tomorrowZone.start, tomorrowZone.end).dividedBy(2))
            return TypicalWakes(schedule.typicalWake.toInstant(), tomorrow)
        }
        val wakeTime = schedule?.typicalWake?.toLocalTime() ?: settings.sleep.typicalWake
        return TypicalWakes(
            today = today.atTime(wakeTime).atZone(zone).toInstant(),
            tomorrow = today.plusDays(1).atTime(wakeTime).atZone(zone).toInstant(),
        )
    }

    /**
     * The next W + 45 min: today's while it's still ahead and last night hasn't arrived (so opening the app early
     * doesn't skip this morning's reminder), otherwise tomorrow's.
     */
    private fun fallbackReminderAt(wakes: TypicalWakes, now: Instant, hasLastNight: Boolean): Instant {
        val todayReminder = wakes.today.plus(FALLBACK_REMINDER_AFTER_WAKE)
        return if (!hasLastNight && todayReminder.isAfter(now)) todayReminder else wakes.tomorrow.plus(FALLBACK_REMINDER_AFTER_WAKE)
    }

    /** From SleepConfig.waitingStartsBeforeWake before W until SleepConfig.waitingGiveUpAt (local). */
    private fun isWaitingWindow(wakes: TypicalWakes, now: Instant): Boolean {
        val localNow = now.atZone(timeProvider.zone()).toLocalTime()
        return localNow.isBefore(config.waitingGiveUpAt) && !now.isBefore(wakes.today.minus(config.waitingStartsBeforeWake))
    }

    private fun failure(error: SyncError) = SyncResult(success = false, error = error)

    private fun SyncTrigger?.isBackground() = this == SyncTrigger.PERIODIC || this == SyncTrigger.RETRY

    private fun SyncTrigger.isForeground() =
        this == SyncTrigger.APP_OPEN || this == SyncTrigger.MANUAL || this == SyncTrigger.ONBOARDING

    private fun Exception.toSyncError(): SyncError = when {
        this is SecurityException -> SyncError.PERMISSION_DENIED
        isRateLimit() -> SyncError.RATE_LIMITED
        else -> SyncError.UNKNOWN
    }

    companion object {
        const val HISTORY_DAYS_WITH_PERMISSION = 365L
        const val HISTORY_DAYS = 30L
        val IMPORT_CHUNK: Duration = Duration.ofDays(30)

        /** Without background reads the morning review is a time-based reminder at W + 45 min (PLAN §3.10). */
        val FALLBACK_REMINDER_AFTER_WAKE: Duration = Duration.ofMinutes(45)

        /** Keeps `IN (:ids)` queries well under SQLite's variable limit. */
        private const val SQL_CHUNK = 200
    }
}

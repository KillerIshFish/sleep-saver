package com.sleepsaver.core.data.sync

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.source.ChangeBatch
import com.sleepsaver.core.data.source.RecordedVital
import com.sleepsaver.core.data.source.SleepDataSource
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// Small hand-written fakes for the sync tests. The real repositories live in other packages and are
// developed separately, so nothing here depends on them.

class FakeTimeProvider(var now: Instant, var zone: ZoneId) : TimeProvider {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> get() = flowOf(now)

    fun setLocal(dateTime: LocalDateTime) {
        now = dateTime.atZone(zone).toInstant()
    }
}

/** An in-memory Health Connect: [records] is what HC holds; changes accumulate until the next changesSince. */
class FakeHealthConnectSource : SleepDataSource {
    override val id: String = "health_connect"

    val records = LinkedHashMap<String, RawSleepSession>()
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    var expireNextToken = false

    /** Thrown by the next changesSince only. */
    var failNextChanges: Exception? = null

    /** When set, reads suspend until it completes (to observe overlapping syncs). */
    var readGate: CompletableDeferred<Unit>? = null
    private var tokens = 0
    private val pendingUpserts = LinkedHashMap<String, RawSleepSession>()
    private val pendingDeletes = LinkedHashSet<String>()
    private val pendingVitals = mutableListOf<RecordedVital>()

    /** Puts [session] into "Health Connect" without a change entry (history that existed before the token). */
    fun seed(vararg sessions: RawSleepSession) = sessions.forEach { records[it.id] = it }

    fun upsert(session: RawSleepSession) {
        records[session.id] = session
        pendingUpserts[session.id] = session
        pendingDeletes -= session.id
    }

    fun delete(id: String) {
        records -= id
        pendingUpserts -= id
        pendingDeletes += id
    }

    fun addVital(vital: VitalSample, offset: ZoneOffset) {
        pendingVitals += RecordedVital(vital, offset)
    }

    override suspend fun isReadable(): Boolean = true

    override suspend fun readSleepSessions(from: Instant, to: Instant): List<RawSleepSession> {
        calls += "read"
        readGate?.await()
        failWith?.let { throw it }
        return records.values.filter { it.end.isAfter(from) && it.start.isBefore(to) }
    }

    /** Sleeping heart rate for each session, stamped with the session's end offset. */
    override suspend fun readVitals(from: Instant, to: Instant, sessions: List<RawSleepSession>): List<RecordedVital> {
        calls += "vitals"
        return sessions.map {
            RecordedVital(VitalSample(VitalIds.sleepingHeartRate(it.id), VitalType.SLEEP_HR_AVG, it.end, 52.0, it.origin), it.endOffset)
        }
    }

    override suspend fun newChangesToken(): String {
        calls += "token"
        failWith?.let { throw it }
        pendingUpserts.clear()
        pendingDeletes.clear()
        pendingVitals.clear()
        return "token-${++tokens}"
    }

    override suspend fun changesSince(token: String): ChangeBatch {
        calls += "changes:$token"
        failWith?.let { throw it }
        failNextChanges?.let { failNextChanges = null; throw it }
        if (expireNextToken) {
            expireNextToken = false
            return ChangeBatch.TokenExpired
        }
        val batch = ChangeBatch.Changes(
            upserted = pendingUpserts.values.toList(),
            deletedIds = pendingDeletes.toList(),
            vitals = pendingVitals.toList(),
            deletedVitalIds = pendingDeletes.toList(),
            nextToken = "token-${++tokens}",
        )
        pendingUpserts.clear()
        pendingDeletes.clear()
        pendingVitals.clear()
        return batch
    }
}

class FakeGateway : HealthConnectGateway {
    var status = HealthConnectStatus.AVAILABLE
    var granted: Set<String> = setOf(HealthConnectPermissions.SLEEP)
    var statusCalls = 0

    override fun status(): HealthConnectStatus = status.also { statusCalls++ }
    override suspend fun permissionsToRequest(): Set<String> = granted
    override suspend fun permissionState() = HealthPermissionState(
        granted = granted,
        sleepGranted = HealthConnectPermissions.SLEEP in granted,
        optionalGranted = HealthConnectPermissions.OPTIONAL intersect granted,
        historyGranted = HealthConnectPermissions.HISTORY in granted,
        backgroundGranted = HealthConnectPermissions.BACKGROUND in granted,
        historyFeatureAvailable = true,
        backgroundFeatureAvailable = true,
    )
    override fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> = error("not used")
    override fun installOrUpdateIntent(): Intent = Intent()
    override fun manageDataIntent(): Intent = Intent()
    override fun isGarminConnectInstalled(): Boolean = true
    override fun garminConnectLaunchIntent(): Intent? = null
    override fun garminConnectStoreIntent(): Intent = Intent()
    override suspend fun sleepOrigins(lookbackDays: Long): List<String> = emptyList()
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val flow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = flow
    override suspend fun current(): AppSettings = flow.value
    override suspend fun setSleepNeed(need: Duration) = flow.update { it.copy(sleep = it.sleep.copy(sleepNeed = need)) }
    override suspend fun setTypicalWake(time: LocalTime) = flow.update { it.copy(sleep = it.sleep.copy(typicalWake = time)) }
    override suspend fun setStreakThreshold(threshold: Duration) =
        flow.update { it.copy(sleep = it.sleep.copy(streakThreshold = threshold)) }
    override suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration) =
        flow.update { it.copy(sleep = it.sleep.copy(lowDebtMax = lowMax, highDebtMin = highMin)) }
    override suspend fun setSource(source: SourceSelection) = flow.update { it.copy(sleep = it.sleep.copy(source = source)) }
    override suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?) =
        flow.update { it.copy(demoMode = enabled, demoStartedAt = startedAt) }
    override suspend fun setClockFormat(format: ClockFormat) = flow.update { it.copy(clockFormat = format) }
    override suspend fun setMorningReviewEnabled(enabled: Boolean) = flow.update { it.copy(morningReviewEnabled = enabled) }
    override suspend fun setOnboardingDone(done: Boolean) = flow.update { it.copy(onboardingDone = done) }
    override suspend fun dismissNeedSuggestion(value: Duration, at: Instant) =
        flow.update { it.copy(needSuggestionDismissedValue = value, needSuggestionDismissedAt = at) }
}

class FakeInsightsRepository : InsightsRepository {
    override val state = MutableStateFlow<InsightsState>(InsightsState.Loading)
    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> = emptyFlow()
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> = flowOf(null)
    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown = error("not used")
}

class RecordingNotifier : MorningReviewNotifier {
    val arrived = mutableListOf<LocalDate>()
    val fallbackReminders = mutableListOf<Instant>()
    override fun onLastNightArrived(wakeDate: LocalDate) {
        arrived += wakeDate
    }
    override fun scheduleFallbackReminder(at: Instant) {
        fallbackReminders += at
    }
    override fun cancel() = Unit
}

class RecordingScheduler : SyncWorkScheduler {
    var periodic = 0
    var retries = 0
    override fun ensurePeriodic() {
        periodic++
    }
    override fun startWaitingRetries() {
        retries++
    }
}

/** Builds sessions from local wall-clock times in [zone]. */
fun hcSession(
    id: String,
    zone: ZoneId,
    start: LocalDateTime,
    end: LocalDateTime,
    origin: String = SleepConfig.GARMIN_CONNECT_PACKAGE,
    withStages: Boolean = true,
): RawSleepSession {
    val s = start.atZone(zone).toInstant()
    val e = end.atZone(zone).toInstant()
    val mid = s.plus(Duration.between(s, e).dividedBy(2))
    return RawSleepSession(
        id = id,
        origin = origin,
        start = s,
        end = e,
        startOffset = zone.rules.getOffset(s),
        endOffset = zone.rules.getOffset(e),
        stages = if (withStages) {
            listOf(SleepStage(s, mid, SleepStageType.DEEP), SleepStage(mid, e, SleepStageType.REM))
        } else {
            emptyList()
        },
        lastModified = e,
    )
}

package com.sleepsaver.core.data.source

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.sleepsaver.core.data.healthconnect.HealthConnectClientProvider
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import com.sleepsaver.core.data.healthconnect.catchingOrNull
import com.sleepsaver.core.data.healthconnect.isRateLimit
import com.sleepsaver.core.data.healthconnect.readAllRecords
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.VitalSample
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads sleep (all origins: filtering happens in the domain) and optional vitals from Health Connect.
 * Sleep reads and the Changes API throw on failure so the sync layer can classify the error; optional
 * vitals are skipped when their permission is missing or their read fails.
 */
@Singleton
class HealthConnectSleepDataSource @Inject constructor(
    private val clientProvider: HealthConnectClientProvider,
    private val timeProvider: TimeProvider,
) : SleepDataSource {

    override val id: String = ID

    override suspend fun isReadable(): Boolean {
        val client = clientProvider.get() ?: return false
        return catchingOrNull { HealthConnectPermissions.SLEEP in client.permissionController.getGrantedPermissions() } == true
    }

    override suspend fun readSleepSessions(from: Instant, to: Instant): List<RawSleepSession> {
        val zone = timeProvider.zone()
        return requireClient()
            .readAllRecords(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
            .map { it.toRawSleepSession(zone) }
    }

    override suspend fun readVitals(from: Instant, to: Instant, sessions: List<RawSleepSession>): List<VitalSample> {
        val client = clientProvider.get() ?: return emptyList()
        val granted = catchingOrNull { client.permissionController.getGrantedPermissions() } ?: return emptyList()
        val zone = timeProvider.zone()
        val range = TimeRangeFilter.between(from, to)
        val vitals = mutableListOf<VitalSample>()

        if (HealthConnectPermissions.RESTING_HEART_RATE in granted) {
            catchingOrNull { client.readAllRecords(RestingHeartRateRecord::class, range) }
                ?.mapTo(vitals) { it.toVitalSample() }
        }
        if (HealthConnectPermissions.HRV in granted) {
            catchingOrNull { client.readAllRecords(HeartRateVariabilityRmssdRecord::class, range) }
                ?.mapTo(vitals) { it.toVitalSample() }
        }
        if (HealthConnectPermissions.STEPS in granted) {
            catchingOrNull { readDailySteps(client, from, to, zone) }?.let(vitals::addAll)
        }
        if (HealthConnectPermissions.HEART_RATE in granted) {
            for (session in sessions) {
                val result = try {
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(HeartRateRecord.BPM_AVG),
                            timeRangeFilter = TimeRangeFilter.between(session.start, session.end),
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Out of quota: the remaining sessions would fail too.
                    if (e.isRateLimit()) break else continue
                }
                result[HeartRateRecord.BPM_AVG]?.let { vitals += sleepingHeartRateSample(session, it) }
            }
        }
        return vitals
    }

    /** One sample per local day overlapping [from, to), sliced by Health Connect in local time. */
    private suspend fun readDailySteps(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
        zone: ZoneId,
    ): List<VitalSample> {
        val firstDay = from.atZone(zone).toLocalDate()
        val lastDay = to.atZone(zone).toLocalDate()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(firstDay.atStartOfDay(), lastDay.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return groups.mapNotNull { group ->
            val steps = group.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            dailyStepsSample(group.startTime.toLocalDate(), zone, steps, group.result.dataOrigins.map { it.packageName })
        }
    }

    /** A token over the granted change-feed types (sleep, resting HR, HRV); null when none is granted. */
    override suspend fun newChangesToken(): String? {
        val client = requireClient()
        val granted = client.permissionController.getGrantedPermissions()
        val types = HealthConnectPermissions.CHANGE_FEED.filterKeys { it in granted }.values.toSet()
        if (types.isEmpty()) return null
        return client.getChangesToken(ChangesTokenRequest(recordTypes = types))
    }

    override suspend fun changesSince(token: String): ChangeBatch {
        val client = requireClient()
        val collector = ChangeCollector(timeProvider.zone())
        var next = token
        do {
            val response = client.getChanges(next)
            if (response.changesTokenExpired) return ChangeBatch.TokenExpired
            collector.add(response.changes)
            next = response.nextChangesToken
        } while (response.hasMore)
        return collector.build(next)
    }

    private fun requireClient(): HealthConnectClient =
        clientProvider.get() ?: throw IllegalStateException("Health Connect is not available")

    companion object {
        const val ID = "health_connect"
    }
}

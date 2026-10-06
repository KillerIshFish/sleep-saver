package com.sleepsaver.core.data.demo

import com.sleepsaver.core.data.db.SleepSessionDao
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalDao
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.sync.toSessionWithStages
import com.sleepsaver.core.data.sync.toVitalEntity
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Writes [FakeSleepDataSource] output into Room as [SourceKind.DEMO] rows, and removes it again. */
@Singleton
class DemoDataWriter @Inject constructor(
    private val demoSource: FakeSleepDataSource,
    private val sessionDao: SleepSessionDao,
    private val vitalDao: VitalDao,
    private val timeProvider: TimeProvider,
) {
    /** Writes the full dataset (idempotent: ids are per date). Returns the number of sessions written. */
    suspend fun writeAll(): Int {
        val data = demoSource.generate()
        val zone = timeProvider.zone()
        sessionDao.replace(data.sessions.map { it.toSessionWithStages(SourceKind.DEMO, zone) })
        vitalDao.upsert(data.vitals.map { it.toVitalEntity(SourceKind.DEMO, zone) })
        return data.sessions.size
    }

    /**
     * Adds the demo sleep that ended since the newest stored demo session, so demo mode keeps rolling forward
     * day after day. Returns the number of new sessions.
     */
    suspend fun extendToNow(): Int {
        val latestEnd = sessionDao.latestEnd(SourceKind.DEMO.name) ?: return writeAll()
        val data = demoSource.generate()
        val zone = timeProvider.zone()
        val newSessions = data.sessions.filter { it.end.toEpochMilli() > latestEnd }
        if (newSessions.isEmpty()) return 0
        val vitalsSince = Instant.ofEpochMilli(latestEnd).minus(VITALS_OVERLAP)
        sessionDao.replace(newSessions.map { it.toSessionWithStages(SourceKind.DEMO, zone) })
        vitalDao.upsert(data.vitals.filter { !it.time.isBefore(vitalsSince) }.map { it.toVitalEntity(SourceKind.DEMO, zone) })
        return newSessions.size
    }

    suspend fun clear() {
        sessionDao.deleteBySourceKind(SourceKind.DEMO.name)
        vitalDao.deleteBySourceKind(SourceKind.DEMO.name)
    }

    private companion object {
        /** Daily steps are stamped at the start of their day, before the night that follows them. */
        val VITALS_OVERLAP: Duration = Duration.ofDays(2)
    }
}

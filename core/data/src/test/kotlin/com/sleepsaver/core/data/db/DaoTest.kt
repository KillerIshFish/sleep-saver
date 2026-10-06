package com.sleepsaver.core.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = db.close()

    private fun session(id: String, start: Long, end: Long, origin: String = GARMIN, kind: SourceKind = SourceKind.HEALTH_CONNECT) =
        SleepSessionEntity(id, origin, start, end, null, null, "UTC", null, end, kind.name)

    private fun stage(sessionId: String, start: Long, end: Long, type: Int = 4) = SleepStageEntity(sessionId, start, end, type)

    @Test
    fun `replace upserts sessions and swaps their stages`() = runTest {
        val dao = db.sleepSessionDao()
        dao.replace(listOf(SessionWithStages(session("a", 0, 100), listOf(stage("a", 0, 50), stage("a", 50, 100)))))

        dao.replace(listOf(SessionWithStages(session("a", 0, 120), listOf(stage("a", 0, 120, 5)))))

        val stored = dao.observeAllWithStages().first()
        assertThat(stored).hasSize(1)
        assertThat(stored.single().session.endEpochMs).isEqualTo(120)
        assertThat(stored.single().stages).containsExactly(stage("a", 0, 120, 5))
    }

    @Test
    fun `range query returns overlapping sessions with stages`() = runTest {
        val dao = db.sleepSessionDao()
        dao.replace(
            listOf(
                SessionWithStages(session("early", 0, 100), emptyList()),
                SessionWithStages(session("mid", 200, 300), listOf(stage("mid", 200, 300))),
                SessionWithStages(session("late", 400, 500), emptyList()),
            ),
        )

        val hits = dao.getWithStages(fromMs = 250, toMs = 450)

        assertThat(hits.map { it.session.id }).containsExactly("mid", "late").inOrder()
        assertThat(hits.first().stages).hasSize(1)
    }

    @Test
    fun `deleting by source kind cascades to stages and origins exclude demo rows`() = runTest {
        val dao = db.sleepSessionDao()
        dao.replace(
            listOf(
                SessionWithStages(session("hc1", 0, 100, origin = "z.app"), emptyList()),
                SessionWithStages(session("hc2", 200, 300), emptyList()),
                SessionWithStages(session("hc3", 400, 500), emptyList()),
                SessionWithStages(session("demo:1", 600, 700, origin = "com.sleepsaver.demo", kind = SourceKind.DEMO), listOf(stage("demo:1", 600, 700))),
            ),
        )

        assertThat(dao.observeOrigins(SourceKind.HEALTH_CONNECT.name).first()).containsExactly(GARMIN, "z.app").inOrder()
        assertThat(dao.count(SourceKind.HEALTH_CONNECT.name)).isEqualTo(3)
        assertThat(dao.latestEnd(SourceKind.HEALTH_CONNECT.name)).isEqualTo(500)

        dao.deleteBySourceKind(SourceKind.DEMO.name)

        assertThat(dao.observeAllWithStages().first().map { it.session.id }).containsExactly("hc1", "hc2", "hc3")
        assertThat(db.query("SELECT COUNT(*) FROM sleep_stages", null).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(0)
    }

    @Test
    fun `overrides upsert, read and delete by date`() = runTest {
        val dao = db.nightOverrideDao()
        dao.upsert(NightOverrideEntity("2026-10-06", 1, 2, null, null, false, 0, updatedAtEpochMs = 10))
        dao.upsert(NightOverrideEntity("2026-10-06", 1, 3, 15, null, false, 0, updatedAtEpochMs = 20))
        dao.upsert(NightOverrideEntity("2026-10-05", null, null, null, 30, false, 0, updatedAtEpochMs = 5))

        assertThat(dao.get("2026-10-06")?.wokeUpEpochMs).isEqualTo(3)
        assertThat(dao.observeAll().first().map { it.wakeDate }).containsExactly("2026-10-05", "2026-10-06").inOrder()

        dao.deleteUpdatedSince(20)
        assertThat(dao.getAll().map { it.wakeDate }).containsExactly("2026-10-05")
        dao.delete("2026-10-05")
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun `added awake is deleted per night`() = runTest {
        val dao = db.addedAwakeDao()
        dao.insert(AddedAwakeEntity(wakeDate = "2026-10-06", startEpochMs = 1, endEpochMs = 2, createdAtEpochMs = 0))
        dao.insert(AddedAwakeEntity(wakeDate = "2026-10-06", startEpochMs = 3, endEpochMs = 4, createdAtEpochMs = 0))
        val keep = dao.insert(AddedAwakeEntity(wakeDate = "2026-10-07", startEpochMs = 5, endEpochMs = 6, createdAtEpochMs = 0))

        dao.deleteForNight("2026-10-06")

        assertThat(dao.getAll().map { it.id }).containsExactly(keep)
    }

    @Test
    fun `vitals range is inclusive`() = runTest {
        val dao = db.vitalDao()
        dao.upsert(
            listOf(
                VitalEntity("v1", "RESTING_HR", 100, null, 50.0, GARMIN, "HEALTH_CONNECT"),
                VitalEntity("v2", "RESTING_HR", 200, null, 51.0, GARMIN, "HEALTH_CONNECT"),
                VitalEntity("v3", "RESTING_HR", 300, null, 52.0, GARMIN, "HEALTH_CONNECT"),
            ),
        )

        assertThat(dao.observeRange(100, 200).first().map { it.id }).containsExactly("v1", "v2").inOrder()
    }

    @Test
    fun `reviews list in date order`() = runTest {
        val dao = db.morningReviewDao()
        dao.upsert(MorningReviewEntity("2026-10-06", "CONFIRMED", 2, 100, 90))
        dao.upsert(MorningReviewEntity("2026-10-04", "DISMISSED", 1, null, null))

        assertThat(dao.getAll().map { it.wakeDate }).containsExactly("2026-10-04", "2026-10-06").inOrder()
        assertThat(dao.observe("2026-10-06").first()?.debtAfterMin).isEqualTo(90)
        assertThat(dao.get("2026-10-05")).isNull()
    }

    @Test
    fun `routine activities order by sortOrder then id`() = runTest {
        val dao = db.routineActivityDao()
        dao.insertAll(
            listOf(
                RoutineActivityEntity(label = "B", emoji = "b", isCustom = false, selected = false, sortOrder = 1),
                RoutineActivityEntity(label = "A", emoji = "a", isCustom = false, selected = false, sortOrder = 0),
                RoutineActivityEntity(label = "C", emoji = "c", isCustom = true, selected = false, sortOrder = 1),
            ),
        )
        val idOfC = dao.getAll().first { it.label == "C" }.id

        dao.setSelected(idOfC, true)

        assertThat(dao.observeAll().first().map { it.label }).containsExactly("A", "B", "C").inOrder()
        assertThat(dao.getAll().single { it.selected }.label).isEqualTo("C")
        assertThat(dao.count()).isEqualTo(3)
    }
}

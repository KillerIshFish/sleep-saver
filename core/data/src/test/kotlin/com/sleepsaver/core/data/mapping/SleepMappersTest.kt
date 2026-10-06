package com.sleepsaver.core.data.mapping

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.model.AddedAwake
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SleepMappersTest {

    private fun sessionEntity(
        start: String,
        end: String,
        startOffsetSec: Int?,
        endOffsetSec: Int?,
        ingestZoneId: String = "UTC",
    ) = SleepSessionEntity(
        id = "hc-1",
        originPackage = GARMIN,
        startEpochMs = instant(start).toEpochMilli(),
        endEpochMs = instant(end).toEpochMilli(),
        startOffsetSec = startOffsetSec,
        endOffsetSec = endOffsetSec,
        ingestZoneId = ingestZoneId,
        title = "Night",
        lastModifiedEpochMs = instant(end).plusSeconds(600).toEpochMilli(),
        sourceKind = SourceKind.HEALTH_CONNECT.name,
    )

    @Test
    fun `session keeps recorded offsets and maps stages in time order`() {
        val entity = SessionWithStages(
            session = sessionEntity("2026-10-05T21:00:00Z", "2026-10-06T05:00:00Z", 7200, 7200, ingestZoneId = "America/New_York"),
            stages = listOf(
                SleepStageEntity("hc-1", instant("2026-10-06T01:00:00Z").toEpochMilli(), instant("2026-10-06T05:00:00Z").toEpochMilli(), 5),
                SleepStageEntity("hc-1", instant("2026-10-05T21:00:00Z").toEpochMilli(), instant("2026-10-06T01:00:00Z").toEpochMilli(), 4),
            ),
        )

        val session = entity.toDomain()

        assertThat(session.startOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(session.endOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(session.origin).isEqualTo(GARMIN)
        assertThat(session.title).isEqualTo("Night")
        assertThat(session.lastModified).isEqualTo(instant("2026-10-06T05:10:00Z"))
        assertThat(session.stages.map { it.type }).containsExactly(SleepStageType.LIGHT, SleepStageType.DEEP).inOrder()
        assertThat(session.stages.first().start).isEqualTo(instant("2026-10-05T21:00:00Z"))
    }

    @Test
    fun `null offsets use the ingest zone at each instant, across a DST change`() {
        // US DST ends 2026-11-01 02:00 local: EDT (-4) before, EST (-5) after.
        val entity = SessionWithStages(
            session = sessionEntity("2026-11-01T04:30:00Z", "2026-11-01T13:00:00Z", null, null, ingestZoneId = "America/New_York"),
            stages = emptyList(),
        )

        val session = entity.toDomain()

        assertThat(session.startOffset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(session.endOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(session.localStart.toString()).isEqualTo("2026-11-01T00:30")
        assertThat(session.localEnd.toString()).isEqualTo("2026-11-01T08:00")
    }

    @Test
    fun `only the missing offset is filled in`() {
        val entity = SessionWithStages(
            session = sessionEntity("2026-10-05T21:00:00Z", "2026-10-06T05:00:00Z", null, 3600, ingestZoneId = "Europe/London"),
            stages = emptyList(),
        )

        val session = entity.toDomain()

        assertThat(session.startOffset).isEqualTo(ZoneOffset.ofHours(1))
        assertThat(session.endOffset).isEqualTo(ZoneOffset.ofHours(1))
    }

    @Test
    fun `unknown ingest zone falls back to UTC`() {
        val entity = SessionWithStages(sessionEntity("2026-10-05T21:00:00Z", "2026-10-06T05:00:00Z", null, null, "Not/AZone"), emptyList())

        assertThat(entity.toDomain().startOffset).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `unknown stage constant maps to UNKNOWN`() {
        val stage = SleepStageEntity("hc-1", 0, 60_000, 42)

        assertThat(stage.toDomain().type).isEqualTo(SleepStageType.UNKNOWN)
    }

    @Test
    fun `raw session round-trips through the entity`() {
        val session = RawSleepSession(
            id = "hc-9",
            origin = GARMIN,
            start = instant("2026-10-05T22:10:00Z"),
            end = instant("2026-10-06T06:20:00Z"),
            startOffset = ZoneOffset.ofHours(-4),
            endOffset = ZoneOffset.ofHours(-5),
            stages = listOf(
                SleepStage(instant("2026-10-05T22:10:00Z"), instant("2026-10-05T23:00:00Z"), SleepStageType.LIGHT),
                SleepStage(instant("2026-10-05T23:00:00Z"), instant("2026-10-05T23:10:00Z"), SleepStageType.AWAKE_IN_BED),
            ),
            lastModified = instant("2026-10-06T07:00:00Z"),
            title = null,
        )

        val entity = session.toEntity(SourceKind.HEALTH_CONNECT, ZoneId.of("America/New_York"))

        assertThat(entity.session.sourceKind).isEqualTo("HEALTH_CONNECT")
        assertThat(entity.session.ingestZoneId).isEqualTo("America/New_York")
        assertThat(entity.session.startOffsetSec).isEqualTo(-4 * 3600)
        assertThat(entity.stages.map { it.stage }).containsExactly(4, 7).inOrder()
        assertThat(entity.stages.map { it.sessionId }.toSet()).containsExactly("hc-9")
        assertThat(entity.toDomain()).isEqualTo(session)
    }

    @Test
    fun `override round-trips, including null fields`() {
        val full = NightOverride(
            wakeDate = LocalDate.of(2026, 10, 6),
            fellAsleep = instant("2026-10-05T22:30:00Z"),
            wokeUp = instant("2026-10-06T06:45:00Z"),
            awakeMinutes = 20,
            napMinutes = 15,
            isManualNight = true,
            offset = ZoneOffset.ofHoursMinutes(5, 30),
            updatedAt = instant("2026-10-06T08:00:00Z"),
        )
        val partial = NightOverride(
            wakeDate = LocalDate.of(2026, 10, 7),
            awakeMinutes = 5,
            offset = ZoneOffset.UTC,
            updatedAt = instant("2026-10-07T08:00:00Z"),
        )

        assertThat(full.toEntity().toDomain()).isEqualTo(full)
        assertThat(partial.toEntity().toDomain()).isEqualTo(partial)
        assertThat(full.toEntity().wakeDate).isEqualTo("2026-10-06")
        assertThat(full.toEntity().offsetSec).isEqualTo(5 * 3600 + 30 * 60)
        assertThat(partial.toEntity().fellAsleepEpochMs).isNull()
    }

    @Test
    fun `override entity maps to domain`() {
        val entity = NightOverrideEntity("2026-10-06", null, 1_000L, null, 30, false, -18_000, 2_000L)

        val override = entity.toDomain()

        assertThat(override.wakeDate).isEqualTo(LocalDate.of(2026, 10, 6))
        assertThat(override.fellAsleep).isNull()
        assertThat(override.wokeUp?.toEpochMilli()).isEqualTo(1_000L)
        assertThat(override.napMinutes).isEqualTo(30)
        assertThat(override.offset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(override.updatedAt.toEpochMilli()).isEqualTo(2_000L)
    }

    @Test
    fun `manual nap and added awake round-trip and keep createdAt`() {
        val nap = ManualNap(7, instant("2026-10-06T13:00:00Z"), instant("2026-10-06T13:25:00Z"), ZoneOffset.ofHours(2))
        val awake = AddedAwake(3, LocalDate.of(2026, 10, 6), instant("2026-10-06T02:00:00Z"), instant("2026-10-06T02:30:00Z"))
        val createdAt = instant("2026-10-06T15:00:00Z")

        val napEntity = nap.toEntity(createdAt)
        val awakeEntity = awake.toEntity(createdAt)

        assertThat(napEntity).isEqualTo(ManualNapEntity(7, nap.start.toEpochMilli(), nap.end.toEpochMilli(), 7200, createdAt.toEpochMilli()))
        assertThat(napEntity.toDomain()).isEqualTo(nap)
        assertThat(awakeEntity).isEqualTo(
            AddedAwakeEntity(3, "2026-10-06", awake.start.toEpochMilli(), awake.end.toEpochMilli(), createdAt.toEpochMilli()),
        )
        assertThat(awakeEntity.toDomain()).isEqualTo(awake)
    }

    @Test
    fun `vital round-trips and unknown types are dropped`() {
        val sample = VitalSample("rhr-1", VitalType.RESTING_HR, instant("2026-10-06T07:00:00Z"), 52.0, GARMIN)

        val entity = sample.toEntity(SourceKind.HEALTH_CONNECT, ZoneOffset.ofHours(2))

        assertThat(entity.type).isEqualTo("RESTING_HR")
        assertThat(entity.offsetSec).isEqualTo(7200)
        assertThat(entity.sourceKind).isEqualTo("HEALTH_CONNECT")
        assertThat(entity.toDomainOrNull()).isEqualTo(sample)
        assertThat(sample.toEntity(SourceKind.DEMO, null).offsetSec).isNull()
        assertThat(VitalEntity("x", "BLOOD_OXYGEN", 0, null, 97.0, GARMIN, "HEALTH_CONNECT").toDomainOrNull()).isNull()
    }
}

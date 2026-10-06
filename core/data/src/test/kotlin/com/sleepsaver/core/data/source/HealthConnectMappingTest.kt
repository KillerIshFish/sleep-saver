package com.sleepsaver.core.data.source

import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalType
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectMappingTest {

    private val newYork = ZoneId.of("America/New_York")
    private val garmin = SleepConfig.GARMIN_CONNECT_PACKAGE

    @Test
    fun session_keepsIdOriginRecordedOffsetsTitleAndLastModified() {
        val start = Instant.parse("2026-10-05T21:10:00Z")
        val end = Instant.parse("2026-10-06T05:00:00Z")
        val modified = Instant.parse("2026-10-06T05:20:00Z")
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.ofHours(2),
            endTime = end,
            endZoneOffset = ZoneOffset.ofHours(3),
            metadata = metadata("hc-1", garmin, modified),
            title = "Night",
        )

        val raw = record.toRawSleepSession(newYork)

        assertThat(raw.id).isEqualTo("hc-1")
        assertThat(raw.origin).isEqualTo(garmin)
        assertThat(raw.start).isEqualTo(start)
        assertThat(raw.end).isEqualTo(end)
        assertThat(raw.startOffset).isEqualTo(ZoneOffset.ofHours(2))
        assertThat(raw.endOffset).isEqualTo(ZoneOffset.ofHours(3))
        assertThat(raw.lastModified).isEqualTo(modified)
        assertThat(raw.title).isEqualTo("Night")
        assertThat(raw.localEnd).isEqualTo(LocalDateTime.of(2026, 10, 6, 8, 0))
        assertThat(raw.stages).isEmpty()
    }

    @Test
    fun session_missingOffsetsUseTheDeviceZoneAtEachInstant() {
        // New York leaves daylight time at 2026-11-01 02:00: the night starts at −4 and ends at −5.
        val start = LocalDateTime.of(2026, 10, 31, 23, 30).atZone(newYork).toInstant()
        val end = LocalDateTime.of(2026, 11, 1, 7, 0).atZone(newYork).toInstant()
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = end,
            endZoneOffset = null,
            metadata = metadata("hc-dst", garmin, end),
        )

        val raw = record.toRawSleepSession(newYork)

        assertThat(raw.startOffset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(raw.endOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(raw.localStart).isEqualTo(LocalDateTime.of(2026, 10, 31, 23, 30))
        assertThat(raw.localEnd).isEqualTo(LocalDateTime.of(2026, 11, 1, 7, 0))
        assertThat(raw.duration.toMinutes()).isEqualTo(8 * 60 + 30)
    }

    @Test
    fun session_mapsAndSortsStages() {
        val start = Instant.parse("2026-10-06T03:00:00Z")
        fun at(minutes: Long) = start.plusSeconds(minutes * 60)
        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = at(90),
            endZoneOffset = ZoneOffset.UTC,
            metadata = metadata("hc-stages", garmin, at(90)),
            stages = listOf(
                SleepSessionRecord.Stage(at(60), at(75), SleepSessionRecord.STAGE_TYPE_REM),
                SleepSessionRecord.Stage(at(0), at(10), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                SleepSessionRecord.Stage(at(10), at(30), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(at(30), at(50), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(at(50), at(55), SleepSessionRecord.STAGE_TYPE_AWAKE),
                SleepSessionRecord.Stage(at(55), at(60), SleepSessionRecord.STAGE_TYPE_OUT_OF_BED),
                SleepSessionRecord.Stage(at(75), at(80), SleepSessionRecord.STAGE_TYPE_SLEEPING),
                SleepSessionRecord.Stage(at(80), at(90), SleepSessionRecord.STAGE_TYPE_UNKNOWN),
            ),
        )

        val stages = record.toRawSleepSession(newYork).stages

        assertThat(stages.map { it.type }).containsExactly(
            SleepStageType.AWAKE_IN_BED,
            SleepStageType.LIGHT,
            SleepStageType.DEEP,
            SleepStageType.AWAKE,
            SleepStageType.OUT_OF_BED,
            SleepStageType.REM,
            SleepStageType.SLEEPING,
            SleepStageType.UNKNOWN,
        ).inOrder()
        assertThat(stages.first().start).isEqualTo(start)
        assertThat(stages.last().end).isEqualTo(at(90))
        assertThat(stages.zipWithNext().all { (a, b) -> a.end == b.start }).isTrue()
    }

    @Test
    fun session_keepsEachRecordsOwnOrigin() {
        val start = Instant.parse("2026-10-06T03:00:00Z")
        val records = listOf(garmin, "com.example.othertracker", "").mapIndexed { i, origin ->
            SleepSessionRecord(start, null, start.plusSeconds(3600), null, metadata("id-$i", origin, start))
        }

        assertThat(records.map { it.toRawSleepSession(newYork).origin })
            .containsExactly(garmin, "com.example.othertracker", "").inOrder()
    }

    @Test
    fun vitals_restingHeartRateAndHrvKeepTheirRecordIds() {
        val time = Instant.parse("2026-10-06T11:00:00Z")
        val rhr = RestingHeartRateRecord(time, null, 54, metadata("rhr-1", garmin, time)).toRecordedVital(newYork).sample
        val hrv = HeartRateVariabilityRmssdRecord(time, ZoneOffset.UTC, 48.5, metadata("hrv-1", garmin, time)).toRecordedVital(newYork).sample

        assertThat(rhr.id).isEqualTo("rhr-1")
        assertThat(rhr.type).isEqualTo(VitalType.RESTING_HR)
        assertThat(rhr.value).isEqualTo(54.0)
        assertThat(rhr.time).isEqualTo(time)
        assertThat(rhr.origin).isEqualTo(garmin)
        assertThat(hrv.id).isEqualTo("hrv-1")
        assertThat(hrv.type).isEqualTo(VitalType.HRV_RMSSD)
        assertThat(hrv.value).isEqualTo(48.5)
    }

    @Test
    fun vitals_keepTheRecordsOwnOffsetElseTheDeviceZones() {
        // New York is at −4 in October; the records were written while travelling (+9) or without an offset.
        val time = Instant.parse("2026-10-06T11:00:00Z")
        val tokyo = ZoneOffset.ofHours(9)

        val rhrAbroad = RestingHeartRateRecord(time, tokyo, 54, metadata("rhr-1", garmin, time)).toRecordedVital(newYork)
        val rhrNoOffset = RestingHeartRateRecord(time, null, 55, metadata("rhr-2", garmin, time)).toRecordedVital(newYork)
        val hrvAbroad = HeartRateVariabilityRmssdRecord(time, tokyo, 48.5, metadata("hrv-1", garmin, time)).toRecordedVital(newYork)
        val hrvNoOffset = HeartRateVariabilityRmssdRecord(time, null, 50.0, metadata("hrv-2", garmin, time)).toRecordedVital(newYork)

        assertThat(rhrAbroad.offset).isEqualTo(tokyo)
        assertThat(rhrNoOffset.offset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(hrvAbroad.offset).isEqualTo(tokyo)
        assertThat(hrvNoOffset.offset).isEqualTo(ZoneOffset.ofHours(-4))

        // Derived vitals: steps use the zone their day was sliced in, sleeping HR the session's own end offset.
        val winterDay = LocalDate.of(2026, 12, 1)
        assertThat(dailyStepsSample(winterDay, newYork, 5_000, setOf(garmin)).offset).isEqualTo(ZoneOffset.ofHours(-5))
        val start = Instant.parse("2026-10-05T14:00:00Z")
        val session = SleepSessionRecord(start, tokyo, start.plusSeconds(8 * 3600), tokyo, metadata("s-abroad", garmin, start))
            .toRawSleepSession(newYork)
        assertThat(sleepingHeartRateSample(session, 50).offset).isEqualTo(tokyo)
    }

    @Test
    fun vitals_derivedSamplesUseStableIds() {
        val date = LocalDate.of(2026, 10, 5)
        val steps = dailyStepsSample(date, newYork, 9_120, setOf("com.example.phone", garmin)).sample
        assertThat(steps.id).isEqualTo("steps:2026-10-05")
        assertThat(steps.type).isEqualTo(VitalType.STEPS_DAY)
        assertThat(steps.time).isEqualTo(date.atStartOfDay(newYork).toInstant())
        assertThat(steps.value).isEqualTo(9_120.0)
        assertThat(steps.origin).isEqualTo(garmin)

        assertThat(aggregateOrigin(setOf("b.app", "a.app"))).isEqualTo("a.app")
        assertThat(aggregateOrigin(emptySet())).isEqualTo("com.google.android.apps.healthdata")

        val start = Instant.parse("2026-10-06T03:00:00Z")
        val session = SleepSessionRecord(start, null, start.plusSeconds(7 * 3600), null, metadata("s-1", garmin, start))
            .toRawSleepSession(newYork)
        val sleepingHr = sleepingHeartRateSample(session, 51).sample
        assertThat(sleepingHr.id).isEqualTo("sleephr:s-1")
        assertThat(sleepingHr.type).isEqualTo(VitalType.SLEEP_HR_AVG)
        assertThat(sleepingHr.time).isEqualTo(session.end)
        assertThat(sleepingHr.value).isEqualTo(51.0)
    }

    @Test
    fun changeCollector_laterChangesWin() {
        val start = Instant.parse("2026-10-06T03:00:00Z")
        fun session(id: String, title: String? = null) =
            SleepSessionRecord(start, null, start.plusSeconds(8 * 3600), null, metadata(id, garmin, start), title = title)
        val collector = ChangeCollector(newYork)

        collector.add(
            listOf(
                UpsertionChange(session("kept")),
                UpsertionChange(session("deleted-later")),
                UpsertionChange(RestingHeartRateRecord(start, null, 55, metadata("rhr", garmin, start))),
                UpsertionChange(StepsRecord(start, null, start.plusSeconds(60), null, 10, metadata("steps", garmin, start))),
            ),
        )
        collector.add(
            listOf(
                DeletionChange("deleted-later"),
                DeletionChange("gone"),
                UpsertionChange(session("kept", title = "edited")),
            ),
        )
        val batch = collector.build("next")

        assertThat(batch.upserted.map { it.id to it.title }).containsExactly("kept" to "edited")
        assertThat(batch.deletedIds).containsExactly("deleted-later", "gone")
        assertThat(batch.deletedVitalIds).containsExactly("deleted-later", "gone")
        assertThat(batch.vitals.map { it.sample.id }).containsExactly("rhr")
        assertThat(batch.vitals.single().offset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(batch.nextToken).isEqualTo("next")
    }

    /** Metadata's full constructor is internal to the library; tests need ids, origins and modification times. */
    private fun metadata(id: String, origin: String, lastModified: Instant): Metadata =
        Metadata::class.java
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                DataOrigin::class.java,
                Instant::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Device::class.java,
            )
            .newInstance(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, id, DataOrigin(origin), lastModified, null, 0L, null)
}

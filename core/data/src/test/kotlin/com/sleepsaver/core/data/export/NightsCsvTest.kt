package com.sleepsaver.core.data.export

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.napOnlyDay
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.StageTotals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

class NightsCsvTest {

    private val date = LocalDate.of(2026, 10, 6)

    @Test
    fun `header lists every column in order`() {
        val csv = buildString { NightsCsv.write(emptyList(), this) }

        assertThat(csv).isEqualTo(
            "wake_date,night_of,fell_asleep_local,woke_up_local,total_min,main_min,awake_min,nap_min," +
                "deep_min,light_min,rem_min,efficiency_pct,interruptions,source,edited,manual," +
                "morning_feel,marks,anchor_latency_min\r\n",
        )
    }

    @Test
    fun `the v2 columns hold Morning feel, lowercased marks and the anchor latency`() {
        val night = testNight(date)

        val row = NightsCsv.row(night, CsvExtras(morningFeel = 2, marks = listOf("ALCOHOL", "STRESS"), anchorLatencyMin = 15))
        val csv = buildString { NightsCsv.write(listOf(night), this) { CsvExtras(morningFeel = 5, marks = emptyList(), anchorLatencyMin = null) } }

        assertThat(row.takeLast(3)).containsExactly("2", "alcohol;stress", "15").inOrder()
        assertThat(csv.split("\r\n")[1]).endsWith(",5,,")
    }

    @Test
    fun `night row uses the recorded offset for local times`() {
        val night = testNight(
            wakeDate = date,
            fellAsleep = "23:10",
            wokeUp = "06:40",
            offset = ZoneOffset.ofHours(-4),
            awake = Duration.ofMinutes(30),
            napSleep = Duration.ofMinutes(20),
            stageTotals = StageTotals(
                deep = Duration.ofMinutes(80),
                light = Duration.ofMinutes(250),
                rem = Duration.ofMinutes(90),
                awake = Duration.ofMinutes(30),
                unclassified = Duration.ZERO,
            ),
            interruptions = listOf(
                AwakePeriod(instant("2026-10-06T06:00:00Z"), instant("2026-10-06T06:20:00Z"), AwakeKind.STAGE),
                AwakePeriod(instant("2026-10-06T08:00:00Z"), instant("2026-10-06T08:10:00Z"), AwakeKind.GAP),
            ),
        )

        assertThat(NightsCsv.row(night)).containsExactly(
            "2026-10-06", "2026-10-05", "2026-10-05 23:10", "2026-10-06 06:40",
            "440", "420", "30", "20", "80", "250", "90", "93", "2", GARMIN, "false", "false", "", "", "",
        ).inOrder()
    }

    @Test
    fun `missing values are empty and flags reflect edits`() {
        val manual = testNight(
            wakeDate = date,
            origin = null,
            override = NightOverride(
                wakeDate = date,
                fellAsleep = instant("2026-10-05T23:00:00Z"),
                wokeUp = instant("2026-10-06T07:00:00Z"),
                isManualNight = true,
                offset = ZoneOffset.UTC,
                updatedAt = instant("2026-10-06T09:00:00Z"),
            ),
        )

        val napRow = NightsCsv.row(napOnlyDay(date))
        val manualRow = NightsCsv.row(manual)

        assertThat(napRow).containsExactly(
            "2026-10-06", "2026-10-05", "", "", "25", "0", "0", "25", "", "", "", "", "0", GARMIN, "false", "false", "", "", "",
        ).inOrder()
        assertThat(manualRow.subList(13, 16)).containsExactly("", "true", "true").inOrder()
        assertThat(manualRow[11]).isEqualTo("100")
    }

    @Test
    fun `stage columns stay empty when the night has no stage data`() {
        val zeroStages = StageTotals(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ofHours(8))

        val row = NightsCsv.row(testNight(date, stageTotals = zeroStages))

        assertThat(row.subList(8, 11)).containsExactly("", "", "")
    }

    @Test
    fun `fields are quoted per RFC 4180`() {
        assertThat(NightsCsv.escape("plain")).isEqualTo("plain")
        assertThat(NightsCsv.escape("")).isEqualTo("")
        assertThat(NightsCsv.escape("a,b")).isEqualTo("\"a,b\"")
        assertThat(NightsCsv.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(NightsCsv.escape("line\nbreak")).isEqualTo("\"line\nbreak\"")
        assertThat(NightsCsv.escape("cr\rhere")).isEqualTo("\"cr\rhere\"")
    }

    @Test
    fun `rows are CRLF-terminated and quoted where needed`() {
        val night = testNight(date, origin = "com.example,tracker")

        val lines = buildString { NightsCsv.write(listOf(night, testNight(date.plusDays(1))), this) }.split("\r\n")

        assertThat(lines).hasSize(4)
        assertThat(lines.last()).isEmpty()
        assertThat(lines[1]).isEqualTo("2026-10-06,2026-10-05,2026-10-05 23:00,2026-10-06 07:00,480,480,0,0,,,,100,0,\"com.example,tracker\",false,false,,,")
        assertThat(lines[2]).startsWith("2026-10-07,2026-10-06,")
    }
}

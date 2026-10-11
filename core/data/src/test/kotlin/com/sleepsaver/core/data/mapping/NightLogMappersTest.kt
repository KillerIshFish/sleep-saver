package com.sleepsaver.core.data.mapping

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AlarmTuningEntity
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.MovementEpochEntity
import com.sleepsaver.core.data.db.MovementLogEntity
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WakeStyle
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NightLogMappersTest {

    private val wakeDate = LocalDate.of(2026, 10, 6)
    private val offset = ZoneOffset.ofHours(-4)

    private val event = AlarmEvent(
        id = 7,
        kind = RingKind.PLANNED,
        occurrenceId = "2026-10-06T06:45",
        wakeDate = wakeDate,
        target = instant("2026-10-06T10:45:00Z"),
        windowStart = instant("2026-10-06T10:15:00Z"),
        mode = AlarmMode.SURFACING,
        style = WakeStyle.CALM,
        firstRingAt = instant("2026-10-06T10:27:00Z"),
        reason = RingReason.RESTLESS,
        maxZ = 3.4,
        snoozeTimes = listOf(instant("2026-10-06T10:28:00Z"), instant("2026-10-06T10:36:00Z")),
        stoppedAt = instant("2026-10-06T10:41:00Z"),
        outcome = RingOutcome.STOPPED,
        sensingStart = instant("2026-10-06T10:00:00Z"),
        offset = offset,
    )

    @Test
    fun `anchors round-trip, an unknown source reads as Tonight`() {
        val anchor = Anchor(3, instant("2026-10-06T02:55:00Z"), offset, wakeDate, AnchorSource.NIGHT_STEPS, wholeNightLog = true)

        assertThat(anchor.toEntity().toDomain()).isEqualTo(anchor)
        assertThat(anchor.toEntity().copy(source = "WIDGET").toDomain().source).isEqualTo(AnchorSource.TONIGHT)
    }

    @Test
    fun `missing epochs have no row and a trace keeps only its own epochs, oldest first`() {
        val start = instant("2026-10-06T10:00:00Z")
        assertThat(MovementEpoch(start, null).toEntityOrNull(1)).isNull()
        assertThat(MovementEpoch(start, 0.4).toEntityOrNull(1)).isEqualTo(MovementEpochEntity(1, start.toEpochMilli(), 0.4))

        val log = MovementLogEntity(1, start.toEpochMilli(), null, -14_400, "WINDOW", "2026-10-06", null, "2026-10-06T06:45")
        val epochs = listOf(
            MovementEpochEntity(1, start.toEpochMilli() + 30_000, 1.0),
            MovementEpochEntity(2, start.toEpochMilli(), 9.0),
            MovementEpochEntity(1, start.toEpochMilli(), 0.5),
        )

        assertThat(log.toDomain(epochs)).isEqualTo(
            MovementTrace(1, "WINDOW", start, null, listOf(MovementEpoch(start, 0.5), MovementEpoch(start.plusSeconds(30), 1.0))),
        )
    }

    @Test
    fun `alarm events round-trip with their snooze taps`() {
        val entity = event.toEntity()

        assertThat(entity.snoozeTimes).isEqualTo("${instant("2026-10-06T10:28:00Z").toEpochMilli()},${instant("2026-10-06T10:36:00Z").toEpochMilli()}")
        assertThat(entity.toDomainOrNull()).isEqualTo(event)
        val unsnoozed = event.copy(snoozeTimes = emptyList(), outcome = null, stoppedAt = null, maxZ = null, sensingStart = null)
        assertThat(unsnoozed.toEntity().toDomainOrNull()).isEqualTo(unsnoozed)
    }

    @Test
    fun `unreadable events are dropped, unknown mode and style fall back`() {
        val entity = event.toEntity()

        assertThat(entity.copy(kind = "PREDICTED").toDomainOrNull()).isNull()
        assertThat(entity.copy(reason = "MYSTERY").toDomainOrNull()).isNull()
        assertThat(entity.copy(outcome = "DISMISSED").toDomainOrNull()).isNull()
        val fallback = entity.copy(mode = "CYCLE", wakeStyle = "LOUD", snoozeTimes = "x,").toDomainOrNull()!!
        assertThat(fallback.mode).isEqualTo(AlarmMode.OFF)
        assertThat(fallback.style).isEqualTo(WakeStyle.STANDARD)
        assertThat(fallback.snoozeTimes).isEmpty()
    }

    @Test
    fun `tuning round-trips, an unknown reason is dropped`() {
        val tuning = AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, instant("2026-10-06T12:00:00Z"), offset)

        assertThat(tuning.toEntity().id).isEqualTo(1)
        assertThat(tuning.toEntity().toDomainOrNull()).isEqualTo(tuning)
        assertThat(AlarmTuningEntity(1, 3.0, "GUESSED", 0, 0).toDomainOrNull()).isNull()
    }

    @Test
    fun `Hush plays store their colour`() {
        val entity = HushColor.BROWN.toPlayEntity(instant("2026-10-06T03:00:00Z"), offset, wakeDate)

        assertThat(entity.endEpochMs).isNull()
        assertThat(entity.wakeDate).isEqualTo("2026-10-06")
        assertThat(entity.colorOrNull()).isEqualTo(HushColor.BROWN)
        assertThat(entity.copy(color = "BLUE").colorOrNull()).isNull()
    }
}

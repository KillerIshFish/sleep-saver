package com.sleepsaver.feature.review.edit

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.offset
import com.sleepsaver.feature.review.TestNight.wakeDate
import com.sleepsaver.feature.review.TestNight.zone
import com.sleepsaver.feature.review.common.NightSourceKind
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class NightEditorTest {

    private val need: Duration = Duration.ofHours(8)
    private val typicalWake: LocalTime = LocalTime.of(7, 0)
    private val now: Instant = TestNight.now

    private fun editor(overrides: List<NightOverride> = emptyList()) =
        NightEditorState.from(wakeDate, TestNight.night(overrides), need, typicalWake, zone)

    @Test
    fun `starts from the night's effective values`() {
        val e = editor()

        assertThat(e.kind).isEqualTo(EditKind.RECORDED)
        assertThat(e.fellAsleep).isEqualTo(TestNight.start)
        assertThat(e.wokeUp).isEqualTo(TestNight.end)
        assertThat(e.awakeMinutes).isEqualTo(30)
        assertThat(e.totalSleep).isEqualTo(Duration.ofMinutes(7 * 60 + 30))
        assertThat(e.source.kind).isEqualTo(NightSourceKind.HEALTH_CONNECT)
        assertThat(e.source.offset).isEqualTo(offset)
        assertThat(e.hasChanges).isFalse()
    }

    @Test
    fun `moving fell asleep saves only that field`() {
        val e = editor().withFellAsleep(at(wakeDate.minusDays(1), 23, 30))

        val save = e.toSave(now) as NightSave.Override

        assertThat(save.override.fellAsleep).isEqualTo(at(wakeDate.minusDays(1), 23, 30))
        assertThat(save.override.wokeUp).isNull()
        assertThat(save.override.awakeMinutes).isNull()
        assertThat(save.override.napMinutes).isNull()
        assertThat(save.override.isManualNight).isFalse()
        assertThat(save.override.offset).isEqualTo(offset)
        assertThat(e.totalSleep).isEqualTo(Duration.ofHours(7))
    }

    @Test
    fun `changing awake saves only awake`() {
        val e = editor().withAwake(35)

        val save = e.toSave(now) as NightSave.Override

        assertThat(save.override.awakeMinutes).isEqualTo(35)
        assertThat(save.override.fellAsleep).isNull()
        assertThat(save.override.wokeUp).isNull()
        assertThat(save.override.napMinutes).isNull()
    }

    @Test
    fun `changing naps saves only naps`() {
        val save = editor().withNaps(25).toSave(now) as NightSave.Override

        assertThat(save.override.napMinutes).isEqualTo(25)
        assertThat(save.override.fellAsleep).isNull()
        assertThat(save.override.awakeMinutes).isNull()
    }

    @Test
    fun `earlier edits of untouched fields are kept`() {
        val earlier = NightOverride(wakeDate, wokeUp = at(wakeDate, 7, 30), offset = offset, updatedAt = now)
        val e = editor(listOf(earlier))
        assertThat(e.isEdited).isTrue()
        assertThat(e.wokeUp).isEqualTo(at(wakeDate, 7, 30))

        val save = e.withAwake(45).toSave(now) as NightSave.Override

        assertThat(save.override.wokeUp).isEqualTo(at(wakeDate, 7, 30))
        assertThat(save.override.awakeMinutes).isEqualTo(45)
        assertThat(save.override.fellAsleep).isNull()
    }

    @Test
    fun `no change or a change undone saves nothing`() {
        val e = editor()
        assertThat(e.toSave(now)).isEqualTo(NightSave.NoChange)

        val undone = e.withFellAsleep(at(wakeDate.minusDays(1), 22)).withFellAsleep(TestNight.start)
        assertThat(undone.toSave(now)).isEqualTo(NightSave.NoChange)
        assertThat(undone.hasChanges).isFalse()
    }

    @Test
    fun `awake follows the window until it is set`() {
        val e = editor().withWokeUp(at(wakeDate, 2, 15))

        // Only 02:00–02:15 of the awake stretch is left inside the window.
        assertThat(e.awakeMinutes).isEqualTo(15)
        val save = e.toSave(now) as NightSave.Override
        assertThat(save.override.awakeMinutes).isNull()

        val set = editor().withAwake(50).withWokeUp(at(wakeDate, 6))
        assertThat(set.awakeMinutes).isEqualTo(50)
    }

    @Test
    fun `awake is capped by the time in bed`() {
        val e = NightEditorState.from(wakeDate, null, need, typicalWake, zone)
        val short = e.withWokeUp(e.fellAsleep.plus(Duration.ofHours(1)))

        assertThat(short.awakeMax).isEqualTo(60)
        assertThat(short.withAwake(240).awakeMinutes).isEqualTo(60)
    }

    @Test
    fun `stepping from an off-grid value lands on a five-minute mark`() {
        assertThat(NightEditorState.snapToStep(23, 28)).isEqualTo(25)
        assertThat(NightEditorState.snapToStep(23, 18)).isEqualTo(20)
        assertThat(NightEditorState.snapToStep(20, 25)).isEqualTo(25)
        assertThat(NightEditorState.snapToStep(3, 0)).isEqualTo(0)
    }

    @Test
    fun `a missing night starts at typical wake minus need and is added as a manual night`() {
        val e = NightEditorState.from(wakeDate, null, need, typicalWake, zone)

        assertThat(e.kind).isEqualTo(EditKind.NEW)
        assertThat(e.wokeUp).isEqualTo(at(wakeDate, 7))
        assertThat(e.fellAsleep).isEqualTo(at(wakeDate.minusDays(1), 23))
        assertThat(e.source.kind).isEqualTo(NightSourceKind.MANUAL)
        assertThat(e.canSave).isTrue()

        val save = e.toSave(now) as NightSave.ManualNight
        assertThat(save.wakeDate).isEqualTo(wakeDate)
        assertThat(save.fellAsleep).isEqualTo(at(wakeDate.minusDays(1), 23))
        assertThat(save.wokeUp).isEqualTo(at(wakeDate, 7))
        assertThat(save.offset).isEqualTo(offset)
        assertThat(save.minutes).isNull()
    }

    @Test
    fun `a new night with awake time also saves the minutes`() {
        val save = NightEditorState.from(wakeDate, null, need, typicalWake, zone).withAwake(20).toSave(now) as NightSave.ManualNight

        assertThat(save.minutes?.awakeMinutes).isEqualTo(20)
        assertThat(save.minutes?.isManualNight).isTrue()
    }

    @Test
    fun `editing a manual night keeps it manual with both times`() {
        val manual = NightOverride(
            wakeDate = wakeDate,
            fellAsleep = at(wakeDate.minusDays(1), 23),
            wokeUp = at(wakeDate, 6, 30),
            isManualNight = true,
            offset = offset,
            updatedAt = now,
        )
        val night = TestNight.nights(sessions = emptyList(), overrides = listOf(manual)).single()
        val e = NightEditorState.from(wakeDate, night, need, typicalWake, zone)
        assertThat(e.kind).isEqualTo(EditKind.MANUAL)

        val save = e.withAwake(10).toSave(now) as NightSave.Override

        assertThat(save.override.isManualNight).isTrue()
        assertThat(save.override.fellAsleep).isEqualTo(manual.fellAsleep)
        assertThat(save.override.wokeUp).isEqualTo(manual.wokeUp)
        assertThat(save.override.awakeMinutes).isEqualTo(10)
    }

    @Test
    fun `woke up before fell asleep or more than 16 hours in bed is invalid`() {
        val e = editor()

        val inverted = e.withWokeUp(e.fellAsleep)
        assertThat(inverted.error).isEqualTo(EditError.WAKE_BEFORE_SLEEP)
        assertThat(inverted.canSave).isFalse()

        val long = e.withFellAsleep(e.fellAsleepMin).withWokeUp(e.wokeUpMax)
        assertThat(long.error).isEqualTo(EditError.TOO_LONG)
        assertThat(long.canSave).isFalse()
    }

    @Test
    fun `scrubbers range six hours around where the night started`() {
        val e = editor().withFellAsleep(at(wakeDate.minusDays(1), 22))

        assertThat(e.fellAsleepMin).isEqualTo(TestNight.start.minus(Duration.ofHours(6)))
        assertThat(e.fellAsleepMax).isEqualTo(TestNight.start.plus(Duration.ofHours(6)))
        assertThat(e.wokeUpMax).isEqualTo(TestNight.end.plus(Duration.ofHours(6)))
    }
}

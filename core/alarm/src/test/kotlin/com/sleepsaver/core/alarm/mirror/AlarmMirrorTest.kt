package com.sleepsaver.core.alarm.mirror

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmOccurrence
import com.sleepsaver.core.domain.alarm.AlarmSource
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SurfacingWindow
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class AlarmMirrorTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 10, 7)
    private val target = ZonedDateTime.of(date, LocalTime.of(6, 30), zone)

    private val planned = PlannedAlarm(
        occurrence = AlarmOccurrence(
            date = date,
            localTime = LocalTime.of(6, 30),
            target = target.toInstant(),
            offset = target.offset,
            settings = AlarmSettings(
                mode = AlarmMode.SURFACING,
                window = Duration.ofMinutes(30),
                style = WakeStyle.FIRM,
                tone = AlarmTone.BRIGHT_BELLS,
                volume = 0.6f,
                snooze = SnoozeSettings(style = SnoozeStyle.FIXED, fixedLength = Duration.ofMinutes(7), fixedCount = 2, overrun = Duration.ofMinutes(15)),
            ),
            source = AlarmSource.DAY_PLAN,
        ),
        window = SurfacingWindow(target.toInstant() - Duration.ofMinutes(40), target.toInstant(), debtAware = true),
        plannedBedtime = target.toInstant() - Duration.ofHours(8),
        debtStatus = DebtStatus.MODERATE,
        latency = Duration.ofMinutes(15),
        need = Duration.ofHours(8),
    )
    private val armed = MirroredAlarm.from(planned, zThreshold = 2.25, expectedOnset = target.toInstant() - Duration.ofHours(7))
        .copy(sensingStartEpochMs = (target.toInstant() - Duration.ofMinutes(55)).toEpochMilli(), sensingOutcome = "NO_TRIGGER", maxZ = 1.7)
    private val next = armed.copy(occurrenceId = "2026-10-08T06:30", date = "2026-10-08", targetEpochMs = armed.targetEpochMs + DAY_MS)
    private val event = AlarmEvent(
        id = 12,
        kind = RingKind.PLANNED,
        occurrenceId = armed.occurrenceId,
        wakeDate = date,
        target = target.toInstant(),
        windowStart = target.toInstant() - Duration.ofMinutes(40),
        mode = AlarmMode.SURFACING,
        style = WakeStyle.FIRM,
        firstRingAt = target.toInstant() - Duration.ofMinutes(18),
        reason = RingReason.RESTLESS,
        maxZ = 3.1,
        snoozeTimes = listOf(target.toInstant() - Duration.ofMinutes(17)),
        stoppedAt = target.toInstant(),
        outcome = RingOutcome.STOPPED,
        sensingStart = target.toInstant() - Duration.ofMinutes(55),
        offset = target.offset,
    )

    /** Every key holds a non-default value. */
    private val full = MirrorState(
        armed = armed,
        upcoming = listOf(next),
        extra = MirroredExtra(
            trigger = ExtraTrigger.SNOOZE.name,
            atEpochMs = armed.targetEpochMs - 60_000,
            ringKind = RingKind.PLANNED.name,
            occurrenceId = armed.occurrenceId,
            snoozesUsed = 1,
            reason = RingReason.SNOOZE_END.name,
            alarm = armed,
            expiresAtEpochMs = armed.targetEpochMs + 540_000,
            eventId = 12,
            event = MirroredEvent.from(event),
            isBackup = false,
        ),
        catnap = MirroredCatnap(startedAtEpochMs = 1_000, ringAtEpochMs = 1_500_000, lengthMin = 20, tone = "WARM_RISE", volume = 0.5f, expiresAtEpochMs = 2_100_000),
        ringing = MirroredRinging(
            ringKind = RingKind.NAP.name,
            trigger = "CATNAP",
            occurrenceId = null,
            sinceEpochMs = 3_000,
            snoozesUsed = 0,
            reason = RingReason.CATNAP_END.name,
            alarm = armed.copy(occurrenceId = ""),
            eventId = null,
            isBackup = true,
            expiresAtEpochMs = 723_000,
            event = MirroredEvent.from(event.copy(id = 0, kind = RingKind.NAP, occurrenceId = null)),
        ),
        night = MirroredNight(anchorId = 4, anchoredAtEpochMs = 5_000, offsetSec = -14_400, wholeNightLog = true, logId = 9, endsAtEpochMs = 43_205_000),
        hush = MirroredHush(color = "BROWN", volume = 0.3f, timerMin = null, endsAtEpochMs = 9_000_000, playId = 2),
        handledIds = listOf("2026-10-06T06:30", "2026-10-05T07:00"),
        pendingEvents = listOf(MirroredEvent.from(event)),
        pendingFeel = MirroredFeel(wakeDate = "2026-10-07", level = 4, atEpochMs = 7_000, offsetSec = -14_400),
        prior = MirroredPrior(binMin = 10, sinceOnset = listOf(0.1, 0.6), beforeWake = listOf(0.2), nightsUsed = 9),
        use24h = true,
        generation = 41,
    )

    @Test
    fun `every key round-trips through device-protected storage`() {
        AlarmMirror(context).write(MirrorState(), full)

        assertThat(AlarmMirror(context).read()).isEqualTo(full)
    }

    @Test
    fun `it lives in device-protected storage, readable before the first unlock`() {
        AlarmMirror(context).write(MirrorState(), full)

        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(AlarmMirror.PREFS_NAME, Context.MODE_PRIVATE)
        assertThat(prefs.getString(AlarmMirror.KEY_ARMED, null)).contains(armed.occurrenceId)
        assertThat(prefs.getLong(AlarmMirror.KEY_GENERATION, 0)).isEqualTo(41)
    }

    @Test
    fun `unknown keys and unknown fields are ignored`() {
        AlarmMirror(context).write(MirrorState(), MirrorState(armed = armed))
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(AlarmMirror.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = checkNotNull(prefs.getString(AlarmMirror.KEY_ARMED, null))
        prefs.edit()
            .putString(AlarmMirror.KEY_ARMED, stored.dropLast(1) + ""","fromAFutureVersion":{"x":1}}""")
            .putString("somethingNew", "[1,2,3]")
            .commit()

        assertThat(AlarmMirror(context).read().armed).isEqualTo(armed)
    }

    @Test
    fun `an unreadable key is dropped and the others survive`() {
        AlarmMirror(context).write(MirrorState(), full)
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(AlarmMirror.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(AlarmMirror.KEY_EXTRA, "{not json").commit()

        val read = AlarmMirror(context).read()

        assertThat(read.extra).isNull()
        assertThat(read.armed).isEqualTo(armed)
        assertThat(read.hush).isEqualTo(full.hush)
    }

    @Test
    fun `writes commit, never apply, and touch only the keys that changed`() {
        val recording = RecordingContext(context)
        val mirror = AlarmMirror(recording)

        mirror.write(MirrorState(), full)
        mirror.write(full, full.copy(hush = null, generation = 42))
        mirror.clear()

        assertThat(recording.applies).isEqualTo(0)
        assertThat(recording.commits).isEqualTo(3)
        assertThat(recording.touched[1]).containsExactly(AlarmMirror.KEY_HUSH, AlarmMirror.KEY_GENERATION)
    }

    @Test
    fun `clear removes every key`() {
        AlarmMirror(context).write(MirrorState(), full)

        AlarmMirror(context).clear()

        assertThat(AlarmMirror(context).read()).isEqualTo(MirrorState())
    }

    @Test
    fun `domain mappers round trip`() {
        assertThat(MirroredEvent.from(event).toDomain()).isEqualTo(event)
        val prior = DeepPrior(Duration.ofMinutes(10), listOf(0.1, 0.7), listOf(0.3, 0.2), nightsUsed = 12)
        assertThat(MirroredPrior.from(prior).toDomain()).isEqualTo(prior)
        assertThat(armed.settings()).isEqualTo(planned.occurrence.settings)
        assertThat(armed.target).isEqualTo(planned.occurrence.target)
        assertThat(armed.windowStart).isEqualTo(planned.window.start)
        assertThat(armed.offset).isEqualTo(planned.occurrence.offset)
        assertThat(armed.wakeDate).isEqualTo(date)
        assertThat(armed.debtAware).isTrue()
        assertThat(MirroredFeel("2026-10-07", 2, 0, 0).feel).isEqualTo(MorningFeel.SLOW)
        assertThat(MirroredHush("WHITE", 0.2f, 30, null, null).let { it.hushColor to it.timer }).isEqualTo(HushColor.WHITE to Duration.ofMinutes(30))
    }

    @Test
    fun `a renamed enum reads back as its default instead of losing the key`() {
        val odd = armed.copy(style = "SOMETHING_NEW", tone = "GONE", mode = "LATER")

        assertThat(odd.wakeStyle).isEqualTo(WakeStyle.STANDARD)
        assertThat(odd.alarmTone).isEqualTo(AlarmTone.SOFT_CHIMES)
        assertThat(odd.alarmMode).isEqualTo(AlarmMode.FIXED)
    }

    /** Counts commit/apply and the keys each edit touched, on the device-protected prefs. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var commits = 0
        var applies = 0
        val touched = mutableListOf<MutableList<String>>()

        override fun createDeviceProtectedStorageContext(): Context = object : ContextWrapper(baseContext.createDeviceProtectedStorageContext()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = Prefs(super.getSharedPreferences(name, mode))
        }

        private inner class Prefs(private val real: SharedPreferences) : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor = Editor(real.edit(), mutableListOf<String>().also { touched += it })
        }

        private inner class Editor(private val real: SharedPreferences.Editor, private val keys: MutableList<String>) : SharedPreferences.Editor by real {
            override fun putString(key: String, value: String?): SharedPreferences.Editor = touch(key) { real.putString(key, value) }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = touch(key) { real.putLong(key, value) }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = touch(key) { real.putBoolean(key, value) }
            override fun remove(key: String): SharedPreferences.Editor = touch(key) { real.remove(key) }
            override fun clear(): SharedPreferences.Editor {
                real.clear()
                return this
            }

            private fun touch(key: String, write: () -> Unit): SharedPreferences.Editor {
                keys += key
                write()
                return this
            }

            override fun commit(): Boolean {
                commits++
                return real.commit()
            }

            override fun apply() {
                applies++
                real.apply()
            }
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

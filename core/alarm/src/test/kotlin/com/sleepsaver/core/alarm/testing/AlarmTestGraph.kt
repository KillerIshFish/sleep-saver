package com.sleepsaver.core.alarm.testing

import android.app.Application
import com.sleepsaver.core.alarm.AlarmBootstrap
import com.sleepsaver.core.alarm.AlarmCoordinator
import com.sleepsaver.core.alarm.AlarmSchedulerImpl
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.mirror.AlarmMirror
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.night.HushOutputs
import com.sleepsaver.core.alarm.night.HushSound
import com.sleepsaver.core.alarm.night.MotionSensors
import com.sleepsaver.core.alarm.night.MotionSource
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.ring.RingOutputs
import com.sleepsaver.core.alarm.ring.RingSound
import com.sleepsaver.core.alarm.ring.RingVibration
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightLogRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.FakeTimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * core:alarm wired by hand for Robolectric tests: the real mirror, coordinator, scheduler, controller and bootstrap
 * over core:testing's fakes, a fake clock, an eager app scope, and fake audio, vibration and sensor outputs. Repository
 * lazies count resolutions and can be set to throw (a locked user).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AlarmTestGraph(val context: Application) {
    val time = FakeTimeProvider(now = START, zone = ZONE)

    /** The app scope's dispatcher: eager, with virtual time (advance its scheduler for delays such as debounce). */
    val dispatcher = UnconfinedTestDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val fakeSchedule = FakeScheduleRepository()
    val nightLog = FakeNightLogRepository(clock = { time.now })
    val journal = FakeJournalRepository(clock = { time.now }, zone = ZONE)
    val patterns = FakePatternsRepository()
    val settings = FakeSettingsRepository()
    val sync = FakeSyncRepository()
    val planner = CountingPlanner()

    /** Runs inside each computeAlarmPlan (with its call number, from 1): a write landing during a reschedule's compute. */
    var onComputeAlarmPlan: ((Int) -> Unit)? = null
    private val schedule = object : ScheduleRepository by fakeSchedule {
        override suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan {
            onComputeAlarmPlan?.invoke(fakeSchedule.alarmPlanCalls.size + 1)
            return fakeSchedule.computeAlarmPlan(now, exclude)
        }
    }

    val scheduleLazy = TestLazy<ScheduleRepository> { schedule }
    val nightLogLazy = TestLazy<NightLogRepository> { nightLog }
    val journalLazy = TestLazy<JournalRepository> { journal }
    val patternsLazy = TestLazy<PatternsRepository> { patterns }
    val settingsLazy = TestLazy<SettingsRepository> { settings }
    val syncLazy = TestLazy<SyncRepository> { sync }
    val plannerLazy = TestLazy<ReminderPlanner> { planner }
    private val repositoryLazies = listOf(scheduleLazy, nightLogLazy, journalLazy, patternsLazy, settingsLazy, syncLazy, plannerLazy)

    val ringOutputs = FakeRingOutputs()
    val hushOutputs = FakeHushOutputs()
    val motion = FakeMotionSensors()

    val mirror = AlarmMirror(context)
    val coordinator = AlarmCoordinator(context, mirror)
    val scheduler = AlarmSchedulerImpl(context, coordinator, time, scope, scheduleLazy, nightLogLazy, patternsLazy, settingsLazy)
    val schedulerLazy = TestLazy { scheduler }
    val nightController = NightControllerImpl(context, coordinator, time, scope, schedulerLazy, nightLogLazy, journalLazy, plannerLazy, ringOutputs)
    val bootstrap = AlarmBootstrap(context, coordinator, nightController, schedulerLazy, scheduleLazy, nightLogLazy, journalLazy, patternsLazy, settingsLazy, syncLazy, scope)

    /** Every repository Lazy throws on get() (the user is locked: credential-encrypted storage is off limits). */
    fun lockRepositories() = repositoryLazies.forEach { it.failing = true }

    /** The first unlock: repositories may be resolved again. */
    fun unlockRepositories() = repositoryLazies.forEach { it.failing = false }

    /** How often any repository Lazy was resolved. */
    val repositoryResolutions: Int get() = repositoryLazies.sumOf { it.resolutions }

    /** Writes [change] through the coordinator (no AlarmManager calls). */
    fun seed(change: (MirrorState) -> MirrorState) = coordinator.apply { m -> MirrorUpdate(change(m), Unit) }

    fun inject(service: AlarmRingService) {
        service.coordinator = coordinator
        service.scheduler = schedulerLazy
        service.nightController = nightController
        service.time = time
        service.outputs = ringOutputs
    }

    fun inject(service: NightService) {
        service.coordinator = coordinator
        service.nightController = nightController
        service.nightLog = nightLogLazy
        service.time = time
        service.scope = scope
        service.sensors = motion
        service.hushOutputs = hushOutputs
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("America/New_York")

        /** Tuesday 2026-10-06, 05:00 New York. */
        val START: Instant = ZonedDateTime.of(2026, 10, 6, 5, 0, 0, 0, ZONE).toInstant()
    }
}

/** A dagger.Lazy that counts resolutions and can be made to throw. */
internal class TestLazy<T>(private val factory: () -> T) : dagger.Lazy<T> {
    var failing = false
    var resolutions = 0
        private set

    override fun get(): T {
        if (failing) throw IllegalStateException("A repository was resolved while the user is locked")
        resolutions++
        return factory()
    }
}

internal class CountingPlanner : ReminderPlanner {
    var replans = 0
    override suspend fun replan() {
        replans++
    }

    override fun cancelAll() = Unit
}

/** Records what the ring played. [failingSound]: every sound made from now on throws, like a broken player. */
internal class FakeRingOutputs : RingOutputs {
    val sounds = mutableListOf<FakeSound>()
    val vibrations = mutableListOf<FakeVibration>()
    var failingSound = false

    override fun sound(tone: AlarmTone, locked: Boolean): RingSound = FakeSound(tone, locked, failingSound).also { sounds += it }
    override fun vibration(): RingVibration = FakeVibration().also { vibrations += it }

    class FakeSound(val tone: AlarmTone, val locked: Boolean, private val failing: Boolean = false) : RingSound {
        val gains = mutableListOf<Float>()
        var started = false
        var stopped = false
        var focusReclaims = 0
        override fun start(gain: Float) {
            if (failing) throw IllegalStateException("No audio track for the alarm")
            started = true
            gains += gain
        }

        override fun setGain(gain: Float) {
            if (failing) throw IllegalStateException("No audio track for the alarm")
            gains += gain
        }

        override fun reclaimFocus() {
            focusReclaims++
        }

        override fun stop() {
            stopped = true
        }
    }

    class FakeVibration : RingVibration {
        var on = false
        var everOn = false
        override fun start() {
            on = true
            everOn = true
        }

        override fun stop() {
            on = false
        }
    }
}

internal class FakeHushOutputs : HushOutputs {
    val sounds = mutableListOf<FakeNoise>()
    override fun noise(color: HushColor): HushSound = FakeNoise(color).also { sounds += it }

    class FakeNoise(val color: HushColor) : HushSound {
        var playing = false
        var stopped = false
        val gains = mutableListOf<Float>()
        override fun start(gain: Float) {
            playing = true
            gains += gain
        }

        override fun setGain(gain: Float) {
            gains += gain
        }

        override fun pause() {
            playing = false
        }

        override fun resume() {
            playing = true
        }

        override fun stop() {
            playing = false
            stopped = true
        }
    }
}

/** An accelerometer the test drives; [available] false = a phone without one. */
internal class FakeMotionSensors(var available: Boolean = true) : MotionSensors {
    val sources = mutableListOf<FakeSource>()
    override fun create(): MotionSource = FakeSource(available).also { sources += it }

    class FakeSource(private val available: Boolean) : MotionSource {
        var started = false
        var stopped = false
        override val wakeUp: Boolean = true
        override fun start(onSample: (Instant, Float, Float, Float) -> Unit): Boolean {
            started = available
            return available
        }

        override fun stop() {
            stopped = true
        }
    }
}

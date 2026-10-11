package com.sleepsaver.core.alarm.testing

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.receiver.RingWakeLock
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmOccurrence
import com.sleepsaver.core.domain.alarm.AlarmSource
import com.sleepsaver.core.domain.alarm.SurfacingWindow
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Base of core:alarm's Robolectric tests: a fresh [AlarmTestGraph] per test, notifications allowed, the alarm channels
 * created, and the services' in-process state cleared afterwards. Times are New York; the clock starts Tuesday
 * 2026-10-06 05:00.
 */
internal abstract class AlarmRobolectricTest {
    protected val context: android.app.Application = ApplicationProvider.getApplicationContext()
    protected lateinit var graph: AlarmTestGraph
    protected val alarms: ShadowAlarmManager get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    protected val notificationManager: NotificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    protected val notifications get() = shadowOf(notificationManager)
    protected val today: LocalDate = LocalDate.of(2026, 10, 6)

    @Before
    fun setUpGraph() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true) // Robolectric's default is false
        graph = AlarmTestGraph(context)
        TestAlarmApp.graph = graph
        AlarmChannels.ensure(context)
    }

    @After
    fun tearDownGraph() {
        AlarmRingService.current = null
        AlarmRingService.running = false
        NightService.current = null
        NightService.running = false
        RingWakeLock.release()
        TestAlarmApp.graph = null
    }

    protected fun at(date: LocalDate, time: String): Instant = ZonedDateTime.of(date, LocalTime.parse(time), AlarmTestGraph.ZONE).toInstant()

    /** A planned alarm on [date] at [time] (New York), 30-min SURFACING window unless said otherwise. */
    protected fun planned(
        date: LocalDate,
        time: String,
        mode: AlarmMode = AlarmMode.SURFACING,
        window: Duration = Duration.ofMinutes(30),
        style: WakeStyle = WakeStyle.STANDARD,
    ): PlannedAlarm {
        val target = ZonedDateTime.of(date, LocalTime.parse(time), AlarmTestGraph.ZONE)
        val settings = AlarmSettings(mode = mode, window = window, style = style)
        val occurrence = AlarmOccurrence(date, LocalTime.parse(time), target.toInstant(), target.offset, settings, AlarmSource.DAY_PLAN)
        val length = if (mode == AlarmMode.SURFACING) window else Duration.ZERO
        return PlannedAlarm(
            occurrence = occurrence,
            window = SurfacingWindow(target.toInstant() - length, target.toInstant(), debtAware = false),
            plannedBedtime = target.toInstant() - Duration.ofHours(8),
            debtStatus = DebtStatus.LOW,
            latency = Duration.ofMinutes(15),
            need = Duration.ofHours(8),
        )
    }

    protected fun mirrored(alarm: PlannedAlarm): MirroredAlarm = MirroredAlarm.from(alarm, zThreshold = 2.5, expectedOnset = null)

    /** Makes computeAlarmPlan return these alarms (filtered by now and the excluded ids, like the real one). */
    protected fun plan(vararg alarms: PlannedAlarm) {
        graph.fakeSchedule.upcomingState.value = alarms.toList()
    }

    protected fun planResult(next: PlannedAlarm?, plannedIds: Set<String> = setOfNotNull(next?.occurrence?.id)) {
        graph.fakeSchedule.alarmPlanResult = AlarmPlan(next, emptyList(), plannedIds, graph.time.now)
    }

    protected fun scheduled(requestCode: Int): List<ShadowAlarmManager.ScheduledAlarm> =
        alarms.scheduledAlarms.filter { shadowOf(it.operation).requestCode == requestCode }

    protected fun armedAt(requestCode: Int): Instant? = scheduled(requestCode).singleOrNull()?.let { Instant.ofEpochMilli(it.triggerAtMs) }

    protected val ringPlanAt: Instant? get() = armedAt(AlarmActions.RC_RING_PLAN)
    protected val windowAt: Instant? get() = armedAt(AlarmActions.RC_WINDOW)
    protected val ringExtraAt: Instant? get() = armedAt(AlarmActions.RC_RING_EXTRA)
    protected val ringCatnapAt: Instant? get() = armedAt(AlarmActions.RC_RING_CATNAP)

    /** The occurrence id the armed RING_PLAN carries. */
    protected val ringPlanId: String?
        get() = scheduled(AlarmActions.RC_RING_PLAN).singleOrNull()?.let { shadowOf(it.operation).savedIntent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID) }

    protected fun setUnlocked(unlocked: Boolean) {
        shadowOf(context.getSystemService(Context.USER_SERVICE) as UserManager).setUserUnlocked(unlocked)
    }

    protected fun idleMain(duration: Duration = Duration.ZERO) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    // ---------------------------------------------------------------- Shortcuts

    /** The mirror as stored now. */
    protected val m: MirrorState get() = graph.coordinator.read()

    protected var now: Instant
        get() = graph.time.now
        set(value) {
            graph.time.now = value
        }

    /** Moves the fake clock and the main looper (SystemClock, coroutine delays) on by [d]. */
    protected fun advance(d: Duration) {
        graph.time.now += d
        idleMain(d)
    }

    protected fun reschedule() = runBlocking { graph.scheduler.reschedule() }

    protected val startedServices: List<Intent> get() = shadowOf(context).allStartedServices

    protected fun ringIntent(kind: String, occurrenceId: String? = null): Intent =
        Intent(context, AlarmRingService::class.java)
            .setAction(AlarmActions.ACTION_RING)
            .putExtra(AlarmActions.EXTRA_KIND, kind)
            .putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId)

    /** AlarmRingService created and started with a RING of [kind] (what AlarmReceiver starts). */
    protected fun startRing(kind: String, occurrenceId: String? = null): ServiceController<AlarmRingService> =
        Robolectric.buildService(AlarmRingService::class.java, ringIntent(kind, occurrenceId)).create().startCommand(0, 1)

    /** Another start of the same service (a notification action or another ring). */
    protected fun <S : android.app.Service> ServiceController<S>.send(intent: Intent): ServiceController<S> =
        withIntent(intent).startCommand(0, ++startIds)

    protected fun ringAction(action: String): Intent = Intent(context, AlarmRingService::class.java).setAction(action)

    protected fun nightIntent(action: String, occurrenceId: String? = null): Intent =
        Intent(context, NightService::class.java).setAction(action).apply { if (occurrenceId != null) putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId) }

    protected fun startNight(action: String, occurrenceId: String? = null): ServiceController<NightService> =
        Robolectric.buildService(NightService::class.java, nightIntent(action, occurrenceId)).create().startCommand(0, 1)

    private var startIds = 1
}

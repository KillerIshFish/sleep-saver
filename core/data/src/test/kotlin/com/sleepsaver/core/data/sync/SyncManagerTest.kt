package com.sleepsaver.core.data.sync

import android.health.connect.HealthConnectException
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SyncManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fx: SyncFixture
    private val today: LocalDate = LocalDate.of(2026, 10, 6)

    @Before
    fun setUp() {
        fx = SyncFixture(tmp.newFolder())
    }

    @After
    fun tearDown() = fx.close()

    /** A 23:10 → 06:50 night ending on [wakeDate]. */
    private fun night(wakeDate: LocalDate, origin: String = GARMIN, id: String = "hc:$wakeDate:$origin"): RawSleepSession =
        hcSession(id, fx.zone, wakeDate.minusDays(1).atTime(23, 10), wakeDate.atTime(6, 50), origin)

    private suspend fun storedIds() = fx.storedSessions().map { it.session.id }

    @Test
    fun firstSyncImportsHistory_tokenFirst() = runTest {
        fx.source.seed(night(today.minusDays(3)), night(today.minusDays(2)), night(today.minusDays(1)))
        val manager = fx.manager(backgroundScope)

        val result = manager.sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(result.error).isNull()
        assertThat(result.sessionsChanged).isEqualTo(3)
        assertThat(fx.source.calls.first()).isEqualTo("token")
        // Without the history permission only 30 days are read: one chunk.
        assertThat(fx.source.calls.count { it == "read" }).isEqualTo(1)
        val stored = fx.storedSessions()
        assertThat(stored.map { it.session.id }).containsExactlyElementsIn(fx.source.records.keys)
        assertThat(stored.map { it.session.sourceKind }.toSet()).containsExactly(SourceKind.HEALTH_CONNECT.name)
        assertThat(stored.all { it.stages.size == 2 }).isTrue()
        val saved = fx.store.current()
        assertThat(saved.changesToken).isEqualTo("token-1")
        assertThat(saved.tokenSourceId).isEqualTo("health_connect")
        assertThat(saved.historyImportDone).isTrue()
        assertThat(manager.state.value.lastSuccessAt).isEqualTo(fx.time.now)
        assertThat(manager.state.value.status).isEqualTo(SyncStatus.Idle)
        assertThat(fx.db.vitalDao().getAll().map { it.id }).contains(VitalIds.sleepingHeartRate("hc:${today.minusDays(1)}:$GARMIN"))
    }

    @Test
    fun historyPermission_reads365DaysIn30DayChunks() = runTest {
        fx.gateway.granted = setOf(HealthConnectPermissions.SLEEP, HealthConnectPermissions.HISTORY)
        val old = night(today.minusDays(300))
        val tooOld = night(today.minusDays(400))
        fx.source.seed(old, tooOld)

        val result = fx.manager(backgroundScope).importHistory()

        assertThat(result.success).isTrue()
        assertThat(fx.source.calls.count { it == "read" }).isEqualTo(13)
        assertThat(storedIds()).containsExactly(old.id)
    }

    @Test
    fun incrementalSync_appliesUpsertsAndDeletes() = runTest {
        val a = night(today.minusDays(2))
        val b = night(today.minusDays(1))
        fx.source.seed(a, b)
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)
        fx.source.calls.clear()

        val edited = b.copy(end = b.end.minusSeconds(600), stages = emptyList())
        val lastNight = night(today)
        fx.source.upsert(edited)
        fx.source.upsert(lastNight)
        fx.source.delete(a.id)
        val result = manager.sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(result.sessionsChanged).isEqualTo(3)
        assertThat(fx.source.calls.first()).isEqualTo("changes:token-1")
        assertThat(fx.source.calls).doesNotContain("token")
        val stored = fx.storedSessions().associateBy { it.session.id }
        assertThat(stored.keys).containsExactly(b.id, lastNight.id)
        assertThat(stored.getValue(b.id).session.endEpochMs).isEqualTo(edited.end.toEpochMilli())
        assertThat(stored.getValue(b.id).stages).isEmpty()
        assertThat(stored.getValue(lastNight.id).stages).hasSize(2)
        val vitalIds = fx.db.vitalDao().getAll().map { it.id }
        assertThat(vitalIds).doesNotContain(VitalIds.sleepingHeartRate(a.id))
        assertThat(vitalIds).contains(VitalIds.sleepingHeartRate(lastNight.id))
        assertThat(fx.store.current().changesToken).isEqualTo("token-2")
    }

    @Test
    fun expiredToken_fallsBackToFullImport() = runTest {
        val a = night(today.minusDays(2))
        val b = night(today.minusDays(1))
        fx.source.seed(a, b)
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)
        fx.source.calls.clear()

        fx.source.records.remove(b.id) // deleted while nobody followed the change feed
        fx.source.expireNextToken = true
        val result = manager.sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(fx.source.calls.take(3)).containsExactly("changes:token-1", "token", "read").inOrder()
        assertThat(storedIds()).containsExactly(a.id)
        assertThat(fx.store.current().changesToken).isEqualTo("token-2")
    }

    @Test
    fun rejectedToken_fallsBackToFullImport() = runTest {
        val a = night(today.minusDays(2))
        fx.source.seed(a)
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)
        fx.source.calls.clear()

        // What the client library throws on Android 14+ for a token Health Connect doesn't know (data cleared/migrated).
        fx.source.failNextChanges = IllegalArgumentException(frameworkException(HealthConnectException.ERROR_INVALID_ARGUMENT))
        val result = manager.sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(fx.source.calls.take(3)).containsExactly("changes:token-1", "token", "read").inOrder()
        assertThat(storedIds()).containsExactly(a.id)
        assertThat(fx.store.current().changesToken).isEqualTo("token-2")
        assertThat(manager.state.value.lastError).isNull()
    }

    @Test
    fun frameworkRateLimit_isRateLimited() = runTest {
        val manager = fx.manager(backgroundScope)

        // Android 14+: the client library wraps the framework's HealthConnectException in an IllegalStateException.
        fx.source.failWith = IllegalStateException(frameworkException(HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED))
        assertThat(manager.sync(SyncTrigger.APP_OPEN).error).isEqualTo(SyncError.RATE_LIMITED)

        fx.source.failWith = IllegalStateException(frameworkException(HealthConnectException.ERROR_INTERNAL))
        assertThat(manager.sync(SyncTrigger.APP_OPEN).error).isEqualTo(SyncError.UNKNOWN)
    }

    @Test
    fun lastNight_lateWakeCountsButSleepStartingThisEveningDoesNot() = runTest {
        fx.time.setLocal(today.atTime(23, 0))
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)

        // Dozing off on the sofa this evening belongs to tonight (tomorrow's wake date).
        fx.source.upsert(hcSession("doze", fx.zone, today.atTime(20, 30), today.atTime(21, 40)))
        assertThat(manager.sync(SyncTrigger.APP_OPEN).lastNightArrived).isFalse()

        // A long sleep that ends after 14:00 is still last night (PLAN §3.1: "very late wake").
        fx.source.upsert(hcSession("late", fx.zone, today.atTime(4, 0), today.atTime(14, 30)))
        assertThat(manager.sync(SyncTrigger.APP_OPEN).lastNightArrived).isTrue()
        assertThat(fx.notifier.arrived).containsExactly(today)
    }

    @Test
    fun demoModeTurnedOffDuringASync_doesNotBringDemoRowsBack() = runTest {
        fx.demoMode.setDemoMode(true)
        // The sync reads "demo on", then the user turns demo mode off before the sync extends the demo nights.
        val racing = object : SettingsRepository by fx.settings {
            override suspend fun current(): AppSettings = fx.settings.current().also { fx.demoMode.setDemoMode(false) }
        }

        val result = fx.manager(backgroundScope, settingsRepository = racing).sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(fx.settings.current().demoMode).isFalse()
        assertThat(fx.db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)
        assertThat(fx.db.vitalDao().getAll()).isEmpty()
    }

    @Test
    fun lastNightArrival_notifiesOnce() = runTest {
        fx.source.seed(night(today.minusDays(1)))
        val manager = fx.manager(backgroundScope)

        assertThat(manager.sync(SyncTrigger.APP_OPEN).lastNightArrived).isFalse()
        assertThat(fx.notifier.arrived).isEmpty()

        fx.source.upsert(night(today))
        assertThat(manager.sync(SyncTrigger.PERIODIC).lastNightArrived).isTrue()
        assertThat(fx.notifier.arrived).containsExactly(today)

        assertThat(manager.sync(SyncTrigger.APP_OPEN).lastNightArrived).isFalse()
        assertThat(fx.notifier.arrived).containsExactly(today)
    }

    @Test
    fun lastNight_onlyCountsTheSelectedSourceAndNotNaps() = runTest {
        fx.time.setLocal(today.atTime(16, 0))
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)

        fx.source.upsert(night(today, origin = OTHER))
        fx.source.upsert(hcSession("nap", fx.zone, today.atTime(13, 0), today.atTime(13, 40)))
        fx.source.upsert(hcSession("yesterday", fx.zone, today.minusDays(2).atTime(23, 0), today.minusDays(1).atTime(7, 0)))
        val result = manager.sync(SyncTrigger.APP_OPEN)

        assertThat(result.lastNightArrived).isFalse()
        assertThat(fx.notifier.arrived).isEmpty()

        fx.settings.setSource(SourceSelection.All)
        fx.source.upsert(night(today, origin = OTHER).copy(title = "edited"))
        // With "all sources" the other app's night was already there before this sync: not a new arrival.
        assertThat(manager.sync(SyncTrigger.APP_OPEN).lastNightArrived).isFalse()
        assertThat(manager.isWaitingForLastNight()).isFalse()
    }

    @Test
    fun demoMode_isANoOpForHealthConnect() = runTest {
        fx.settings.setDemoModeFlag(true, fx.time.now)

        val result = fx.manager(backgroundScope).sync(SyncTrigger.APP_OPEN)

        assertThat(result.success).isTrue()
        assertThat(result.error).isNull()
        assertThat(result.lastNightArrived).isFalse()
        assertThat(fx.source.calls).isEmpty()
        assertThat(fx.gateway.statusCalls).isEqualTo(0)
        assertThat(fx.notifier.arrived).isEmpty()
        assertThat(fx.notifier.fallbackReminders).isEmpty()
        assertThat(fx.store.current().changesToken).isNull()
    }

    @Test
    fun unavailableHealthConnect_isReturnedNotThrown() = runTest {
        val manager = fx.manager(backgroundScope)
        val expected = mapOf(
            HealthConnectStatus.NOT_INSTALLED to SyncError.HEALTH_CONNECT_NOT_INSTALLED,
            HealthConnectStatus.NOT_SUPPORTED to SyncError.HEALTH_CONNECT_NOT_INSTALLED,
            HealthConnectStatus.UPDATE_REQUIRED to SyncError.HEALTH_CONNECT_UPDATE_REQUIRED,
        )
        for ((status, error) in expected) {
            fx.gateway.status = status
            val result = manager.sync(SyncTrigger.MANUAL)
            assertThat(result.success).isFalse()
            assertThat(result.error).isEqualTo(error)
            assertThat(manager.state.value.lastError).isEqualTo(error)
            assertThat(fx.store.current().lastError).isEqualTo(error)
        }
        assertThat(fx.source.calls).isEmpty()
    }

    @Test
    fun missingSleepPermission_isPermissionDenied() = runTest {
        fx.gateway.granted = setOf(HealthConnectPermissions.STEPS)

        val result = fx.manager(backgroundScope).sync(SyncTrigger.APP_OPEN)

        assertThat(result.error).isEqualTo(SyncError.PERMISSION_DENIED)
        assertThat(fx.source.calls).isEmpty()
    }

    @Test
    fun backgroundTrigger_needsBackgroundPermissionWhileAppIsHidden() = runTest {
        val manager = fx.manager(backgroundScope)
        fx.foreground = false

        val hidden = manager.sync(SyncTrigger.PERIODIC)
        assertThat(hidden.error).isEqualTo(SyncError.BACKGROUND_NOT_ALLOWED)
        assertThat(fx.source.calls).isEmpty()
        // Expected without the permission, so it isn't remembered as a failure.
        assertThat(manager.state.value.lastError).isNull()

        assertThat(manager.sync(SyncTrigger.MANUAL).success).isTrue()

        fx.gateway.granted = setOf(HealthConnectPermissions.SLEEP, HealthConnectPermissions.BACKGROUND)
        assertThat(manager.sync(SyncTrigger.RETRY).success).isTrue()

        fx.gateway.granted = setOf(HealthConnectPermissions.SLEEP)
        fx.foreground = true
        assertThat(manager.sync(SyncTrigger.PERIODIC).success).isTrue()
    }

    @Test
    fun exceptions_areClassifiedAndNeverThrown() = runTest {
        val manager = fx.manager(backgroundScope)
        val cases = listOf(
            SecurityException("no access") to SyncError.PERMISSION_DENIED,
            RemoteException("Rate limited request quota has been exceeded") to SyncError.RATE_LIMITED,
            IOException("API call quota exceeded, rate limit") to SyncError.RATE_LIMITED,
            IOException("binder died") to SyncError.UNKNOWN,
            IllegalStateException("boom") to SyncError.UNKNOWN,
        )
        for ((exception, error) in cases) {
            fx.source.failWith = exception
            val result = manager.sync(SyncTrigger.APP_OPEN)
            assertThat(result.success).isFalse()
            assertThat(result.error).isEqualTo(error)
            assertThat(manager.state.value.lastError).isEqualTo(error)
            assertThat(manager.state.value.status).isEqualTo(SyncStatus.Idle)
        }

        fx.source.failWith = null
        assertThat(manager.sync(SyncTrigger.APP_OPEN).success).isTrue()
        assertThat(manager.state.value.lastError).isNull()
        assertThat(fx.store.current().lastError).isNull()
    }

    @Test
    fun foregroundSyncWithoutBackgroundPermission_schedulesFallbackReminder() = runTest {
        val manager = fx.manager(backgroundScope)

        // 09:00, typical wake 07:00: this morning's 07:45 has passed, so tomorrow's.
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.notifier.fallbackReminders.last())
            .isEqualTo(today.plusDays(1).atTime(7, 45).atZone(fx.zone).toInstant())

        // Opened before this morning's reminder and last night isn't in: remind this morning.
        fx.time.setLocal(today.atTime(6, 0))
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.notifier.fallbackReminders.last()).isEqualTo(today.atTime(7, 45).atZone(fx.zone).toInstant())

        // Background syncs and granted background reads don't need it.
        val count = fx.notifier.fallbackReminders.size
        manager.sync(SyncTrigger.PERIODIC)
        fx.gateway.granted = setOf(HealthConnectPermissions.SLEEP, HealthConnectPermissions.BACKGROUND)
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.notifier.fallbackReminders).hasSize(count)
    }

    @Test
    fun missingLastNight_startsRetryChainInTheMorningOnly() = runTest {
        val manager = fx.manager(backgroundScope)

        manager.sync(SyncTrigger.APP_OPEN) // 09:00, nothing yet
        assertThat(fx.scheduler.retries).isEqualTo(1)
        assertThat(manager.isWaitingForLastNight()).isTrue()

        manager.sync(SyncTrigger.RETRY) // the chain retries itself through WorkManager
        assertThat(fx.scheduler.retries).isEqualTo(1)

        fx.time.setLocal(today.atTime(3, 0)) // long before the expected wake
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.scheduler.retries).isEqualTo(1)

        fx.time.setLocal(today.atTime(14, 30)) // gave up waiting
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.scheduler.retries).isEqualTo(1)
        assertThat(manager.isWaitingForLastNight()).isFalse()

        fx.time.setLocal(today.atTime(9, 0))
        fx.source.upsert(night(today))
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.scheduler.retries).isEqualTo(1)
        assertThat(manager.isWaitingForLastNight()).isFalse()
    }

    @Test
    fun newlyGrantedChangeFeedType_triggersReimport() = runTest {
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)
        fx.source.calls.clear()

        fx.gateway.granted = setOf(HealthConnectPermissions.SLEEP, HealthConnectPermissions.RESTING_HEART_RATE)
        manager.sync(SyncTrigger.APP_OPEN)

        assertThat(fx.source.calls.first()).isEqualTo("token")
        assertThat(fx.store.current().tokenScope)
            .containsExactly(HealthConnectPermissions.SLEEP, HealthConnectPermissions.RESTING_HEART_RATE)
    }

    @Test
    fun resetSyncState_forgetsTokenAndTimestamps() = runTest {
        val manager = fx.manager(backgroundScope)
        manager.sync(SyncTrigger.APP_OPEN)

        manager.resetSyncState()

        assertThat(fx.store.current()).isEqualTo(SyncStateSnapshot())
        assertThat(manager.state.value.lastSuccessAt).isNull()
        fx.source.calls.clear()
        manager.sync(SyncTrigger.APP_OPEN)
        assertThat(fx.source.calls.first()).isEqualTo("token")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun syncsNeverOverlap() = runTest {
        val manager = fx.manager(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        fx.source.readGate = gate

        val first = async { manager.sync(SyncTrigger.APP_OPEN) }
        val second = async { manager.sync(SyncTrigger.MANUAL) }
        while (fx.source.calls.isEmpty() || fx.source.calls.last() != "read") yield()
        runCurrent()
        assertThat(fx.source.calls).containsExactly("token", "read").inOrder()
        assertThat(manager.state.value.status).isInstanceOf(SyncStatus.Importing::class.java)

        fx.source.readGate = null
        gate.complete(Unit)
        assertThat(first.await().success).isTrue()
        assertThat(second.await().success).isTrue()
        assertThat(fx.source.calls.last()).isEqualTo("changes:token-1")
    }

    @Test
    fun savedStateIsLoadedAtStart() = runTest {
        fx.store.recordSuccess(fx.time.now.minusSeconds(3600))
        fx.store.recordError(SyncError.RATE_LIMITED)

        val manager = fx.manager(backgroundScope)
        while (manager.state.value.lastError == null) yield()

        assertThat(manager.state.value.lastError).isEqualTo(SyncError.RATE_LIMITED)
        assertThat(manager.state.value.lastSuccessAt).isEqualTo(fx.time.now.minusSeconds(3600))
    }

    private companion object {
        const val GARMIN = "com.garmin.android.apps.connectmobile"
        const val OTHER = "com.example.othertracker"

        /** The framework exception's constructors are hidden from the SDK stubs but public at runtime. */
        fun frameworkException(errorCode: Int): Exception = HealthConnectException::class.java
            .getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(errorCode, "error $errorCode")
    }
}

package com.sleepsaver.feature.onboarding.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeDemoModeController
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.onboarding.connect.ConnectStage
import com.sleepsaver.feature.onboarding.connect.SourceCheck
import com.sleepsaver.feature.onboarding.testing.FakeTimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectSetupViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Env(
        val gateway: FakeHealthConnectGateway = FakeHealthConnectGateway(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) {
        val sync = FakeSyncRepository()
        val demo = FakeDemoModeController(settings)
        val time = FakeTimeProvider()
        val viewModel by lazy { HealthConnectSetupViewModel(gateway, settings, sync, demo, time) }
        val state get() = viewModel.uiState.value
    }

    @Test
    fun `everything fine shows connected with Garmin sharing`() {
        val env = Env()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.CONNECTED)
        assertThat(env.state.connection.source).isEqualTo(SourceCheck.Sharing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE)))
        assertThat(env.state.now).isEqualTo(SampleData.now)
        assertThat(env.state.zone).isEqualTo(SampleData.zone)
    }

    @Test
    fun `every Health Connect status maps to its stage`() {
        val expected = mapOf(
            HealthConnectStatus.NOT_INSTALLED to ConnectStage.NOT_INSTALLED,
            HealthConnectStatus.UPDATE_REQUIRED to ConnectStage.UPDATE_REQUIRED,
            HealthConnectStatus.NOT_SUPPORTED to ConnectStage.NOT_SUPPORTED,
            HealthConnectStatus.AVAILABLE to ConnectStage.CONNECTED,
        )
        expected.forEach { (status, stage) ->
            val env = Env(gateway = FakeHealthConnectGateway(status = status))
            assertThat(env.state.connection.stage).isEqualTo(stage)
        }
    }

    @Test
    fun `revoked sleep access asks again and syncs once granted`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.NEEDS_PERMISSION)

        env.gateway.permissions = FakeHealthConnectGateway.ALL_GRANTED
        env.viewModel.onPermissionsResult()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.CONNECTED)
        assertThat(env.sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `still refused means denied and no sync`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))

        env.viewModel.onPermissionsResult()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.DENIED)
        assertThat(env.sync.syncCalls).isEmpty()
    }

    @Test
    fun `resume re-reads everything`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.UPDATE_REQUIRED))
        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.UPDATE_REQUIRED)

        env.gateway.status = HealthConnectStatus.AVAILABLE
        env.time.now = SampleData.now.plusSeconds(600)
        env.viewModel.refresh()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.CONNECTED)
        assertThat(env.state.now).isEqualTo(SampleData.now.plusSeconds(600))
    }

    @Test
    fun `missing Garmin data offers the other apps`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = listOf("com.ouraring.oura")))

        assertThat(env.state.connection.source).isEqualTo(
            SourceCheck.Missing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE), listOf("com.ouraring.oura")),
        )
        env.viewModel.useSource(SourceSelection.Single("com.ouraring.oura"))

        assertThat(env.settings.state.value.sleep.source).isEqualTo(SourceSelection.Single("com.ouraring.oura"))
        assertThat(env.state.connection.source).isInstanceOf(SourceCheck.Sharing::class.java)
    }

    @Test
    fun `sync now runs a manual sync`() {
        val env = Env()

        env.viewModel.syncNow()

        assertThat(env.sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `demo can be switched on and back off`() {
        val env = Env(settings = FakeSettingsRepository(AppSettings(demoMode = false)))

        env.viewModel.exploreDemo()
        assertThat(env.state.connection.demoMode).isTrue()

        env.viewModel.leaveDemo()
        assertThat(env.demo.calls).containsExactly(true, false).inOrder()
        assertThat(env.state.connection.demoMode).isFalse()
        assertThat(env.state.busy).isFalse()
        assertThat(env.sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `intents never crash`() {
        val env = Env(gateway = FakeHealthConnectGateway(garminInstalled = false))

        assertThat(env.viewModel.installOrUpdateIntent()).isNotNull()
        assertThat(env.viewModel.manageDataIntent()).isNotNull()
        assertThat(env.viewModel.garminConnectIntent()).isNotNull()
    }
}

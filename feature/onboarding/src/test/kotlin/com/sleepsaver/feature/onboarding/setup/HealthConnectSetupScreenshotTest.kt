package com.sleepsaver.feature.onboarding.setup

import androidx.compose.foundation.ScrollState
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.feature.onboarding.Previews
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import org.junit.Test
import java.time.Duration

class HealthConnectSetupScreenshotTest : ScreenshotTest() {

    private val synced = SyncState(lastSuccessAt = SampleData.now.minus(Duration.ofMinutes(12)))

    private fun state(connection: ConnectionUiState, sync: SyncState = synced) =
        HealthConnectSetupUiState(connection = connection, sync = sync, now = SampleData.now, zone = SampleData.zone)

    private fun capture(name: String, state: HealthConnectSetupUiState, scrolledToEnd: Boolean = false) = captureScreen(name) {
        HealthConnectSetupContent(
            state = state,
            actions = SetupActions(),
            scrollState = if (scrolledToEnd) ScrollState(Int.MAX_VALUE) else ScrollState(0),
        )
    }

    @Test fun connected() = capture("setup_connected", state(Previews.connected))

    @Test fun notInstalled() = capture(
        "setup_not_installed",
        state(ConnectionUiState(status = HealthConnectStatus.NOT_INSTALLED), SyncState(lastError = SyncError.HEALTH_CONNECT_NOT_INSTALLED)),
    )

    @Test fun notSupported() = capture(
        "setup_not_supported",
        state(ConnectionUiState(status = HealthConnectStatus.NOT_SUPPORTED), SyncState()),
    )

    @Test fun permissionDenied() = capture(
        "setup_permission_denied",
        state(
            Previews.needsPermission.copy(permissionAsked = true),
            SyncState(lastSuccessAt = SampleData.now.minus(Duration.ofDays(3)), lastError = SyncError.PERMISSION_DENIED),
        ),
    )

    @Test fun garminMissing() = capture("setup_garmin_missing", state(Previews.garminMissing))

    @Test fun garminMissingBottom() = capture("setup_garmin_missing_bottom", state(Previews.garminMissing), scrolledToEnd = true)

    @Test fun demo() = capture(
        "setup_demo",
        state(Previews.connected.copy(demoMode = true, permissions = FakeHealthConnectGateway.ALL_GRANTED)),
    )
}

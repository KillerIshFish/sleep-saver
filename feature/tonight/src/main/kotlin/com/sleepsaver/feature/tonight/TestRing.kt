package com.sleepsaver.feature.tonight

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.formatClockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId

/** What "Test ring in 1 minute" did, for the snackbar. */
internal sealed interface TestRingEvent {
    /** "Test ring set for 9:14 PM"; [at] is null during the no-clock night: "Test ring set". */
    data class Set(val at: Instant?, val zone: ZoneId) : TestRingEvent

    /** "A ring is already set or sounding" (or notifications are blocked). */
    data object Refused : TestRingEvent
}

/**
 * Asks for a test ring in `alarm.testRingDelay`. During the no-clock [night] the answer carries no time, since
 * "set for 9:14 PM" is the clock a minute from now.
 */
internal fun NightController.requestTestRing(now: Instant, zone: ZoneId, night: Boolean, config: SleepConfig = SleepConfig.DEFAULT): TestRingEvent {
    if (!testRing()) return TestRingEvent.Refused
    val at = state.value.testPendingAt ?: (now + config.alarm.testRingDelay)
    return TestRingEvent.Set(at.takeUnless { night }, zone)
}

/** Shows each [TestRingEvent] from [events] in [snackbar]. */
@Composable
internal fun TestRingMessages(events: Flow<TestRingEvent>, snackbar: SnackbarHostState) {
    val setAt = stringResource(R.string.tonight_test_set)
    val setNight = stringResource(R.string.tonight_test_set_night)
    val refused = stringResource(R.string.tonight_ring_refused)
    val use24h = LocalUse24HourClock.current
    LaunchedEffect(events) {
        events.collect { event ->
            val message = when (event) {
                is TestRingEvent.Set -> event.at?.let { setAt.format(formatClockTime(it, event.zone, use24h)).keepTimesTogether() } ?: setNight
                TestRingEvent.Refused -> refused
            }
            snackbar.showSnackbar(message)
        }
    }
}

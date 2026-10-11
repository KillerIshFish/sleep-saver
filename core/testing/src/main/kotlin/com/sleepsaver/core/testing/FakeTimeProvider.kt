package com.sleepsaver.core.testing

import com.sleepsaver.core.data.hooks.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A settable clock for ViewModel and screenshot tests. Defaults to [SampleData.now] in [SampleData.zone].
 * [advance] moves the clock and emits on [minuteTicks]; assigning [now] directly doesn't emit.
 */
class FakeTimeProvider(var now: Instant = SampleData.now, var zone: ZoneId = SampleData.zone) : TimeProvider {
    val ticks = MutableStateFlow(now)

    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> = ticks

    fun advance(d: Duration) {
        now += d
        ticks.value = now
    }
}

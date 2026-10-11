package com.sleepsaver.core.alarm.di

import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.WakeStyle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage-2 placeholder bindings for the alarm hooks, so the Hilt graph builds before core:alarm is implemented: an
 * AlarmScheduler that arms nothing and a NightController that stays idle. B4 deletes this file when it adds
 * AlarmModule with the real implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StubAlarmModule {
    @Binds
    abstract fun alarmScheduler(impl: StubAlarmScheduler): AlarmScheduler

    @Binds
    abstract fun nightController(impl: StubNightController): NightController
}

internal class StubAlarmScheduler @Inject constructor() : AlarmScheduler {
    override suspend fun reschedule() = Unit
    override fun cancelAll() = Unit
}

@Singleton
internal class StubNightController @Inject constructor() : NightController {
    override val state: StateFlow<NightState> = MutableStateFlow(NightState()).asStateFlow()
    override suspend fun dropAnchor(source: AnchorSource, wholeNightLog: Boolean): Long? = null
    override suspend fun undoAnchor(anchorId: Long) = Unit
    override fun endNight() = Unit
    override fun resumeMovementLog() = Unit
    override fun startHush(color: HushColor, timer: Duration?, volume: Float) = Unit
    override fun extendHush(by: Duration) = Unit
    override fun setHushVolume(volume: Float) = Unit
    override fun stopHush() = Unit
    override fun startCatnap(length: Duration): Boolean = false
    override fun cancelCatnap() = Unit
    override fun testRing(): Boolean = false
    override fun snooze() = Unit
    override fun stopRinging() = Unit
    override fun recordMorningFeel(wakeDate: LocalDate, feel: MorningFeel) = Unit
    override fun previewTone(tone: AlarmTone, style: WakeStyle, volume: Float) = Unit
    override fun stopPreview() = Unit
    override fun stopEverything() = Unit
}

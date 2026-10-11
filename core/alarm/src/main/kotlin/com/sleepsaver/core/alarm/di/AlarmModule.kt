package com.sleepsaver.core.alarm.di

import com.sleepsaver.core.alarm.AlarmSchedulerImpl
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.night.DeviceHushOutputs
import com.sleepsaver.core.alarm.night.DeviceMotionSensors
import com.sleepsaver.core.alarm.night.HushOutputs
import com.sleepsaver.core.alarm.night.MotionSensors
import com.sleepsaver.core.alarm.ring.DeviceRingOutputs
import com.sleepsaver.core.alarm.ring.RingOutputs
import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.NightController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the alarm hooks to core:alarm's implementations, and the device outputs the services use. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AlarmModule {
    @Binds
    abstract fun alarmScheduler(impl: AlarmSchedulerImpl): AlarmScheduler

    @Binds
    abstract fun nightController(impl: NightControllerImpl): NightController

    @Binds
    abstract fun ringOutputs(impl: DeviceRingOutputs): RingOutputs

    @Binds
    abstract fun motionSensors(impl: DeviceMotionSensors): MotionSensors

    @Binds
    abstract fun hushOutputs(impl: DeviceHushOutputs): HushOutputs
}

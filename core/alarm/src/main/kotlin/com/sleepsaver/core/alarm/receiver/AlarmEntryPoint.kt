package com.sleepsaver.core.alarm.receiver

import android.content.Context
import com.sleepsaver.core.alarm.AlarmCoordinator
import com.sleepsaver.core.alarm.AlarmSchedulerImpl
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.mirror.AlarmMirror
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.TimeProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/** What core:alarm's receivers take from the Hilt graph. Direct-boot safe (SPEC_V2 §4.9). */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AlarmEntryPoint {
    fun coordinator(): AlarmCoordinator
    fun mirror(): AlarmMirror
    fun scheduler(): dagger.Lazy<AlarmSchedulerImpl>
    fun nightController(): NightControllerImpl

    @ApplicationScope
    fun scope(): CoroutineScope
    fun time(): TimeProvider
}

internal fun Context.alarmEntryPoint(): AlarmEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AlarmEntryPoint::class.java)

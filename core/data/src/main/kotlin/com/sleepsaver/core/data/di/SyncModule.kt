package com.sleepsaver.core.data.di

import com.sleepsaver.core.data.demo.DemoModeControllerImpl
import com.sleepsaver.core.data.demo.FakeSleepDataSource
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectGatewayImpl
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.source.HealthConnectSleepDataSource
import com.sleepsaver.core.data.source.SleepDataSource
import com.sleepsaver.core.data.sync.AppVisibility
import com.sleepsaver.core.data.sync.ProcessAppVisibility
import com.sleepsaver.core.data.sync.SyncManager
import com.sleepsaver.core.data.sync.SyncWorkScheduler
import com.sleepsaver.core.data.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/** The Health Connect [SleepDataSource]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HealthConnectSource

/** The synthetic demo-mode [SleepDataSource]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoSource

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun bindHealthConnectGateway(impl: HealthConnectGatewayImpl): HealthConnectGateway

    @Binds
    abstract fun bindSyncRepository(impl: SyncManager): SyncRepository

    @Binds
    abstract fun bindDemoModeController(impl: DemoModeControllerImpl): DemoModeController

    @Binds
    @HealthConnectSource
    abstract fun bindHealthConnectSource(impl: HealthConnectSleepDataSource): SleepDataSource

    @Binds
    @DemoSource
    abstract fun bindDemoSource(impl: FakeSleepDataSource): SleepDataSource

    @Binds
    abstract fun bindSyncWorkScheduler(impl: WorkManagerSyncScheduler): SyncWorkScheduler

    @Binds
    abstract fun bindAppVisibility(impl: ProcessAppVisibility): AppVisibility
}

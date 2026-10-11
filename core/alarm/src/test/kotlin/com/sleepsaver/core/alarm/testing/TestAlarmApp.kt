package com.sleepsaver.core.alarm.testing

import android.app.Application
import com.sleepsaver.core.alarm.AlarmCoordinator
import com.sleepsaver.core.alarm.AlarmSchedulerImpl
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.mirror.AlarmMirror
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.night.NightService_GeneratedInjector
import com.sleepsaver.core.alarm.receiver.AlarmEntryPoint
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.ring.AlarmRingService_GeneratedInjector
import com.sleepsaver.core.data.hooks.TimeProvider
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.internal.builders.ServiceComponentBuilder
import dagger.hilt.android.internal.managers.ServiceComponentManager
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.CoroutineScope

/**
 * The Robolectric application of core:alarm's tests (robolectric.properties). It stands in for Hilt's generated
 * application component with [AlarmTestGraph]: receivers get their [AlarmEntryPoint] from it, and the
 * `@AndroidEntryPoint` services are injected from it, so production code runs unchanged.
 */
class TestAlarmApp : Application(), GeneratedComponentManager<Any> {
    override fun generatedComponent(): Any = TestComponent(checkNotNull(graph) { "Set TestAlarmApp.graph in @Before" })

    internal companion object {
        @Volatile var graph: AlarmTestGraph? = null
    }
}

/** The "application component": the entry point and the service component builder. */
internal class TestComponent(private val graph: AlarmTestGraph) :
    AlarmEntryPoint,
    ServiceComponentManager.ServiceComponentBuilderEntryPoint {
    override fun coordinator(): AlarmCoordinator = graph.coordinator
    override fun mirror(): AlarmMirror = graph.mirror
    override fun scheduler(): dagger.Lazy<AlarmSchedulerImpl> = graph.schedulerLazy
    override fun nightController(): NightControllerImpl = graph.nightController
    override fun scope(): CoroutineScope = graph.scope
    override fun time(): TimeProvider = graph.time

    override fun serviceComponentBuilder(): ServiceComponentBuilder = object : ServiceComponentBuilder {
        override fun service(service: android.app.Service): ServiceComponentBuilder = this
        override fun build(): ServiceComponent = TestServiceComponent(graph)
    }
}

/** Injects the two services the way Hilt would. */
internal class TestServiceComponent(private val graph: AlarmTestGraph) :
    ServiceComponent,
    AlarmRingService_GeneratedInjector,
    NightService_GeneratedInjector {
    override fun injectAlarmRingService(alarmRingService: AlarmRingService) = graph.inject(alarmRingService)
    override fun injectNightService(nightService: NightService) = graph.inject(nightService)
}

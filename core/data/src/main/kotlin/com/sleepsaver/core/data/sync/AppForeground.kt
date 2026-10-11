package com.sleepsaver.core.data.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether an activity of this process is started, as a [Lifecycle]: STARTED while at least one is, CREATED otherwise
 * (ON_START when the first one starts, ON_STOP when the last one stops other than for a configuration change).
 * Application.onCreate calls [install].
 *
 * Why not ProcessLifecycleOwner: androidx.startup attaches it from its InitializationProvider, which isn't direct-boot
 * aware. In a process that started before the first unlock (the alarm ringing over the lock screen) it is attached
 * only at the unlock, after the ringing screen started. It then sees that screen stop but never start, its count drops
 * below zero, and it never reports ON_START again in that process: no app-open sync for the rest of its life.
 * Application.onCreate runs before any activity, also before the unlock, so this one sees every start.
 */
@Singleton
class AppForeground @Inject constructor() : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var started = 0
    private var installed = false

    @Volatile private var foreground = false

    override val lifecycle: Lifecycle get() = registry

    /** True while an activity of the app is started. Safe from any thread. */
    val isInForeground: Boolean get() = foreground

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            if (!foreground) {
                foreground = true
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            // A rotation stops the activity and starts its new instance right after: that isn't leaving the app.
            if (started == 0 && foreground && !activity.isChangingConfigurations) {
                foreground = false
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Main thread, in Application.onCreate (before any activity exists). Idempotent. */
    fun install(application: Application) {
        if (installed) return
        installed = true
        registry.currentState = Lifecycle.State.CREATED
        application.registerActivityLifecycleCallbacks(callbacks)
    }
}

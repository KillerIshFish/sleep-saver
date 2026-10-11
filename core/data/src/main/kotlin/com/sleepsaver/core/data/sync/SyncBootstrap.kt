package com.sleepsaver.core.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs whenever the app comes to the foreground ([AppForeground]) and keeps the periodic job enqueued. Call [start]
 * once the user is unlocked; an activity already started then (the ringing screen after a reboot) counts as an app open.
 */
@Singleton
class SyncBootstrap @Inject constructor(
    private val syncRepository: SyncRepository,
    @ApplicationScope private val scope: CoroutineScope,
    private val appForeground: AppForeground,
) {
    private val started = AtomicBoolean(false)

    /** Idempotent. Must be called on the main thread. */
    fun start() = start(appForeground.lifecycle)

    internal fun start(lifecycle: Lifecycle) {
        if (!started.compareAndSet(false, true)) return
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    syncRepository.ensurePeriodicSync()
                    scope.launch { syncRepository.sync(SyncTrigger.APP_OPEN) }
                }
            },
        )
    }
}

package com.sleepsaver.core.data.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject

/** Whether any activity of the app is visible: Health Connect allows foreground reads without the background permission. */
fun interface AppVisibility {
    fun isInForeground(): Boolean
}

class ProcessAppVisibility @Inject constructor() : AppVisibility {
    override fun isInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

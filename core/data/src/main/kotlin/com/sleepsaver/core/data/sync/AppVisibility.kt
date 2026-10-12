package com.sleepsaver.core.data.sync

import javax.inject.Inject

/** Whether any activity of the app is visible: Health Connect allows foreground reads without the background permission. */
fun interface AppVisibility {
    fun isInForeground(): Boolean
}

/** Reads [AppForeground] (not ProcessLifecycleOwner, which can lose count in a process started before the unlock). */
class ProcessAppVisibility @Inject constructor(private val appForeground: AppForeground) : AppVisibility {
    override fun isInForeground(): Boolean = appForeground.isInForeground
}

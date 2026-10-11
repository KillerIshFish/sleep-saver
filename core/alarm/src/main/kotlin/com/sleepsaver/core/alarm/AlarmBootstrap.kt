package com.sleepsaver.core.alarm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the alarm machinery from SleepSaverApplication. Stage-2 skeleton: both calls do nothing yet. B4 replaces the
 * bodies (and the constructor) per SPEC_V2 §4.9: [startLocked] is safe before the first unlock (channels, reconcile,
 * re-arm from the device-protected mirror); [startUnlocked] runs once after unlock (flush buffered events, reschedule,
 * then follow the plan).
 */
@Singleton
class AlarmBootstrap @Inject constructor(@ApplicationContext context: Context) {

    /** Before or after unlock. Must never touch credential-encrypted storage. */
    fun startLocked() {}

    /** After unlock, once. */
    fun startUnlocked() {}
}

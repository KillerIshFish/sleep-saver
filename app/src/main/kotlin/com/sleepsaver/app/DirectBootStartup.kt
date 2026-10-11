package com.sleepsaver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Application's direct-boot start order (SPEC_V2 §4.9), apart from Hilt so it can be tested with stand-ins
 * (ApplicationDirectBootTest).
 *
 * [start] runs [locked] at once: it may only touch device-protected storage, because after a reboot the process can
 * start before the first unlock. [unlocked] (everything that needs credential-encrypted storage: Room, DataStore,
 * normal SharedPreferences) runs once, on the main thread, as soon as the user is unlocked: at once, or on
 * ACTION_USER_UNLOCKED. The unlock state is checked again after registering, in case the unlock came in between.
 */
internal class DirectBootStartup(
    private val context: Context,
    private val locked: () -> Unit,
    private val unlocked: () -> Unit,
) {
    private val unlockedStarted = AtomicBoolean(false)

    fun start() {
        locked()
        if (UserManagerCompat.isUserUnlocked(context)) startUnlocked() else startWhenUnlocked()
    }

    private fun startWhenUnlocked() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { this@DirectBootStartup.context.unregisterReceiver(this) }
                startUnlocked()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The unlock may have happened between the check and the registration.
        if (UserManagerCompat.isUserUnlocked(context)) {
            runCatching { context.unregisterReceiver(receiver) }
            startUnlocked()
        }
    }

    /** Main thread, once. */
    private fun startUnlocked() {
        if (!unlockedStarted.compareAndSet(false, true)) return
        unlocked()
    }
}

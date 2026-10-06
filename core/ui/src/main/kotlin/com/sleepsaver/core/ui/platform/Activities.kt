package com.sleepsaver.core.ui.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Starts [intent]; false when nothing on the device can handle it (or it isn't allowed to). */
fun Context.tryStartActivity(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

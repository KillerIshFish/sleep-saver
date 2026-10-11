package com.sleepsaver.core.ui.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
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

/**
 * Opens the first of [intents] that something on the device handles, through [tryStartActivity]; false when none
 * could be opened. Outside an activity (a wrapped context) each intent gets NEW_TASK, which starting one requires.
 */
internal fun Context.tryStartFirst(vararg intents: Intent): Boolean {
    val outsideActivity = findActivity() == null
    return intents.any { intent ->
        if (outsideActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tryStartActivity(intent)
    }
}

/** The activity behind this context (through any wrappers), or null for an application or service context. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

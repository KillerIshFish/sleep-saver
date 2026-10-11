package com.sleepsaver.core.alarm

/**
 * Intent actions, extras, PendingIntent request codes and notification ids of core:alarm (SPEC_V2 §4.1–§4.2).
 * Actions are `com.sleepsaver.core.alarm.<NAME>`; every PendingIntent targets an explicit component and carries only
 * ids and simple extras.
 */
internal object AlarmActions {
    private const val PREFIX = "com.sleepsaver.core.alarm."

    // AlarmReceiver
    const val ACTION_RING = PREFIX + "RING"
    const val ACTION_WINDOW = PREFIX + "WINDOW"
    const val ACTION_STOP_SNOOZED = PREFIX + "STOP_SNOOZED"
    const val ACTION_CANCEL_CATNAP = PREFIX + "CANCEL_CATNAP"
    const val ACTION_HUSH_STOP = PREFIX + "HUSH_STOP"
    const val ACTION_HUSH_EXTEND = PREFIX + "HUSH_EXTEND"

    // AlarmRingService
    const val ACTION_SNOOZE = PREFIX + "SNOOZE"
    const val ACTION_STOP = PREFIX + "STOP"
    const val ACTION_REPOST = PREFIX + "REPOST"

    // NightService
    const val ACTION_LOG_START = PREFIX + "LOG_START"
    const val ACTION_HUSH_START = PREFIX + "HUSH_START"

    const val EXTRA_KIND = "kind"
    const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    const val EXTRA_EPOCH_MS = "epochMs"

    /** EXTRA_KIND values of ACTION_RING. */
    const val KIND_PLAN = "PLAN"
    const val KIND_EXTRA = "EXTRA"
    const val KIND_CATNAP = "CATNAP"

    // PendingIntent request codes (§4.2)
    const val RC_RING_PLAN = 20
    const val RC_WINDOW = 21
    const val RC_SHOW = 22
    const val RC_RING_EXTRA = 23
    const val RC_RING_CATNAP = 24
    const val RC_FULL_SCREEN = 25
    const val RC_SNOOZE = 26
    const val RC_STOP = 27
    const val RC_STOP_SNOOZED = 28
    const val RC_CANCEL_CATNAP = 29
    const val RC_HUSH_STOP = 30
    const val RC_HUSH_EXTEND = 31
    const val RC_NIGHT = 32
    const val RC_REPOST = 33

    // Notification ids (core:notifications uses 1 and 100–108)
    const val ID_RINGING = 300
    const val ID_NIGHT = 301
    const val ID_SNOOZED = 302
    const val ID_RING_FALLBACK = 303
    const val ID_CATNAP = 304
}

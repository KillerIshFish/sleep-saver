package com.sleepsaver.core.alarm.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The alarm's state in device-protected storage (SPEC_V2 §4.3), readable before the first unlock after a reboot, when
 * Room and DataStore aren't. One SharedPreferences key per concern, each holding kotlinx JSON of an internal DTO.
 * Every write is commit(), never apply(): the next broadcast may run in a fresh process.
 *
 * Only [AlarmCoordinator][com.sleepsaver.core.alarm.AlarmCoordinator] writes it (under its lock).
 */
@Singleton
internal class AlarmMirror @Inject constructor(@ApplicationContext private val context: Context) {

    /** Opened on first use; device-protected, so it also opens while the user is locked. */
    private val prefs: SharedPreferences by lazy {
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun read(): MirrorState = MirrorState(
        armed = decode(KEY_ARMED, MirroredAlarm.serializer()),
        upcoming = decode(KEY_UPCOMING, ListSerializer(MirroredAlarm.serializer())).orEmpty(),
        extra = decode(KEY_EXTRA, MirroredExtra.serializer()),
        catnap = decode(KEY_CATNAP, MirroredCatnap.serializer()),
        ringing = decode(KEY_RINGING, MirroredRinging.serializer()),
        night = decode(KEY_NIGHT, MirroredNight.serializer()),
        hush = decode(KEY_HUSH, MirroredHush.serializer()),
        handledIds = decode(KEY_HANDLED, ListSerializer(String.serializer())).orEmpty(),
        pendingEvents = decode(KEY_PENDING_EVENTS, ListSerializer(MirroredEvent.serializer())).orEmpty(),
        pendingFeel = decode(KEY_PENDING_FEEL, MirroredFeel.serializer()),
        pendingLogEnds = decode(KEY_PENDING_LOG_ENDS, ListSerializer(MirroredLogEnd.serializer())).orEmpty(),
        prior = decode(KEY_PRIOR, MirroredPrior.serializer()),
        use24h = prefs.getBoolean(KEY_USE_24H, false),
        generation = prefs.getLong(KEY_GENERATION, 0L),
    )

    /** Writes the keys whose value differs between [old] and [new] in one commit. */
    @SuppressLint("ApplySharedPref") // commit on purpose: the next broadcast may run in a fresh process
    fun write(old: MirrorState, new: MirrorState) {
        val editor = prefs.edit()
        editor.put(KEY_ARMED, old.armed, new.armed, MirroredAlarm.serializer())
        editor.putList(KEY_UPCOMING, old.upcoming, new.upcoming, MirroredAlarm.serializer())
        editor.put(KEY_EXTRA, old.extra, new.extra, MirroredExtra.serializer())
        editor.put(KEY_CATNAP, old.catnap, new.catnap, MirroredCatnap.serializer())
        editor.put(KEY_RINGING, old.ringing, new.ringing, MirroredRinging.serializer())
        editor.put(KEY_NIGHT, old.night, new.night, MirroredNight.serializer())
        editor.put(KEY_HUSH, old.hush, new.hush, MirroredHush.serializer())
        editor.putList(KEY_HANDLED, old.handledIds, new.handledIds, String.serializer())
        editor.putList(KEY_PENDING_EVENTS, old.pendingEvents, new.pendingEvents, MirroredEvent.serializer())
        editor.put(KEY_PENDING_FEEL, old.pendingFeel, new.pendingFeel, MirroredFeel.serializer())
        editor.putList(KEY_PENDING_LOG_ENDS, old.pendingLogEnds, new.pendingLogEnds, MirroredLogEnd.serializer())
        editor.put(KEY_PRIOR, old.prior, new.prior, MirroredPrior.serializer())
        if (old.use24h != new.use24h) editor.putBoolean(KEY_USE_24H, new.use24h)
        editor.putLong(KEY_GENERATION, new.generation)
        editor.commit()
    }

    /** Removes every key (Reset all data). */
    @SuppressLint("ApplySharedPref")
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun <T : Any> decode(key: String, serializer: KSerializer<T>): T? {
        val text = prefs.getString(key, null) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) { // SerializationException is one
            Log.w(TAG, "Unreadable mirror key $key; dropping it", e)
            null
        }
    }

    private fun <T : Any> SharedPreferences.Editor.put(key: String, old: T?, new: T?, serializer: KSerializer<T>) {
        if (old == new) return
        if (new == null) remove(key) else putString(key, json.encodeToString(serializer, new))
    }

    private fun <T : Any> SharedPreferences.Editor.putList(key: String, old: List<T>, new: List<T>, serializer: KSerializer<T>) {
        if (old == new) return
        if (new.isEmpty()) remove(key) else putString(key, json.encodeToString(ListSerializer(serializer), new))
    }

    internal companion object {
        const val PREFS_NAME = "sleepsaver_alarm"
        const val KEY_ARMED = "armed"
        const val KEY_UPCOMING = "upcoming"
        const val KEY_EXTRA = "extra"
        const val KEY_CATNAP = "catnap"
        const val KEY_RINGING = "ringing"
        const val KEY_NIGHT = "night"
        const val KEY_HUSH = "hush"
        const val KEY_HANDLED = "handledIds"
        const val KEY_PENDING_EVENTS = "pendingEvents"
        const val KEY_PENDING_FEEL = "pendingFeel"
        const val KEY_PENDING_LOG_ENDS = "pendingLogEnds"
        const val KEY_PRIOR = "prior"
        const val KEY_USE_24H = "use24h"
        const val KEY_GENERATION = "generation"
        private const val TAG = "SleepSaverAlarm"

        /** Unknown fields (a newer app version's) are ignored; defaults keep older files readable. */
        val json = Json { ignoreUnknownKeys = true }
    }
}

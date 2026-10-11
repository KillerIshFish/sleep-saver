package com.sleepsaver.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.model.HabitType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** In-app destinations. The app's MainActivity handles the `sleepsaver://` scheme. */
internal object DeepLinks {
    fun energy(): Uri = Uri.parse("sleepsaver://energy")
    fun review(): Uri = Uri.parse("sleepsaver://review")
    fun review(wakeDate: LocalDate): Uri = review().buildUpon().appendQueryParameter("wakeDate", wakeDate.toString()).build()
    fun nightSteps(): Uri = Uri.parse(AlarmIntents.DEEP_LINK_NIGHT_STEPS)
    fun tonight(): Uri = Uri.parse(AlarmIntents.DEEP_LINK_TONIGHT)

    /** Where a habit reminder opens: Night steps for the evening ones, Tonight for bedtime, else the energy schedule. */
    fun forHabit(type: HabitType): Uri = when (type) {
        HabitType.EVENING_ROUTINE, HabitType.DIM_LIGHTS -> nightSteps()
        HabitType.BEDTIME -> tonight()
        HabitType.MORNING_LIGHT, HabitType.CAFFEINE_CUTOFF, HabitType.LARGE_MEAL_CUTOFF, HabitType.ALCOHOL_CUTOFF,
        HabitType.EXERCISE_CUTOFF, HabitType.NAP_WINDOW,
        -> energy()
    }
}

/** Stable notification ids: posting again for the same thing replaces the old notification. */
internal object NotificationIds {
    const val REVIEW = 1
    private const val HABIT_BASE = 100
    fun habit(type: HabitType): Int = HABIT_BASE + type.ordinal
}

/** Builds and posts this module's notifications. Posting is skipped silently when the user hasn't allowed notifications. */
@Singleton
class NotificationPoster @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    @Volatile private var channelsCreated = false

    fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** A builder with the app's icon and accent, opening [deepLink] in the app when tapped. */
    internal fun builder(channelId: String, deepLink: Uri): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_sleepsaver)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setAutoCancel(true)
            .setContentIntent(openApp(deepLink))

    /** Returns whether it was posted. */
    @SuppressLint("MissingPermission") // checked by canPost()
    internal fun post(id: Int, notification: Notification): Boolean {
        if (!canPost()) return false
        if (!channelsCreated) {
            NotificationChannels.create(context)
            channelsCreated = true
        }
        manager.notify(id, notification)
        return true
    }

    internal fun cancel(id: Int) = manager.cancel(id)

    internal fun text(resId: Int): String = context.getString(resId)

    internal fun text(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    private fun openApp(uri: Uri): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, uri)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

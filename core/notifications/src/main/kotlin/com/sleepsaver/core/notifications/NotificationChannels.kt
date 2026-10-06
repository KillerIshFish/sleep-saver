package com.sleepsaver.core.notifications

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.sleepsaver.core.domain.model.ReminderCategory

/** The app's notification channels (docs/PLAN.md §3.8). Ids are stable: users' per-channel settings hang off them. */
object NotificationChannels {
    const val REVIEW = "review"
    const val MORNING_LIGHT = "morning_light"
    const val CUTOFFS = "cutoffs"
    const val WIND_DOWN = "wind_down"
    const val BEDTIME = "bedtime"
    const val NAPS = "naps"

    /** Low importance; for progress of long imports. */
    const val SYNC = "sync"

    fun forCategory(category: ReminderCategory): String = when (category) {
        ReminderCategory.MORNING_LIGHT -> MORNING_LIGHT
        ReminderCategory.CUTOFFS -> CUTOFFS
        ReminderCategory.WIND_DOWN -> WIND_DOWN
        ReminderCategory.BEDTIME -> BEDTIME
        ReminderCategory.NAPS -> NAPS
    }

    /** Creates every channel. Idempotent; refreshes names and descriptions (e.g. after a locale change). */
    fun create(context: Context) {
        val default = NotificationManagerCompat.IMPORTANCE_DEFAULT
        val channels = listOf(
            channel(context, REVIEW, R.string.channel_review_name, R.string.channel_review_description, default),
            channel(context, MORNING_LIGHT, R.string.channel_morning_light_name, R.string.channel_morning_light_description, default),
            channel(context, CUTOFFS, R.string.channel_cutoffs_name, R.string.channel_cutoffs_description, default),
            channel(context, WIND_DOWN, R.string.channel_wind_down_name, R.string.channel_wind_down_description, default),
            channel(context, BEDTIME, R.string.channel_bedtime_name, R.string.channel_bedtime_description, default),
            channel(context, NAPS, R.string.channel_naps_name, R.string.channel_naps_description, default),
            channel(context, SYNC, R.string.channel_sync_name, R.string.channel_sync_description, NotificationManagerCompat.IMPORTANCE_LOW),
        )
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }

    private fun channel(context: Context, id: String, @StringRes name: Int, @StringRes description: Int, importance: Int) =
        NotificationChannelCompat.Builder(id, importance)
            .setName(context.getString(name))
            .setDescription(context.getString(description))
            .build()
}

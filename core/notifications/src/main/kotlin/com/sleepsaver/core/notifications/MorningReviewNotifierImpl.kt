package com.sleepsaver.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "last night is in" nudge on the review channel. Posted only while morning reviews are on and the night
 * hasn't been reviewed. Without background reads, a time-based fallback alarm posts a generic nudge instead.
 */
@Singleton
class MorningReviewNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val reviews: ReviewRepository,
    private val time: TimeProvider,
    private val poster: NotificationPoster,
    @ApplicationScope private val scope: CoroutineScope,
) : MorningReviewNotifier {

    private val alarmManager = checkNotNull(context.getSystemService<AlarmManager>())

    override fun onLastNightArrived(wakeDate: LocalDate) {
        // The data is in, so the real nudge replaces the time-based one.
        alarmManager.cancel(fallbackIntent(wakeDate = null))
        scope.launch { notifyIfUnreviewed(wakeDate, fallback = false) }
    }

    override fun scheduleFallbackReminder(at: Instant) {
        val wakeDate = at.atZone(time.zone()).toLocalDate()
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), fallbackIntent(wakeDate))
    }

    override fun cancel() {
        alarmManager.cancel(fallbackIntent(wakeDate = null))
        poster.cancel(NotificationIds.REVIEW)
    }

    internal suspend fun onFallbackAlarm(wakeDate: LocalDate) {
        notifyIfUnreviewed(wakeDate, fallback = true)
    }

    /** Returns whether a notification was posted. */
    internal suspend fun notifyIfUnreviewed(wakeDate: LocalDate, fallback: Boolean): Boolean {
        if (!settings.current().morningReviewEnabled) return false
        if (reviews.review(wakeDate).first() != null) return false
        val (title, body, link) = if (fallback) {
            Triple(R.string.review_fallback_title, R.string.review_fallback_body, DeepLinks.review())
        } else {
            Triple(R.string.review_ready_title, R.string.review_ready_body, DeepLinks.review(wakeDate))
        }
        val notification = poster.builder(NotificationChannels.REVIEW, link)
            .setContentTitle(poster.text(title))
            .setContentText(poster.text(body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(poster.text(body)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .build()
        return poster.post(NotificationIds.REVIEW, notification)
    }

    /** Extras don't take part in PendingIntent identity, so a null [wakeDate] still matches for cancelling. */
    private fun fallbackIntent(wakeDate: LocalDate?): PendingIntent = PendingIntent.getBroadcast(
        context,
        ReminderReceiver.REQUEST_REVIEW_FALLBACK,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REVIEW_FALLBACK)
            .apply { if (wakeDate != null) putExtra(ReminderReceiver.EXTRA_WAKE_DATE, wakeDate.toString()) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

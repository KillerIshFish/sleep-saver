package com.sleepsaver.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "last night is in" nudge on the review channel. Posted only while morning reviews are on, onboarding is done
 * and the night hasn't been reviewed. Without background reads, a time-based fallback alarm posts a generic nudge
 * instead. A fallback already set for a later morning survives this morning's data arriving or being reviewed.
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
    /** Opened on first use: credential-encrypted storage can't be read before the first unlock (direct boot). */
    private val prefs by lazy { context.getSharedPreferences("sleepsaver_review_nudge", Context.MODE_PRIVATE) }

    /**
     * When the armed fallback goes off; persisted because alarms don't survive a reboot or an app update.
     * Written asynchronously: [cancel] may be called from the main thread.
     */
    private var fallbackAt: Instant?
        get() = prefs.getLong(KEY_FALLBACK_AT, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_FALLBACK_AT) else putLong(KEY_FALLBACK_AT, value.toEpochMilli())
        }

    override fun onLastNightArrived(wakeDate: LocalDate) {
        // The data is in, so the real nudge replaces the time-based one.
        cancelFallbackThrough(wakeDate)
        scope.launch {
            try {
                notifyIfUnreviewed(wakeDate, fallback = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Morning review nudge failed", e)
            }
        }
    }

    override fun scheduleFallbackReminder(at: Instant) {
        fallbackAt = at
        armFallback(at)
    }

    override fun cancel() {
        cancelFallbackThrough(today())
        poster.cancel(NotificationIds.REVIEW)
    }

    /** After a reboot or an app update: re-arms a fallback for today or later (one already due fires at once). */
    internal fun restoreFallbackReminder() {
        val at = fallbackAt ?: return
        if (wakeDateOf(at).isBefore(today())) fallbackAt = null else armFallback(at)
    }

    internal suspend fun onFallbackAlarm(wakeDate: LocalDate) {
        cancelFallbackThrough(wakeDate)
        notifyIfUnreviewed(wakeDate, fallback = true)
    }

    /** Returns whether a notification was posted. */
    internal suspend fun notifyIfUnreviewed(wakeDate: LocalDate, fallback: Boolean): Boolean {
        val current = settings.current()
        if (!current.morningReviewEnabled || !current.onboardingDone) return false
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

    /** Cancels the fallback unless it's for a morning after [wakeDate]. */
    private fun cancelFallbackThrough(wakeDate: LocalDate) {
        if (fallbackAt?.let(::wakeDateOf)?.isAfter(wakeDate) == true) return
        alarmManager.cancel(fallbackIntent(wakeDate = null))
        fallbackAt = null
    }

    private fun armFallback(at: Instant) =
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), fallbackIntent(wakeDateOf(at)))

    private fun wakeDateOf(at: Instant): LocalDate = at.atZone(time.zone()).toLocalDate()

    private fun today(): LocalDate = wakeDateOf(time.now())

    /** Extras don't take part in PendingIntent identity, so a null [wakeDate] still matches for cancelling. */
    private fun fallbackIntent(wakeDate: LocalDate?): PendingIntent = PendingIntent.getBroadcast(
        context,
        ReminderReceiver.REQUEST_REVIEW_FALLBACK,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REVIEW_FALLBACK)
            .apply { if (wakeDate != null) putExtra(ReminderReceiver.EXTRA_WAKE_DATE, wakeDate.toString()) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "SleepSaverReminders"
        const val KEY_FALLBACK_AT = "fallback_at_ms"
    }
}

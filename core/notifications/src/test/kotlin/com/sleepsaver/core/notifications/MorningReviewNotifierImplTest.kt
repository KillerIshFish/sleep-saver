@file:OptIn(ExperimentalCoroutinesApi::class)

package com.sleepsaver.core.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.notifications.testing.FakeReviewRepository
import com.sleepsaver.core.notifications.testing.FakeSettingsRepository
import com.sleepsaver.core.notifications.testing.FakeTimeProvider
import com.sleepsaver.core.notifications.testing.TestInsights.TODAY
import com.sleepsaver.core.notifications.testing.TestInsights.at
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class MorningReviewNotifierImplTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private val settings = FakeSettingsRepository()
    private val reviews = FakeReviewRepository()

    private fun TestScope.notifier() = MorningReviewNotifierImpl(
        context, settings, reviews, FakeTimeProvider(at(TODAY, "07:30")), NotificationPoster(context), this,
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `posts the review nudge with a deep link to that night`() = runTest {
        notifier().onLastNightArrived(TODAY)
        advanceUntilIdle()

        val notification = notifications.getNotification(NotificationIds.REVIEW)
        assertThat(notification.channelId).isEqualTo(NotificationChannels.REVIEW)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.review_ready_title))
        assertThat(shadowOf(notification.contentIntent).savedIntent.dataString)
            .isEqualTo("sleepsaver://review?wakeDate=2026-10-06")
    }

    @Test
    fun `no nudge when morning reviews are turned off`() = runTest {
        settings.settings.value = AppSettings(morningReviewEnabled = false)

        notifier().onLastNightArrived(TODAY)
        advanceUntilIdle()

        assertThat(notifications.allNotifications).isEmpty()
    }

    @Test
    fun `no nudge when the night was already reviewed`() = runTest {
        reviews.record(MorningReview(TODAY, ReviewStatus.CONFIRMED, at(TODAY, "07:10"), null, null))

        notifier().onLastNightArrived(TODAY)
        advanceUntilIdle()

        assertThat(notifications.allNotifications).isEmpty()
    }

    @Test
    fun `a review of another night doesn't count`() = runTest {
        reviews.record(MorningReview(TODAY.minusDays(1), ReviewStatus.CONFIRMED, at(TODAY, "07:10"), null, null))

        assertThat(notifier().notifyIfUnreviewed(TODAY, fallback = false)).isTrue()
    }

    @Test
    fun `no nudge without notification permission`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertThat(notifier().notifyIfUnreviewed(TODAY, fallback = false)).isFalse()
        assertThat(notifications.allNotifications).isEmpty()
    }

    @Test
    fun `fallback reminder arms an inexact alarm that posts a generic nudge if still unreviewed`() = runTest {
        val notifier = notifier()

        notifier.scheduleFallbackReminder(at(TODAY, "07:45"))

        val alarm = alarms.scheduledAlarms.single()
        assertThat(alarm.triggerAtMs).isEqualTo(at(TODAY, "07:45").toEpochMilli())
        assertThat(alarm.isAllowWhileIdle).isTrue()
        assertThat(shadowOf(alarm.operation).savedIntent.getStringExtra(ReminderReceiver.EXTRA_WAKE_DATE)).isEqualTo("2026-10-06")

        notifier.onFallbackAlarm(TODAY)

        val notification = notifications.getNotification(NotificationIds.REVIEW)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.review_fallback_title))
        assertThat(shadowOf(notification.contentIntent).savedIntent.dataString).isEqualTo("sleepsaver://review")
    }

    @Test
    fun `fallback stays quiet once the night is reviewed`() = runTest {
        reviews.record(MorningReview(TODAY, ReviewStatus.ADJUSTED, at(TODAY, "07:40"), null, null))

        notifier().onFallbackAlarm(TODAY)

        assertThat(notifications.allNotifications).isEmpty()
    }

    @Test
    fun `data arriving replaces the fallback alarm`() = runTest {
        val notifier = notifier()
        notifier.scheduleFallbackReminder(at(TODAY, "07:45"))

        notifier.onLastNightArrived(TODAY)
        advanceUntilIdle()

        assertThat(alarms.scheduledAlarms).isEmpty()
        assertThat(notifications.allNotifications).hasSize(1)
    }

    @Test
    fun `cancel removes both the fallback alarm and the posted nudge`() = runTest {
        val notifier = notifier()
        notifier.scheduleFallbackReminder(at(TODAY, "07:45"))
        notifier.notifyIfUnreviewed(TODAY, fallback = false)

        notifier.cancel()

        assertThat(alarms.scheduledAlarms).isEmpty()
        assertThat(notifications.allNotifications).isEmpty()
    }
}

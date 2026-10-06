package com.sleepsaver.core.data.time

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SystemTimeProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `minute ticks emit again at once when the clock or zone changes or the screen turns on`() = runTest {
        val provider = SystemTimeProvider(context)
        val ticks = mutableListOf<Instant>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.minuteTicks.collect { ticks += it } }
        runCurrent()
        assertThat(ticks).hasSize(1)

        for ((i, action) in listOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_SCREEN_ON).withIndex()) {
            context.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertWithMessage(action).that(ticks).hasSize(i + 2)
        }
    }

    @Test
    fun `the receiver is unregistered when collection stops`() = runTest {
        val provider = SystemTimeProvider(context)
        val application = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { provider.minuteTicks.collect {} }
        runCurrent()
        val registered = application.registeredReceivers.size

        job.cancel()
        runCurrent()

        assertThat(application.registeredReceivers).hasSize(registered - 1)
    }
}

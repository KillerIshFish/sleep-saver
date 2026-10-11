package com.sleepsaver.app

import android.app.Application
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The Application's direct-boot order (SPEC_V2 §4.9) with a locked user: before unlock only the alarm's locked start
 * runs and no bootstrap Lazy is resolved; the unlock starts each of them exactly once. [DirectBootStartup] runs here
 * with the same steps SleepSaverApplication gives it, over counting Lazies (the real bootstraps need Hilt).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ApplicationDirectBootTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val userManager get() = shadowOf(context.getSystemService(UserManager::class.java))

    private val calls = mutableListOf<String>()

    /** A dagger.Lazy that records when it is resolved and when what it gives is started. */
    private inner class CountingLazy(private val name: String) : dagger.Lazy<Runnable> {
        var gets = 0
        override fun get(): Runnable {
            gets++
            return Runnable { calls += "$name.start" }
        }
    }

    private val sync = CountingLazy("sync")
    private val notifications = CountingLazy("notifications")
    private val widget = CountingLazy("widget")
    private val lazies = listOf(sync, notifications, widget)

    /** SleepSaverApplication.onCreate's start-up, step for step. */
    private fun startup() = DirectBootStartup(
        context = context,
        locked = { calls += "alarm.startLocked" },
        unlocked = {
            sync.get().run()
            notifications.get().run()
            widget.get().run()
            calls += "alarm.startUnlocked"
        },
    )

    private fun unlock() {
        userManager.setUserUnlocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        ShadowLooper.idleMainLooper()
    }

    @Before
    fun locked() {
        userManager.setUserUnlocked(false)
    }

    @Test
    fun `before unlock only the alarm starts, and no bootstrap Lazy is resolved`() {
        startup().start()
        ShadowLooper.idleMainLooper()

        assertThat(calls).containsExactly("alarm.startLocked")
        assertThat(lazies.map { it.gets }).containsExactly(0, 0, 0)
    }

    @Test
    fun `the unlock starts every bootstrap once, then the alarm follows the plan`() {
        startup().start()

        unlock()
        unlock() // a second broadcast changes nothing

        assertThat(calls).containsExactly(
            "alarm.startLocked",
            "sync.start",
            "notifications.start",
            "widget.start",
            "alarm.startUnlocked",
        ).inOrder()
        assertThat(lazies.map { it.gets }).containsExactly(1, 1, 1)
    }

    @Test
    fun `an unlocked user starts everything at once`() {
        userManager.setUserUnlocked(true)

        startup().start()

        assertThat(calls).containsExactly(
            "alarm.startLocked",
            "sync.start",
            "notifications.start",
            "widget.start",
            "alarm.startUnlocked",
        ).inOrder()
    }

    @Test
    fun `the application resolves its bootstraps lazily`() {
        // The fields Hilt fills before onCreate: anything touching credential-encrypted storage is a dagger.Lazy.
        val fields = SleepSaverApplication::class.java.declaredFields.associateBy { it.name }

        listOf("syncBootstrap", "notificationsBootstrap", "widgetUpdater").forEach { name ->
            assertThat(fields.getValue(name).type).isEqualTo(dagger.Lazy::class.java)
        }
    }
}

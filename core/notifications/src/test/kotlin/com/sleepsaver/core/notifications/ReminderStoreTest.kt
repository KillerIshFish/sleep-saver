package com.sleepsaver.core.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReminderStoreTest {

    /** Stands in for a locked user: credential-encrypted preferences throw until [unlocked]. */
    private class LockedContext(base: Context) : ContextWrapper(base) {
        var unlocked = false
        var opens = 0

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            check(unlocked) { "credential-encrypted storage is locked" }
            opens++
            return super.getSharedPreferences(name, mode)
        }
    }

    private val context = LockedContext(ApplicationProvider.getApplicationContext())

    @Test
    fun `creating the store before unlock doesn't open its preferences`() {
        val store = ReminderStore(context)

        assertThat(context.opens).isEqualTo(0)

        context.unlocked = true
        store.postedThrough = Instant.ofEpochMilli(1_000)
        assertThat(store.postedThrough).isEqualTo(Instant.ofEpochMilli(1_000))
        assertThat(context.opens).isEqualTo(1)
    }
}

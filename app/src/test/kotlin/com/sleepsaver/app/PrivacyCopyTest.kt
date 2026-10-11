package com.sleepsaver.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The privacy page says exactly what the app touches (SPEC_V2 §5.5): when the motion sensor runs, and everything the
 * before-unlock file (core:alarm's AlarmMirror) holds, not just the next alarm. A plain Application: only strings.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PrivacyCopyTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the motion sensor starts the sensing lead-in before the surfacing window`() {
        val leadIn = SleepConfig.DEFAULT.surfacing.sensingLeadIn.toMinutes()

        assertThat(context.getString(R.string.privacy_what_body))
            .contains("only from $leadIn minutes before your alarm's surfacing window opens until it rings")
    }

    @Test
    fun `the before-unlock file is described in full`() {
        val body = context.getString(R.string.privacy_where_body)

        assertThat(body).contains("your next alarms and any snooze or catnap")
        assertThat(body).contains("tonight's anchor and Hush settings")
        assertThat(body).contains("a summary of when your watch usually records deep sleep")
        assertThat(body).contains("Morning feel from before you unlock")
    }
}

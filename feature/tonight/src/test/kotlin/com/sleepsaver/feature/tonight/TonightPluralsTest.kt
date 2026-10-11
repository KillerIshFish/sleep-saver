package com.sleepsaver.feature.tonight

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.patterns.DebtPreview
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalTime

/** A count of one reads "1 night" / "1 minute", never "1 nights" / "1 minutes" (the counts come from config). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class TonightPluralsTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = PlanSamples.today

    private fun notEnoughNights(presentNights: Int) = DebtPreview(
        wakeDate = today.plusDays(1),
        asleepBy = PlanSamples.at(today, LocalTime.of(23, 15)),
        projectedDebt = Duration.ZERO,
        change = Duration.ZERO,
        enoughHistory = false,
        presentNights = presentNights,
    )

    @Test
    fun `the debt preview counts the nights it needs`() {
        val oneNight = SleepConfig.DEFAULT.run { copy(anchor = anchor.copy(debtPreviewMinNights = 1)) }
        compose.setContent {
            SleepSaverTheme {
                Column {
                    DebtPreviewChip(notEnoughNights(1), today, PlanSamples.zone)
                    DebtPreviewChip(notEnoughNights(0), today, PlanSamples.zone, config = oneNight)
                }
            }
        }

        compose.onNodeWithText("Debt preview needs 3 nights (1 so far)").assertExists()
        compose.onNodeWithText("Debt preview needs 1 night (0 so far)").assertExists()
    }

    @Test
    fun `the catnap counts the minutes to fall asleep`() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        fun fallAsleep(minutes: Int) = resources.getQuantityString(R.plurals.tonight_catnap_fall_asleep, minutes, minutes)

        assertThat(fallAsleep(5)).isEqualTo("Includes 5 minutes to fall asleep.")
        assertThat(fallAsleep(1)).isEqualTo("Includes 1 minute to fall asleep.")
    }
}

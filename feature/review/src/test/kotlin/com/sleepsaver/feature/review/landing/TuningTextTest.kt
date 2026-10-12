package com.sleepsaver.feature.review.landing

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.testing.PlanSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The tuning note counts the rings that set it off: "after 3 rings", and "after 1 ring" when config says one. */
@RunWith(AndroidJUnit4::class)
class TuningTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val raised = PlanSamples.tuning()
    private val lowered = raised.copy(reason = TuningReason.LOWERED_AFTER_LATE_RINGS)
    private val oneRing = SleepConfig.DEFAULT.run { copy(surfacing = surfacing.copy(tuneRaiseDeepRings = 1, tuneLowerDeadlineRings = 1)) }

    private fun texts(vararg cases: Pair<AlarmTuning, SleepConfig>): List<String?> {
        var texts = emptyList<String?>()
        compose.setContent { texts = cases.map { (tuning, config) -> tuningText(tuning, config) } }
        compose.waitForIdle()
        return texts
    }

    @Test
    fun `the rings are counted, one ring in the singular`() {
        val texts = texts(
            raised to SleepConfig.DEFAULT,
            lowered to SleepConfig.DEFAULT,
            raised to oneRing,
            lowered to oneRing,
        )

        assertThat(texts).containsExactly(
            "Sleep Saver now waits for a clearer restless moment, after 3 rings this week during watch-scored deep sleep.",
            "Sleep Saver now reacts to smaller movements, after 5 rings at your wake time this week while your watch saw lighter sleep.",
            "Sleep Saver now waits for a clearer restless moment, after 1 ring this week during watch-scored deep sleep.",
            "Sleep Saver now reacts to smaller movements, after 1 ring at your wake time this week while your watch saw lighter sleep.",
        ).inOrder()
    }

    @Test
    fun `a reset has no note`() {
        assertThat(texts(raised.copy(reason = TuningReason.RESET) to SleepConfig.DEFAULT)).containsExactly(null)
    }
}

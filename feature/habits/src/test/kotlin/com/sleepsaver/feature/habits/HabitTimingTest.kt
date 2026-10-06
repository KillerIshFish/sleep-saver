package com.sleepsaver.feature.habits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HabitTimingTest {

    @Test
    fun `every default offset sits inside its habit's range and on its step grid`() {
        habitCatalog.all.forEach { definition ->
            val spec = timingSpec(definition.type)
            val value = spec.valueOf(definition.defaultOffset)
            assertThat(spec.offsetOf(value)).isEqualTo(definition.defaultOffset)
            assertThat((value - spec.min) % spec.step).isEqualTo(0)
        }
    }

    @Test
    fun `cutoffs read as positive hours before bedtime`() {
        val spec = timingSpec(HabitType.CAFFEINE_CUTOFF)

        assertThat(spec.valueOf(Duration.ofHours(-10))).isEqualTo(600)
        assertThat(spec.offsetOf(570)).isEqualTo(Duration.ofMinutes(-570))
    }

    @Test
    fun `shifts keep their sign`() {
        val spec = timingSpec(HabitType.NAP_WINDOW)

        assertThat(spec.valueOf(Duration.ofMinutes(-30))).isEqualTo(-30)
        assertThat(spec.offsetOf(45)).isEqualTo(Duration.ofMinutes(45))
        assertThat(spec.offsetOf(500)).isEqualTo(Duration.ofMinutes(60))
    }

    @Test
    fun `the emoji field keeps only the last character typed, whole`() {
        assertThat(lastGrapheme("")).isEmpty()
        assertThat(lastGrapheme("✨")).isEqualTo("✨")
        assertThat(lastGrapheme("✨🍵")).isEqualTo("🍵")
        assertThat(lastGrapheme("🍵🕯️")).isEqualTo("🕯️")
        assertThat(lastGrapheme("ab")).isEqualTo("b")
    }
}

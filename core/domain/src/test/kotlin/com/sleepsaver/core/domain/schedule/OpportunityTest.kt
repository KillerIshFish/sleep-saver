package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Test

class OpportunityTest {

    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)
    private val latency = Duration.ofMinutes(15)

    @Test
    fun `sleep opportunity is the wake time minus bedtime and latency`() {
        val opportunity = sleepOpportunity(Fx.instant(monday, "23:00"), Fx.instant(tuesday, "06:30"), latency)

        assertThat(opportunity).isEqualTo(Duration.ofMinutes(7 * 60 + 15))
    }

    @Test
    fun `sleep opportunity is never negative`() {
        assertThat(sleepOpportunity(Fx.instant(tuesday, "06:20"), Fx.instant(tuesday, "06:30"), latency)).isEqualTo(Duration.ZERO)
        assertThat(sleepOpportunity(Fx.instant(tuesday, "07:00"), Fx.instant(tuesday, "06:30"), latency)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `expected onset prefers the anchor, then the planned bedtime, then the target minus the need`() {
        val anchor = Fx.instant(monday, "23:10")
        val bedtime = Fx.instant(monday, "22:45")
        val target = Fx.instant(tuesday, "06:30")
        val need = Duration.ofHours(8)

        assertThat(expectedOnset(anchor, bedtime, latency, target, need)).isEqualTo(Fx.instant(monday, "23:25"))
        assertThat(expectedOnset(null, bedtime, latency, target, need)).isEqualTo(Fx.instant(monday, "23:00"))
        assertThat(expectedOnset(null, null, latency, target, need)).isEqualTo(Fx.instant(monday, "22:30"))
    }
}

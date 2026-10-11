package com.sleepsaver.core.data.mapping

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.PromptLogEntity
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.nightsteps.StepLog
import com.sleepsaver.core.domain.nightsteps.Technique
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class JournalMappersTest {

    private val date = LocalDate.of(2026, 10, 6)
    private val at = instant("2026-10-06T02:30:00Z")
    private val offset = ZoneOffset.ofHours(-4)

    @Test
    fun `step logs round-trip, an unknown technique reads as none`() {
        val log = StepLog(date, activityId = 3, technique = Technique.SLOW_TIDE, doneAt = at, offset = offset)

        assertThat(log.toEntity()).isEqualTo(NightStepLogEntity("2026-10-06", 3, "SLOW_TIDE", at.toEpochMilli(), -14_400))
        assertThat(log.toEntity().toDomain()).isEqualTo(log)
        assertThat(NightStepLogEntity("2026-10-06", 3, "FOOT_SOAK", 0, 0).toDomain().technique).isNull()
    }

    @Test
    fun `tomorrow lists round-trip`() {
        val list = TomorrowList(date, "- call Sam\nbuy milk", at, offset)

        assertThat(list.toEntity().toDomain()).isEqualTo(list)
    }

    @Test
    fun `stored marks round-trip, unknown and derived names are dropped`() {
        val entity = Mark.ALCOHOL.toEntity(date, at, offset)

        assertThat(entity).isEqualTo(NightMarkEntity("2026-10-06", "ALCOHOL", at.toEpochMilli(), -14_400))
        assertThat(entity.toDomainOrNull()).isEqualTo(Mark.ALCOHOL)
        assertThat(NightMarkEntity("2026-10-06", "LATE_SNACK", 0, 0).toDomainOrNull()).isNull()
        assertThat(NightMarkEntity("2026-10-06", "HUSH_USED", 0, 0).toDomainOrNull()).isNull()
        assertThat(runCatching { Mark.HUSH_USED.toEntity(date, at, offset) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `morning feel round-trips, a bad level or source is dropped`() {
        val entry = MorningFeelEntry(date, MorningFeel.CLEAR, at, offset, FeelSource.RING)

        assertThat(entry.toEntity()).isEqualTo(MorningFeelEntity("2026-10-06", 4, at.toEpochMilli(), -14_400, "RING"))
        assertThat(entry.toEntity().toDomainOrNull()).isEqualTo(entry)
        assertThat(MorningFeelEntity("2026-10-06", 9, 0, 0, "RING").toDomainOrNull()).isNull()
        assertThat(MorningFeelEntity("2026-10-06", 3, 0, 0, "WIDGET").toDomainOrNull()).isNull()
    }

    @Test
    fun `prompt kinds round-trip`() {
        val entity = PromptKind.ODD_NIGHT.toEntity(date, at, offset)

        assertThat(entity).isEqualTo(PromptLogEntity("2026-10-06", "ODD_NIGHT", at.toEpochMilli(), -14_400))
        assertThat(entity.toDomainOrNull()).isEqualTo(PromptKind.ODD_NIGHT)
        assertThat(entity.copy(kind = "RATE_US").toDomainOrNull()).isNull()
    }
}

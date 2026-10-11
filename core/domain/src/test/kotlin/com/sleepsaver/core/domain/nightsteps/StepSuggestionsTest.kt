package com.sleepsaver.core.domain.nightsteps

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.NightStepsConfig
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.PatternFx
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Test

class StepSuggestionsTest {

    private val engine = StepSuggestionEngine()

    /** Sunday 2026-10-11. */
    private val asOf = LocalDate.of(2026, 10, 11)

    /** Two calm weeks: asleep 23:00 → 07:00, nothing awake. */
    private val calm: List<Night> = Fx.regularNights(14, asOf)

    private fun suggest(
        nights: List<Night> = calm,
        inRoutine: Set<Technique> = emptySet(),
        routineHasScreens: Boolean = false,
        marks: Map<LocalDate, Set<Mark>> = emptyMap(),
        latencies: List<Duration> = emptyList(),
        status: DebtStatus? = null,
        engine: StepSuggestionEngine = this.engine,
    ): List<StepSuggestion> = engine.suggest(nights, inRoutine, routineHasScreens, marks, latencies, status, asOf)

    private fun minutes(vararg values: Long): List<Duration> = values.map { Duration.ofMinutes(it) }

    private fun s(technique: Technique, reason: SuggestionReason) = StepSuggestion(technique, reason)

    /** [count] nights ending on [asOf] with [awake] minutes awake each. */
    private fun restless(count: Int, awake: Long): List<Night> =
        PatternFx.dates(count, asOf).map { PatternFx.night(it, asleep = 420, awake = awake) }

    private fun dates(vararg back: Long): List<LocalDate> = back.map { asOf.minusDays(it) }

    // ---------------------------------------------------------------- rules

    @Test
    fun `slow to settle suggests the Tomorrow list and Slow tide`() {
        assertThat(suggest(latencies = minutes(40, 35, 45))).containsExactly(
            s(Technique.TOMORROW_LIST, SuggestionReason.SLOW_TO_SETTLE),
            s(Technique.SLOW_TIDE, SuggestionReason.SLOW_TO_SETTLE),
        ).inOrder()
    }

    @Test
    fun `slow to settle needs three latencies above 30 minutes of the latest 14`() {
        assertThat(suggest(latencies = minutes(40, 45)).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
        assertThat(suggest(latencies = minutes(30, 30, 30)).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
        val olderSlowOnes = minutes(*LongArray(14) { 10 }) + minutes(*LongArray(10) { 60 })
        assertThat(suggest(latencies = olderSlowOnes).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
    }

    @Test
    fun `restless nights suggest muscle release and a body scan`() {
        assertThat(suggest(nights = restless(7, awake = 30))).containsExactly(
            s(Technique.MUSCLE_RELEASE, SuggestionReason.RESTLESS_NIGHTS),
            s(Technique.BODY_SCAN, SuggestionReason.RESTLESS_NIGHTS),
        ).inOrder()
        // Both in the routine: the cool dark room is next.
        assertThat(suggest(nights = restless(7, awake = 30), inRoutine = setOf(Technique.MUSCLE_RELEASE, Technique.BODY_SCAN)).first())
            .isEqualTo(s(Technique.COOL_DARK_ROOM, SuggestionReason.RESTLESS_NIGHTS))
    }

    @Test
    fun `restless nights look at the last seven present nights only`() {
        val olderRestless = PatternFx.dates(7, asOf.minusDays(7)).map { PatternFx.night(it, asleep = 420, awake = 120) }
        val recentCalm = restless(7, awake = 25)

        assertThat(suggest(nights = olderRestless + recentCalm).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
        // A gap doesn't matter: the last seven present nights, however far back.
        assertThat(suggest(nights = olderRestless).first().reason).isEqualTo(SuggestionReason.RESTLESS_NIGHTS)
    }

    @Test
    fun `stressful days suggest the Tomorrow list and Slow tide`() {
        val stress = PatternFx.marked(Mark.STRESS, dates(0, 3, 6))

        assertThat(suggest(marks = stress)).containsExactly(
            s(Technique.TOMORROW_LIST, SuggestionReason.STRESSFUL_DAYS),
            s(Technique.SLOW_TIDE, SuggestionReason.STRESSFUL_DAYS),
        ).inOrder()
        // Two of the last seven, or a third one eight days ago, isn't enough.
        assertThat(suggest(marks = PatternFx.marked(Mark.STRESS, dates(0, 3, 7))).map { it.reason }.toSet())
            .containsExactly(SuggestionReason.STARTER)
    }

    @Test
    fun `drifting bedtimes suggest screens down and a warm shower`() {
        // Onsets alternate 21:30 and 00:30: a circular SD of about 91 min.
        val drifting = PatternFx.dates(14, asOf).mapIndexed { i, date ->
            PatternFx.night(date, asleep = 420, fellAsleep = if (i % 2 == 0) "21:30" else "00:30")
        }

        assertThat(suggest(nights = drifting)).containsExactly(
            s(Technique.SCREENS_DOWN, SuggestionReason.DRIFTING_BEDTIMES),
            s(Technique.WARM_SHOWER, SuggestionReason.DRIFTING_BEDTIMES),
        ).inOrder()
        // Six nights aren't enough to call it drift.
        assertThat(suggest(nights = drifting.takeLast(6)).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
        // An hour apart (SD about 30 min) is steady enough.
        val steady = PatternFx.dates(14, asOf).mapIndexed { i, date ->
            PatternFx.night(date, asleep = 420, fellAsleep = if (i % 2 == 0) "22:30" else "23:30")
        }
        assertThat(suggest(nights = steady).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
    }

    @Test
    fun `screens in the routine or in bed suggest screens down and paper`() {
        val expected = listOf(s(Technique.SCREENS_DOWN, SuggestionReason.SCREENS_IN_ROUTINE), s(Technique.READ_ON_PAPER, SuggestionReason.SCREENS_IN_ROUTINE))

        assertThat(suggest(routineHasScreens = true)).isEqualTo(expected)
        assertThat(suggest(marks = PatternFx.marked(Mark.SCREENS_IN_BED, dates(1, 2, 4)))).isEqualTo(expected)
        assertThat(suggest(marks = PatternFx.marked(Mark.SCREENS_IN_BED, dates(1, 2))).map { it.reason }.toSet())
            .containsExactly(SuggestionReason.STARTER)
    }

    @Test
    fun `a high debt suggests a warm shower and Slow tide`() {
        assertThat(suggest(status = DebtStatus.HIGH)).containsExactly(
            s(Technique.WARM_SHOWER, SuggestionReason.SHORT_ON_SLEEP),
            s(Technique.SLOW_TIDE, SuggestionReason.SHORT_ON_SLEEP),
        ).inOrder()
        assertThat(suggest(status = DebtStatus.MODERATE).map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)
    }

    @Test
    fun `starters are the best-studied techniques in order`() {
        assertThat(suggest()).containsExactly(
            s(Technique.WARM_SHOWER, SuggestionReason.STARTER),
            s(Technique.SCREENS_DOWN, SuggestionReason.STARTER),
        ).inOrder()
        assertThat(suggest(inRoutine = setOf(Technique.WARM_SHOWER, Technique.SCREENS_DOWN)).map { it.technique })
            .containsExactly(Technique.BODY_SCAN, Technique.COOL_DARK_ROOM).inOrder()
        val allGood = Technique.entries.filter { it.evidence == EvidenceLevel.GOOD }.toSet()
        assertThat(suggest(inRoutine = allGood).map { it.technique })
            .containsExactly(Technique.TOMORROW_LIST, Technique.SLOW_TIDE).inOrder()
        val goodAndModerate = Technique.entries.filter { it.evidence == EvidenceLevel.GOOD || it.evidence == EvidenceLevel.MODERATE }.toSet()
        assertThat(suggest(inRoutine = goodAndModerate)).isEmpty()
    }

    @Test
    fun `away from home or unwell this week means no suggestions`() {
        val signals = PatternFx.marked(Mark.STRESS, dates(0, 1, 2))

        assertThat(suggest(marks = PatternFx.merge(signals, PatternFx.marked(Mark.AWAY_FROM_HOME, dates(6))), status = DebtStatus.HIGH))
            .isEmpty()
        assertThat(suggest(marks = PatternFx.merge(signals, PatternFx.marked(Mark.UNWELL, dates(0))))).isEmpty()
        // Eight days ago is last week.
        assertThat(suggest(marks = PatternFx.merge(signals, PatternFx.marked(Mark.UNWELL, dates(7))))).isNotEmpty()
        // Marked this evening (tonight's wake date) counts too.
        assertThat(suggest(marks = PatternFx.merge(signals, PatternFx.marked(Mark.UNWELL, listOf(asOf.plusDays(1)))))).isEmpty()
    }

    // ---------------------------------------------------------------- ordering and filters

    @Test
    fun `rules apply in order and skip what is already in the routine`() {
        val restlessAndSlow = suggest(nights = restless(7, awake = 40), latencies = minutes(40, 50, 60))
        assertThat(restlessAndSlow.map { it.reason }).containsExactly(SuggestionReason.SLOW_TO_SETTLE, SuggestionReason.SLOW_TO_SETTLE)

        val withTomorrowList = suggest(nights = restless(7, awake = 40), latencies = minutes(40, 50, 60), inRoutine = setOf(Technique.TOMORROW_LIST))
        assertThat(withTomorrowList).containsExactly(
            s(Technique.SLOW_TIDE, SuggestionReason.SLOW_TO_SETTLE),
            s(Technique.MUSCLE_RELEASE, SuggestionReason.RESTLESS_NIGHTS),
        ).inOrder()

        // One rule match leaves the second place to a starter.
        assertThat(suggest(latencies = minutes(40, 50, 60), inRoutine = setOf(Technique.TOMORROW_LIST))).containsExactly(
            s(Technique.SLOW_TIDE, SuggestionReason.SLOW_TO_SETTLE),
            s(Technique.WARM_SHOWER, SuggestionReason.STARTER),
        ).inOrder()
    }

    @Test
    fun `a technique is never suggested twice`() {
        val four = StepSuggestionEngine(SleepConfig(steps = NightStepsConfig(suggestionMax = 4)))

        val result = suggest(marks = PatternFx.marked(Mark.STRESS, dates(0, 1, 2)), status = DebtStatus.HIGH, engine = four)

        assertThat(result).containsExactly(
            s(Technique.TOMORROW_LIST, SuggestionReason.STRESSFUL_DAYS),
            s(Technique.SLOW_TIDE, SuggestionReason.STRESSFUL_DAYS),
            s(Technique.WARM_SHOWER, SuggestionReason.SHORT_ON_SLEEP),
            s(Technique.SCREENS_DOWN, SuggestionReason.STARTER),
        ).inOrder()
    }

    @Test
    fun `little-evidence and screen-based techniques are never suggested`() {
        val many = StepSuggestionEngine(SleepConfig(steps = NightStepsConfig(suggestionMax = 50)))
        val everything = suggest(
            nights = restless(7, awake = 60),
            routineHasScreens = true,
            marks = PatternFx.marked(Mark.STRESS, dates(0, 1, 2)),
            latencies = minutes(60, 60, 60),
            status = DebtStatus.HIGH,
            engine = many,
        )

        assertThat(everything.map { it.technique }).containsNoneOf(Technique.HUSH, Technique.TV, Technique.GAMING)
        assertThat(everything.map { it.technique }).containsNoDuplicates()
        assertThat(everything.map { it.reason }).isInOrder()
        assertThat(everything.filter { it.technique.evidence == EvidenceLevel.LITTLE || it.technique.screenBased }).isEmpty()
    }

    @Test
    fun `at most two suggestions by default`() {
        val result = suggest(nights = restless(7, awake = 60), latencies = minutes(60, 60, 60), status = DebtStatus.HIGH)

        assertThat(result).hasSize(2)
    }
}

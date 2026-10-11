package com.sleepsaver.feature.progress

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.domain.night.NightAssembler
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Builders for nights with specific shapes, plus states built the same way the ViewModel builds them. */
object ProgressFixtures {
    val zone: ZoneId = SampleData.zone
    val today: LocalDate = SampleData.today
    val settings = UserSleepSettings()

    fun at(date: LocalDate, hour: Int, minute: Int = 0): LocalDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute))

    fun session(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        zone: ZoneId = this.zone,
        stages: List<SleepStage> = emptyList(),
    ): RawSleepSession {
        val s = start.atZone(zone).toInstant()
        val e = end.atZone(zone).toInstant()
        return RawSleepSession(
            id = id,
            origin = SleepConfig.GARMIN_CONNECT_PACKAGE,
            start = s,
            end = e,
            startOffset = zone.rules.getOffset(s),
            endOffset = zone.rules.getOffset(e),
            stages = stages,
        )
    }

    fun assemble(sessions: List<RawSleepSession>, overrides: List<NightOverride> = emptyList()): List<Night> =
        NightAssembler().assemble(sessions, SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE), overrides)

    /** Wake date of SampleData's night `k` days back. */
    private fun day(k: Int): LocalDate = today.minusDays(k.toLong())

    /**
     * The current window with something of everything, by wake date k days back: k = 2 (Saturday night) has only
     * an afternoon nap, k = 5 and 6 (Wednesday and Tuesday night) are missing, k = 4 (Thursday night) was edited,
     * k = 8 starts with an evening doze, k = 9 is a lie-in past 11 AM and k = 10 has a mid-morning nap.
     */
    fun eventfulNights(): List<Night> {
        val replaced = setOf(5, 6, 8, 9)
        val base = SampleData.sessions().filterNot { s ->
            s.id == "sample:2" || replaced.any { k -> s.id == "sample:$k" || s.id.startsWith("sample:$k:") }
        }
        val extra = listOf(
            session("doze", at(day(9), 20, 5), at(day(9), 21, 20)),
            session("main8", at(day(9), 23, 40), at(day(8), 7, 5)),
            session("lie-in", at(day(9), 1, 10), at(day(9), 11, 50)),
            session("morning-nap", at(day(10), 9, 40), at(day(10), 10, 25)),
        )
        val edit = NightOverride(
            wakeDate = day(4),
            wokeUp = at(day(4), 7, 45).atZone(zone).toInstant(),
            offset = zone.rules.getOffset(at(day(4), 7, 45).atZone(zone).toInstant()),
            updatedAt = SampleData.now,
        )
        return assemble((base + extra).sortedBy { it.start }, listOf(edit))
    }

    /** Three weeks of 4-hour nights ending today: debt climbs far past the chart's usual 12 hours. */
    fun shortNights(): List<Night> = assemble(
        (0 until 21).map { k ->
            val wake = day(k)
            session("short:$k", at(wake.minusDays(1), 23, 50).plusMinutes((k % 4) * 20L), at(wake, 4, 10).plusMinutes((k % 3) * 15L))
        }.sortedBy { it.start },
    )

    fun ready(
        nights: List<Night> = SampleData.nights(),
        windowsBack: Int = 0,
        withVitals: Boolean = true,
        settings: UserSleepSettings = this.settings,
        keel: KeelResult? = null,
        socialJetlag: SocialJetlag? = null,
        markEffects: List<MarkEffect> = emptyList(),
    ): ProgressUiState.Ready {
        val window = ProgressWindow.of(today, windowsBack, oldestNight = null)
        return ProgressStateBuilder.build(
            today = today,
            zone = zone,
            windowsBack = windowsBack,
            nights = nights,
            debtSeries = SampleData.debtSeries(window.from, window.to, nights, settings),
            vitals = if (withVitals) SampleData.vitals(nights) else emptyList(),
            settings = settings,
            keel = keel,
            socialJetlag = socialJetlag,
            markEffects = markEffects,
        ) as ProgressUiState.Ready
    }
}

/** A clock that only moves when the test says so. */
class FakeTimeProvider(now: Instant = SampleData.now, private val zone: ZoneId = SampleData.zone) : TimeProvider {
    val ticks = MutableStateFlow(now)
    override fun now(): Instant = ticks.value
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> = ticks
}

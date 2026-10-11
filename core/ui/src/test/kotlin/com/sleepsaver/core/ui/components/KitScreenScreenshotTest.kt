package com.sleepsaver.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** A full-screen composition of the kit, to check that the pieces sit well together. */
class KitScreenScreenshotTest : ScreenshotTest() {

    private val zone = ZoneOffset.ofHours(2)
    private fun phase(type: PhaseType, start: String, end: String, shiftMin: Long?) = Phase(
        type = type,
        start = Instant.parse("2026-10-06T${start}:00Z"),
        end = Instant.parse("2026-10-06T${end}:00Z"),
        shiftVsYesterday = shiftMin?.let { Duration.ofMinutes(it) },
    )

    @Test
    fun homeComposition() = captureScreen("kit_home") {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = Spacing.l, bottom = Spacing.l),
        ) {
            ScreenHeader(
                title = "Sleep debt",
                subtitle = "Tuesday, Oct 6",
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            ) {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = SleepSaverTheme.colors.textSecondary)
                }
            }
            Spacer(Modifier.height(Spacing.l))
            DebtGauge(
                debt = Duration.ofMinutes(222),
                status = DebtStatus.MODERATE,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(Spacing.m))
            Row(
                Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                ChangeChip(change = Duration.ofMinutes(-12))
                StreakBadge(days = 12)
            }
            Spacer(Modifier.height(Spacing.s))
            Row(
                Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                InfoChip("7h 41m last night", icon = Icons.Rounded.Bedtime)
                InfoChip("Garmin", icon = Icons.Rounded.Watch)
                InfoChip("Nap", icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary, onClick = {})
            }
            Spacer(Modifier.height(Spacing.xl))
            SectionHeader(
                "Today's energy",
                actionLabel = "See all",
                onAction = {},
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
            )
            Spacer(Modifier.height(Spacing.s))
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                val compact = Modifier.width(PhaseCardDefaults.CompactWidth)
                PhaseCard(phase(PhaseType.MORNING_PEAK, "07:30", "10:30", -13), zone, compact, highlighted = true)
                PhaseCard(phase(PhaseType.AFTERNOON_DIP, "12:00", "14:30", 0), zone, compact)
                PhaseCard(phase(PhaseType.EVENING_PEAK, "15:30", "18:30", 8), zone, compact)
            }
            Spacer(Modifier.height(Spacing.l))
            SectionCard(
                title = "Next up",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
            ) {
                IconListItem(
                    icon = HabitType.CAFFEINE_CUTOFF.icon,
                    title = HabitType.CAFFEINE_CUTOFF.title(),
                    supporting = "2:15 PM",
                )
            }
            Spacer(Modifier.weight(1f))
            WellnessDisclaimer(Modifier.padding(horizontal = Spacing.screenHorizontal))
        }
    }

    // ---------------------------------------------------------------- v2 compositions

    @Test
    fun tonightComposition() = captureScreen("kit_tonight") {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        ) {
            ScreenHeader(title = "Tonight", subtitle = "Tuesday evening")
            SleepSaverCard(Modifier.fillMaxWidth()) {
                Text("6:30 AM", style = MaterialTheme.typography.displayMedium)
                Text("Tomorrow, Wed", style = MaterialTheme.typography.titleSmall, color = SleepSaverTheme.colors.textSecondary)
                Text(
                    "May ring from 6:00 at a restless moment, never later than 6:30",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.s),
                )
                Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    InfoChip("Asleep by 11:10 PM → about 3.1 h (−0.4 h)", icon = AlarmMode.SURFACING.icon)
                }
            }
            NoticeCard(
                title = "Your alarm might not ring",
                body = AlarmBlocker.EXACT_ALARMS_OFF.fix(),
                icon = AlarmBlocker.EXACT_ALARMS_OFF.icon,
                actionLabel = "Alarm check",
                onAction = {},
                tone = NoticeTone.WARNING,
            )
            SectionCard("Anything to mark for tonight?") {
                MarkChips(selected = setOf(Mark.LATE_CAFFEINE), onToggle = {})
            }
            SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                ToggleRow("Hush", checked = false, onCheckedChange = {}, supporting = "Pink · 45 min", icon = Mark.HUSH_USED.icon)
            }
            Spacer(Modifier.weight(1f))
            WellnessDisclaimer()
        }
    }

    @Test
    fun dayPlanComposition() = captureScreen("kit_day_plan") {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        ) {
            ScreenHeader(title = "Week plan", subtitle = "Next: Wed 6:30 AM · window 30 min")
            DayTabs(
                selected = DayOfWeek.TUESDAY,
                onSelect = {},
                marked = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            )
            SectionCard("Tuesday morning") {
                LocalTimeScrubber(value = LocalTime.of(6, 30), onValueChange = {}, label = "Wake time")
                SegmentedControl(AlarmMode.entries.map { it.title() }, selectedIndex = 2, onSelectedChange = {})
                Text(AlarmMode.SURFACING.description().orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard("Tuesday evening (before Wednesday)") {
                ToggleRow("Night steps", checked = true, onCheckedChange = {}, supporting = "3 steps · same as every evening")
                Text("Copy to…", style = MaterialTheme.typography.labelLarge, color = SleepSaverTheme.colors.textSecondary)
                DayChipRow(selected = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), onToggle = {})
            }
        }
    }

    @Test
    fun ringingComposition() = captureScreen("kit_ringing") {
        Box(Modifier.fillMaxSize()) {
            WarmGlow(Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text("6:12", style = MaterialTheme.typography.displayLarge)
                Text("Good morning", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A restless moment · 18 min before 6:30",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = Spacing.s),
                )
                Spacer(Modifier.weight(1f))
                SecondaryButton("Snooze until 6:40", onClick = {}, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.m))
                PrimaryButton("Stop", onClick = {}, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

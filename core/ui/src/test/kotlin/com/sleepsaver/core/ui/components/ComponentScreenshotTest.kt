package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.domain.nightsteps.StepCategory
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.KeelBand
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class ComponentScreenshotTest : ScreenshotTest() {

    private fun min(m: Long) = Duration.ofMinutes(m)

    @Test
    fun debtGauge() = captureComponent("debt_gauge") {
        DebtGauge(debt = min(222), status = DebtStatus.MODERATE)
    }

    @Test
    fun debtGaugeStates() = captureComponent("debt_gauge_states") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebtGauge(Duration.ZERO, DebtStatus.LOW, size = 180.dp)
                DebtGauge(min(84), DebtStatus.LOW, size = 180.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebtGauge(min(408), DebtStatus.HIGH, size = 180.dp)
                DebtGauge(Duration.ofHours(14), DebtStatus.HIGH, size = 180.dp)
            }
        }
    }

    @Test
    fun chips() = captureComponent("chips") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StreakBadge(days = 12)
            StreakBadge(days = 1)
            ChangeChip(change = min(-12))
            ChangeChip(change = min(66))
            ChangeChip(change = min(1), suffix = null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip("7h 41m last night", icon = Icons.Rounded.Bedtime)
                InfoChip("Garmin", icon = Icons.Rounded.Watch)
                InfoChip("Nap", icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary, onClick = {})
            }
            InfoChip("No icon")
        }
    }

    @Test
    fun phaseCards() = captureComponent("phase_cards") {
        val compact = Modifier.width(PhaseCardDefaults.CompactWidth)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhaseCard(PhaseType.GROGGINESS, "7:05 – 8:20 AM", compact, shift = "↓8m later")
                PhaseCard(PhaseType.MORNING_PEAK, "9:30 AM – 12:30 PM", compact, shift = "↑13m earlier", highlighted = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhaseCard(PhaseType.AFTERNOON_DIP, "2:00 – 4:30 PM", compact, shift = "same as yesterday")
                PhaseCard(PhaseType.EVENING_PEAK, "5:30 – 8:30 PM", compact) // no shift: same height
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhaseCard(PhaseType.WIND_DOWN, "9:15 – 10:15 PM", compact, shift = "↑1h 5m earlier")
                PhaseCard(PhaseType.MELATONIN_WINDOW, "10:15 – 11:15 PM", compact, shift = "same as yesterday")
            }
        }
    }

    @Test
    fun cards() = captureComponent("cards") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Today's energy", actionLabel = "See all", onAction = {})
            SectionCard("Next up", actionLabel = "Edit", onAction = {}) {
                Text("Caffeine cutoff", style = MaterialTheme.typography.titleMedium)
                Text("2:15 PM", style = MaterialTheme.typography.bodyMedium, color = SleepSaverTheme.colors.textSecondary)
            }
            SleepSaverCard(Modifier.fillMaxWidth(), onClick = {}) {
                Text("Energy potential", style = MaterialTheme.typography.titleSmall, color = SleepSaverTheme.colors.textSecondary)
                Text("82%", style = MaterialTheme.typography.displayMedium)
            }
        }
    }

    @Test
    fun buttons() = captureComponent("buttons") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Sync now", onClick = {}, icon = Icons.Rounded.Sync, modifier = Modifier.fillMaxWidth())
            SecondaryButton("Add it by hand", onClick = {}, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Save", onClick = {})
                PrimaryButton("Disabled", onClick = {}, enabled = false)
                TextLinkButton("Not now", onClick = {})
            }
        }
    }

    @Test
    fun emptyStateWithActions() = captureComponent("empty_state_actions") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            EmptyState(
                icon = Icons.Rounded.HealthAndSafety,
                title = "Health Connect is needed",
                body = "Sleep Saver reads your Garmin sleep through Health Connect. Install it to continue.",
                primaryActionLabel = "Get Health Connect",
                onPrimaryAction = {},
                secondaryActionLabel = "Try demo data",
                onSecondaryAction = {},
            )
            EmptyState(
                icon = Icons.Rounded.Lock,
                title = "Sleep access is off",
                body = "Allow Sleep Saver to read sleep so it can work out your debt.",
                primaryActionLabel = "Allow access",
                onPrimaryAction = {},
            )
        }
    }

    @Test
    fun emptyStateNoActions() = captureComponent("empty_state_plain") {
        Column(Modifier.width(379.dp)) {
            EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = "No sleep yet",
                body = "Wear your watch tonight and your first night shows up here in the morning.",
            )
        }
    }

    @Test
    fun segmentedControl() = captureComponent("segmented_control") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SegmentedControl(listOf("Sleep times", "Sleep debt", "Quality"), selectedIndex = 1, onSelectedChange = {})
            SegmentedControl(listOf("12-hour", "24-hour"), selectedIndex = 0, onSelectedChange = {})
        }
    }

    @Test
    fun timeScrubber() = captureComponent("time_scrubber") {
        val zone = ZoneOffset.ofHours(2)
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            TimeScrubber(
                value = Instant.parse("2026-10-05T21:47:00Z"),
                onValueChange = {},
                zone = zone,
                label = "Fell asleep",
            )
            CompositionLocalProvider(LocalUse24HourClock provides true) {
                TimeScrubber(
                    value = Instant.parse("2026-10-06T04:55:00Z"),
                    onValueChange = {},
                    zone = zone,
                    max = Instant.parse("2026-10-06T05:10:00Z"),
                    label = "Woke up",
                )
            }
        }
    }

    @Test
    fun minuteStepper() = captureComponent("minute_stepper") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MinuteStepper(minutes = 45, onMinutesChange = {})
            MinuteStepper(minutes = 0, onMinutesChange = {})
            MinuteStepper(minutes = 480, onMinutesChange = {}, step = 15)
        }
    }

    @Test
    fun loadingOrb() = captureComponent("loading_orb") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LoadingOrb(label = "Syncing…")
            LoadingOrb(size = 56.dp)
        }
    }

    @Test
    fun screenHeader() = captureComponent("screen_header") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ScreenHeader("My progress", subtitle = "Sep 22 – Oct 5") {
                IconButton(onClick = {}) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = SleepSaverTheme.colors.textSecondary)
                }
            }
            ScreenHeader("Energy")
            WellnessDisclaimer()
        }
    }

    @Test
    fun phaseVisuals() = captureComponent("phase_visuals") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhaseType.entries.forEach {
                IconListItem(it.icon, it.title(), supporting = stringResource(it.descriptionRes), tint = it.tint)
            }
        }
    }

    @Test
    fun habitVisuals() = captureComponent("habit_visuals") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HabitType.entries.forEach {
                IconListItem(
                    icon = it.icon,
                    title = it.title(),
                    supporting = it.description(),
                    trailing = if (it == HabitType.CAFFEINE_CUTOFF) {
                        { Text("2:15 PM", style = MaterialTheme.typography.labelLarge) }
                    } else {
                        null
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebtStatus.entries.forEach { InfoChip(it.label(), icon = Icons.Rounded.Circle, iconTint = it.color) }
            }
        }
    }

    // ---------------------------------------------------------------- v2: week plan, alarm, Night steps, journal

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    @Test
    fun dayPickers() = captureComponent("day_pickers") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DayChipRow(selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), onToggle = {})
            DayChipRow(selected = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), onToggle = {}, enabled = false)
            DayTabs(selected = DayOfWeek.TUESDAY, onSelect = {}, marked = weekdays)
            DayTabs(selected = DayOfWeek.SUNDAY, onSelect = {})
            // The narrowest the circles go: 7 × 40 dp plus gaps, about a dialog's content width.
            DayChipRow(selected = weekdays, onToggle = {}, modifier = Modifier.width(316.dp))
        }
    }

    @Test
    fun selectableChips() = captureComponent("selectable_chips") {
        FlowRow(Modifier.width(379.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Late caffeine", selected = true, onClick = {}, icon = Mark.LATE_CAFFEINE.icon)
            SelectableChip("Stress", selected = false, onClick = {}, icon = Mark.STRESS.icon)
            SelectableChip("45 min", selected = true, onClick = {})
            SelectableChip("90 min", selected = false, onClick = {})
            SelectableChip("Until my window", selected = false, onClick = {}, enabled = false)
            SelectableChip("Until my window", selected = true, onClick = {}, enabled = false)
        }
    }

    @Test
    fun toggles() = captureComponent("toggles") {
        Column(Modifier.width(379.dp)) {
            ToggleRow("Night steps", checked = true, onCheckedChange = {})
            ToggleRow(
                "Log movement all night",
                checked = false,
                onCheckedChange = {},
                supporting = stringResource(R.string.ss_placement) + " Uses more battery.",
            )
            ToggleRow("Hush", checked = true, onCheckedChange = {}, supporting = "Pink · 45 min", icon = Mark.HUSH_USED.icon)
            ToggleRow("Reminder when Night steps start", checked = true, onCheckedChange = {}, enabled = false)
            ToggleRow("Same as every evening", checked = false, onCheckedChange = {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SleepSaverSwitch(checked = true, onCheckedChange = {})
                SleepSaverSwitch(checked = false, onCheckedChange = {})
                SleepSaverSwitch(checked = true, onCheckedChange = {}, enabled = false)
                SleepSaverSwitch(checked = false, onCheckedChange = {}, enabled = false)
            }
        }
    }

    @Test
    fun noticeCards() = captureComponent("notice_cards") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val blocker = AlarmBlocker.RING_CHANNEL_OFF
            NoticeCard(
                title = blocker.title(),
                body = blocker.fix(),
                icon = blocker.icon,
                actionLabel = blocker.actionLabel(),
                onAction = {},
                tone = NoticeTone.WARNING,
            )
            NoticeCard(
                title = "Planned keel 84 · Even keel",
                body = "Free days wake 1 h 40 m later than alarm days. Keeping it within an hour steadies your Keel; " +
                    "a short early-afternoon catnap is one alternative.",
                actionLabel = "Got it",
                onAction = {},
            )
            NoticeCard(title = AlarmBlocker.BLUETOOTH_OUTPUT.title(), body = AlarmBlocker.BLUETOOTH_OUTPUT.fix())
        }
    }

    @Test
    fun morningFeelScale() = captureComponent("morning_feel_scale") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ss_feel_question), style = MaterialTheme.typography.titleMedium)
            MorningFeelScale(value = null, onValueChange = {})
            MorningFeelScale(value = MorningFeel.CLEAR, onValueChange = {})
            MorningFeelScale(value = MorningFeel.FOGGY, onValueChange = {})
        }
    }

    @Test
    fun evidenceBadges() = captureComponent("evidence_badges") {
        FlowRow(Modifier.width(379.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EvidenceLevel.entries.forEach { EvidenceBadge(it) }
        }
    }

    @Test
    fun markChips() = captureComponent("mark_chips") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MarkChips(selected = setOf(Mark.LATE_CAFFEINE, Mark.STRESS, Mark.AWAY_FROM_HOME), onToggle = {})
            // Read-only, with the derived marks too (as on Night detail).
            MarkChips(
                selected = setOf(Mark.ALCOHOL, Mark.NIGHT_STEPS_DONE, Mark.ANCHOR_DROPPED),
                onToggle = {},
                marks = Mark.entries,
                enabled = false,
            )
        }
    }

    private val hush = HushControlsState(
        color = HushColor.PINK,
        timer = Duration.ofMinutes(45),
        volume = 0.25f,
        playing = false,
        untilWindowAvailable = true,
        mediaVolumeHigh = false,
    )

    @Test
    fun hushControls() = captureComponent("hush_controls") {
        Column(Modifier.width(379.dp)) { HushControls(hush, HushControlsActions()) }
    }

    @Test
    fun hushControlsPlaying() = captureComponent("hush_controls_playing") {
        Column(Modifier.width(379.dp)) {
            HushControls(
                hush.copy(color = HushColor.BROWN, timer = null, volume = 0.6f, playing = true, mediaVolumeHigh = true),
                HushControlsActions(),
            )
        }
    }

    @Test
    fun hushControlsNight() = captureComponent("hush_controls_night") {
        Column(Modifier.width(379.dp)) {
            HushControls(hush.copy(color = HushColor.WHITE, untilWindowAvailable = false, night = true), HushControlsActions())
        }
    }

    @Test
    fun warmGlow() = captureComponent("warm_glow") {
        Box(Modifier.size(379.dp, 560.dp)) {
            WarmGlow(Modifier.matchParentSize())
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("6:12", style = MaterialTheme.typography.displayLarge)
                Text("Good morning", style = MaterialTheme.typography.headlineSmall)
                Text("A restless moment · 18 min before 6:30", color = SleepSaverTheme.colors.textSecondary)
            }
        }
    }

    @Test
    fun localTimeScrubber() = captureComponent("local_time_scrubber") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LocalTimeScrubber(value = LocalTime.of(6, 30), onValueChange = {}, label = "Wake time")
            LocalTimeScrubber(value = LocalTime.of(23, 50), onValueChange = {}, overnight = true, label = "Bedtime")
            CompositionLocalProvider(LocalUse24HourClock provides true) {
                LocalTimeScrubber(value = LocalTime.of(0, 15), onValueChange = {}, overnight = true, stepMinutes = 15)
            }
        }
    }

    @Test
    fun customActivityForm() = captureComponent("custom_activity_form") {
        Column(Modifier.width(379.dp)) {
            CustomActivityForm(
                onCancel = {},
                onAdd = { _, _ -> },
                initialLabel = "Herbal tea",
                initialEmoji = "🍵",
                autoFocus = false,
            )
        }
    }

    @Test
    fun journalVisuals() = captureComponent("journal_visuals") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Mark.entries.forEach { IconListItem(it.icon, it.label(), supporting = if (it.derived) "Derived" else null) }
            Text(MorningFeel.entries.map { it.label() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(KeelBand.entries.map { it.label() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Test
    fun nightStepsVisuals() = captureComponent("night_steps_visuals") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Technique.entries.forEach { technique ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(technique.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(technique.title(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            technique.category.title(),
                            style = MaterialTheme.typography.bodySmall,
                            color = SleepSaverTheme.colors.textSecondary,
                        )
                    }
                    EvidenceBadge(technique.evidence)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StepCategory.entries.forEach { Icon(it.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    @Test
    fun techniqueCopy() = captureComponent("technique_copy") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Technique.SLOW_TIDE, Technique.TOMORROW_LIST, Technique.HUSH).forEach { technique ->
                SleepSaverCard(Modifier.fillMaxWidth()) {
                    Text(
                        "${technique.emoji}  ${technique.title()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(technique.howTo(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        technique.why(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(technique.source(), style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textTertiary)
                }
            }
        }
    }

    @Test
    fun alarmVisuals() = captureComponent("alarm_visuals") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WakeStyle.entries.forEach { IconListItem(it.icon, it.title(), supporting = it.description()) }
            AlarmMode.entries.forEach { IconListItem(it.icon, it.title(), supporting = it.description()) }
            Text(AlarmTone.entries.map { it.title() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(
                SnoozeStyle.entries.map { listOfNotNull(it.title(), it.description()).joinToString(": ") }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            HushColor.entries.forEach { Text("${it.title()}: ${it.description()}", style = MaterialTheme.typography.bodyMedium) }
        }
    }

    @Test
    fun alarmBlockers() = captureComponent("alarm_blockers") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AlarmBlocker.entries.forEach { blocker ->
                IconListItem(
                    blocker.icon,
                    blocker.title(),
                    supporting = blocker.fix(),
                    tint = if (blocker.blocking) MaterialTheme.colorScheme.tertiary else SleepSaverTheme.colors.textSecondary,
                )
            }
        }
    }
}

package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Test
import java.time.Duration
import java.time.Instant
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
}

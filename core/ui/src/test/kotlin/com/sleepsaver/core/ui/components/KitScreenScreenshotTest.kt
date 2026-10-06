package com.sleepsaver.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import org.junit.Test
import java.time.Duration
import java.time.Instant
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
}

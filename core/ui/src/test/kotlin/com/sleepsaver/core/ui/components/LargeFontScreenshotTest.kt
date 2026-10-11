package com.sleepsaver.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** The kit at large system font sizes ("Large" 115 % up to the 200 % maximum): nothing may clip or spill over. */
class LargeFontScreenshotTest : ScreenshotTest() {

    @Composable
    private fun FontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = scale), content = content)
    }

    @Test
    fun debtGauge() = captureComponent("large_font_debt_gauge") {
        FontScale(2f) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebtGauge(Duration.ofMinutes(222), DebtStatus.MODERATE, size = 180.dp)
                DebtGauge(Duration.ofMinutes(12 * 60 + 18), DebtStatus.HIGH, size = 180.dp)
            }
        }
    }

    @Test
    fun controls() = captureComponent("large_font_controls") {
        FontScale(2f) {
            Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TimeScrubber(
                    value = Instant.parse("2026-10-05T21:47:00Z"),
                    onValueChange = {},
                    zone = ZoneOffset.ofHours(2),
                    label = "Fell asleep",
                )
                ChangeChip(change = Duration.ofMinutes(-12))
                StreakBadge(days = 12)
            }
        }
    }

    @Test
    fun phaseCardRows() = captureComponent("large_font_phase_cards") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(1.15f, 1.3f, 2f).forEach { scale ->
                FontScale(scale) {
                    // The equal-height recipe from PhaseCard's KDoc, inside a scrolling row as on the home screen.
                    Row(
                        Modifier
                            .width(379.dp)
                            .horizontalScroll(rememberScrollState())
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val card = Modifier.width(PhaseCardDefaults.CompactWidth).fillMaxHeight()
                        PhaseCard(PhaseType.MORNING_PEAK, "9:30 AM – 12:30 PM", card, shift = "↑13m earlier", highlighted = true)
                        PhaseCard(PhaseType.MELATONIN_WINDOW, "10:15 – 11:15 PM", card)
                        PhaseCard(PhaseType.AFTERNOON_DIP, "2:00 – 4:30 PM", card, shift = "same as yesterday")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- v2 components

    @Test
    fun weekControls() = captureComponent("large_font_week_controls") {
        FontScale(2f) {
            Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DayChipRow(selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), onToggle = {})
                DayTabs(selected = DayOfWeek.WEDNESDAY, onSelect = {}, marked = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
                LocalTimeScrubber(value = LocalTime.of(23, 50), onValueChange = {}, overnight = true, label = "Bedtime")
                ToggleRow(
                    "Night steps",
                    checked = true,
                    onCheckedChange = {},
                    supporting = "Night steps from 9:45 PM".keepTimesTogether(),
                    icon = Mark.NIGHT_STEPS_DONE.icon,
                )
            }
        }
    }

    @Test
    fun choices() = captureComponent("large_font_choices") {
        Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Below 150 % the words stay side by side; from 150 % they stack.
            FontScale(1.3f) { MorningFeelScale(value = MorningFeel.BRIGHT, onValueChange = {}) }
            FontScale(2f) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MorningFeelScale(value = MorningFeel.SLOW, onValueChange = {})
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvidenceBadge(EvidenceLevel.GOOD)
                        EvidenceBadge(EvidenceLevel.LITTLE)
                    }
                }
            }
        }
    }

    @Test
    fun marksAndNotice() = captureComponent("large_font_marks_notice") {
        FontScale(2f) {
            Column(Modifier.width(379.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MarkChips(selected = setOf(Mark.SCREENS_IN_BED, Mark.AWAY_FROM_HOME), onToggle = {})
                NoticeCard(
                    title = "Notifications are off",
                    body = "Alarms need notifications to show the Stop button.",
                    actionLabel = "Allow",
                    onAction = {},
                    tone = NoticeTone.WARNING,
                )
            }
        }
    }

    @Test
    fun hush() = captureComponent("large_font_hush") {
        FontScale(2f) {
            Column(Modifier.width(379.dp)) {
                HushControls(
                    HushControlsState(
                        color = HushColor.BROWN,
                        timer = null,
                        volume = 0.25f,
                        playing = false,
                        untilWindowAvailable = true,
                        mediaVolumeHigh = true,
                    ),
                    HushControlsActions(),
                )
            }
        }
    }
}

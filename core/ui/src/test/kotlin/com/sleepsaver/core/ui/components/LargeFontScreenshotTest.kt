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
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import org.junit.Test
import java.time.Duration
import java.time.Instant
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
}

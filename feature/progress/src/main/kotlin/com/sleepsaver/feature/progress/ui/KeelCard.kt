package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.patterns.KeelBand
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.R
import java.time.Duration

/**
 * Keel on the Sleep times tab: the regularity score with its band ("84 Even keel"), how much bedtime and wake time
 * vary, the social jetlag line and what Keel compares. While calibrating: "Keel · Calibrating: 4 of 7 days with
 * nights" (or "needs two nights in a row" when there are enough nights but none back to back).
 */
@Composable
internal fun KeelCard(
    keel: KeelResult,
    socialJetlag: SocialJetlag?,
    modifier: Modifier = Modifier,
    config: SleepConfig = SleepConfig.DEFAULT,
) {
    val colors = SleepSaverTheme.colors
    SleepSaverCard(modifier.fillMaxWidth()) {
        when (keel) {
            is KeelResult.Ready -> {
                Text(
                    text = stringResource(R.string.progress_keel_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val band = keel.band.label()
                val description = stringResource(R.string.progress_keel_description, keel.score, band)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .clearAndSetSemantics { contentDescription = description },
                ) {
                    Text(
                        text = keel.score.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = band,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (keel.band == KeelBand.EVEN) MaterialTheme.colorScheme.primary else colors.textSecondary,
                        modifier = Modifier.padding(start = Spacing.m, bottom = 8.dp),
                    )
                }
                spreadLine(keel.onsetSd, keel.wakeSd)?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
            is KeelResult.Calibrating -> Text(
                text = if (keel.validDays >= keel.needed) {
                    stringResource(R.string.progress_keel_calibrating_pairs)
                } else {
                    stringResource(R.string.progress_keel_calibrating, keel.validDays, keel.needed)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        socialJetlag?.let { jetlag ->
            Text(
                text = jetlagLine(jetlag, config),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.s),
            )
            if (!jetlag.freeDaysFromPlan) {
                Text(
                    text = stringResource(R.string.progress_jetlag_weekend),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
        }
        Text(
            text = stringResource(R.string.progress_keel_caption, config.keel.windowDays),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

/** "Bedtime varies by about ±35 min, wake time by about ±20 min.", or whichever half is known. */
@Composable
private fun spreadLine(onsetSd: Duration?, wakeSd: Duration?): String? = when {
    onsetSd != null && wakeSd != null ->
        stringResource(R.string.progress_keel_spread, aboutMinutes(onsetSd), aboutMinutes(wakeSd))
    onsetSd != null -> stringResource(R.string.progress_keel_spread_bedtime, aboutMinutes(onsetSd))
    wakeSd != null -> stringResource(R.string.progress_keel_spread_wake, aboutMinutes(wakeSd))
    else -> null
}

/** "Social jetlag: free days run 1h 20m later" / "…earlier", or "Free days and alarm days line up well." */
@Composable
private fun jetlagLine(jetlag: SocialJetlag, config: SleepConfig): String {
    val shift = jetlag.shift
    return when {
        shift.abs() < config.keel.jetlagAlignedBelow -> stringResource(R.string.progress_jetlag_aligned)
        shift.isNegative -> stringResource(R.string.progress_jetlag_earlier, formatDuration(shift.abs()))
        else -> stringResource(R.string.progress_jetlag_later, formatDuration(shift))
    }
}

/** A spread in rough minutes, like formatAboutMinutes: rounded half up to 5, never below 5. */
private fun aboutMinutes(duration: Duration): Long =
    ((duration.toMinutes() + ABOUT_STEP / 2) / ABOUT_STEP * ABOUT_STEP).coerceAtLeast(ABOUT_STEP)

private const val ABOUT_STEP = 5L

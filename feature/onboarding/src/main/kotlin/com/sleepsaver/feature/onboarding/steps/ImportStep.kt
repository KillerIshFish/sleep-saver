package com.sleepsaver.feature.onboarding.steps

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.formatWindowHeader
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.ImportUiState
import com.sleepsaver.feature.onboarding.NightsSummary
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.ui.StepHeading
import com.sleepsaver.feature.onboarding.ui.StepLayout
import java.time.Duration
import kotlin.math.roundToInt

/** Step 3: import the history (instant in demo mode), then say what was found. */
@Composable
internal fun ImportStep(
    import: ImportUiState,
    busy: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val importing = import is ImportUiState.Importing || import == ImportUiState.NotStarted
    StepLayout(
        modifier = modifier,
        centered = true,
        bottom = {
            if (import is ImportUiState.Failed) {
                PrimaryButton(stringResource(R.string.onboarding_try_again), onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.xs))
                TextLinkButton(stringResource(R.string.onboarding_continue_anyway), onClick = onContinue, enabled = !busy)
            } else {
                PrimaryButton(
                    stringResource(R.string.onboarding_continue),
                    onClick = onContinue,
                    enabled = !importing && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        when (import) {
            ImportUiState.NotStarted -> Importing(progress = null)
            is ImportUiState.Importing -> Importing(import.progress)
            is ImportUiState.Done ->
                if (import.nights.count == 0) {
                    EmptyState(
                        icon = Icons.Rounded.Nightlight,
                        title = stringResource(R.string.onboarding_import_empty_title),
                        body = stringResource(R.string.onboarding_import_empty_body),
                    )
                } else {
                    Found(
                        title = stringResource(R.string.onboarding_import_done_title),
                        label = pluralStringResource(R.plurals.onboarding_nights_found, import.nights.count),
                        note = null,
                        nights = import.nights,
                    )
                }
            is ImportUiState.Demo -> Found(
                title = stringResource(R.string.onboarding_demo_title),
                label = pluralStringResource(R.plurals.onboarding_demo_nights, import.nights.count),
                note = stringResource(R.string.onboarding_demo_body),
                nights = import.nights,
            )
            is ImportUiState.Failed -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.onboarding_import_failed_title),
                body = stringResource(R.string.onboarding_import_failed_body, syncErrorText(import.error)),
            )
        }
    }
}

@Composable
private fun Importing(progress: Float?) {
    LoadingOrb(size = 120.dp)
    Spacer(Modifier.height(Spacing.xl))
    StepHeading(
        title = stringResource(R.string.onboarding_import_title),
        body = stringResource(R.string.onboarding_import_body),
        centered = true,
    )
    Spacer(Modifier.height(Spacing.xxl))
    if (progress != null) {
        val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "importProgress")
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier
                    .width(200.dp)
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = SleepSaverTheme.colors.cardRaised,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                text = stringResource(R.string.onboarding_import_percent, (shown * 100).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(start = Spacing.m),
            )
        }
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .width(200.dp)
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = SleepSaverTheme.colors.cardRaised,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
}

@Composable
private fun Found(title: String, label: String, note: String?, nights: NightsSummary) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Spacing.xxl))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = nights.count.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            color = SleepSaverTheme.colors.textSecondary,
        )
    }
    if (nights.first != null && nights.last != null && nights.first != nights.last) {
        Text(
            text = formatWindowHeader(nights.first, nights.last),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
    if (note != null) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.l, start = Spacing.l, end = Spacing.l),
        )
    }
    if (nights.recent.any { it != null }) {
        Spacer(Modifier.height(Spacing.xxl))
        SleepSaverCard(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onboarding_import_recent),
                style = MaterialTheme.typography.labelLarge,
                color = SleepSaverTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(Spacing.l))
            RecentNightsBars(nights.recent)
        }
    }
}

/** One rounded bar per night (height = total sleep, 10 h = full), a dot for a missing night. */
@Composable
private fun RecentNightsBars(recent: List<Duration?>, modifier: Modifier = Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    val missing = SleepSaverTheme.colors.textTertiary
    val grid = SleepSaverTheme.colors.gridLine
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clearAndSetSemantics { },
    ) {
        if (recent.isEmpty()) return@Canvas
        val slot = size.width / recent.size
        val barWidth = slot * 0.56f
        val radius = CornerRadius(barWidth / 2, barWidth / 2)
        val fullMinutes = 10 * 60f
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        recent.forEachIndexed { index, total ->
            val x = slot * index + (slot - barWidth) / 2
            if (total == null) {
                drawCircle(missing, radius = 2.dp.toPx(), center = Offset(x + barWidth / 2, size.height - 4.dp.toPx()))
            } else {
                val fraction = (total.toMinutes() / fullMinutes).coerceIn(0.08f, 1f)
                val h = (size.height - 2.dp.toPx()) * fraction
                val latest = index == recent.lastIndex
                drawRoundRect(
                    color = if (latest) tide else tide.copy(alpha = 0.55f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
internal fun syncErrorText(error: SyncError?): String = stringResource(
    when (error) {
        SyncError.HEALTH_CONNECT_NOT_INSTALLED -> R.string.onboarding_error_not_installed
        SyncError.HEALTH_CONNECT_UPDATE_REQUIRED -> R.string.onboarding_error_update
        SyncError.PERMISSION_DENIED -> R.string.onboarding_error_permission
        SyncError.RATE_LIMITED -> R.string.onboarding_error_rate_limited
        SyncError.BACKGROUND_NOT_ALLOWED -> R.string.onboarding_error_background
        SyncError.UNKNOWN, null -> R.string.onboarding_error_unknown
    },
)

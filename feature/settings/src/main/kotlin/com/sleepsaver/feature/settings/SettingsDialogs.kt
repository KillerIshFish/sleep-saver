package com.sleepsaver.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.TimeScrubber
import com.sleepsaver.core.ui.components.color
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.formatClockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** The dialogs the settings screen can show. */
enum class SettingsDialog { SLEEP_NEED, TYPICAL_WAKE, STREAK, DEBT_LEVELS, SOURCE, DEMO_OFF, RESET }

// ---------------------------------------------------------------- Sleep need

@Composable
internal fun SleepNeedDialogCard(
    current: Duration,
    suggestion: NeedSuggestion?,
    onSave: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable {
        mutableIntStateOf(current.toMinutes().toInt().coerceIn(SettingsLimits.NEED_MIN_MINUTES, SettingsLimits.NEED_MAX_MINUTES))
    }
    SettingsDialogCard(
        title = stringResource(R.string.settings_sleep_need),
        body = stringResource(R.string.settings_sleep_need_dialog_body),
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onSave(draft) },
        dismissLabel = stringResource(R.string.settings_cancel),
        onDismiss = onDismiss,
    ) {
        MinuteStepper(
            minutes = draft,
            onMinutesChange = { draft = it },
            step = SettingsLimits.NEED_STEP_MINUTES,
            min = SettingsLimits.NEED_MIN_MINUTES,
            max = SettingsLimits.NEED_MAX_MINUTES,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (suggestion != null) {
            val suggested = suggestion.need.toMinutes().toInt()
            Spacer(Modifier.height(Spacing.l))
            InfoChip(
                text = stringResource(R.string.settings_need_suggestion_chip, formatDuration(suggestion.need)),
                icon = Icons.Rounded.AutoAwesome,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = if (draft != suggested) ({ draft = suggested }) else null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ---------------------------------------------------------------- Typical wake

/** The scrubber works on instants: a fixed UTC day stands in for "any day", so local time = UTC time. */
private val ScrubberDay: LocalDate = LocalDate.of(2000, 1, 3)

private fun LocalTime.onScrubberDay(): Instant = ScrubberDay.atTime(this).toInstant(ZoneOffset.UTC)

@Composable
internal fun TypicalWakeDialogCard(
    current: LocalTime,
    learnedWake: LocalTime?,
    onSave: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftMinute by rememberSaveable { mutableIntStateOf(current.hour * 60 + current.minute) }
    val draft = LocalTime.of(draftMinute / 60, draftMinute % 60)
    val body = if (learnedWake != null) {
        stringResource(R.string.settings_typical_wake_learned_body, formatClockTime(learnedWake, LocalUse24HourClock.current))
    } else {
        stringResource(R.string.settings_typical_wake_caption)
    }
    val title = stringResource(R.string.settings_typical_wake)
    SettingsDialogCard(
        title = title,
        body = body,
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onSave(draft) },
        dismissLabel = stringResource(R.string.settings_cancel),
        onDismiss = onDismiss,
    ) {
        TimeScrubber(
            value = draft.onScrubberDay(),
            onValueChange = { instant ->
                val time = instant.atZone(ZoneOffset.UTC).toLocalTime()
                draftMinute = time.hour * 60 + time.minute
            },
            zone = ZoneOffset.UTC,
            min = LocalTime.MIDNIGHT.onScrubberDay(),
            max = LocalTime.of(23, 55).onScrubberDay(),
            stepMinutes = SettingsLimits.WAKE_STEP_MINUTES,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = title },
        )
    }
}

// ---------------------------------------------------------------- Streak

@Composable
internal fun StreakDialogCard(
    current: Duration,
    onSave: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable {
        mutableIntStateOf(current.toMinutes().toInt().coerceIn(SettingsLimits.STREAK_MIN_MINUTES, SettingsLimits.STREAK_MAX_MINUTES))
    }
    SettingsDialogCard(
        title = stringResource(R.string.settings_streak),
        body = stringResource(R.string.settings_streak_dialog_body),
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onSave(draft) },
        dismissLabel = stringResource(R.string.settings_cancel),
        onDismiss = onDismiss,
    ) {
        MinuteStepper(
            minutes = draft,
            onMinutesChange = { draft = it },
            step = SettingsLimits.STREAK_STEP_MINUTES,
            min = SettingsLimits.STREAK_MIN_MINUTES,
            max = SettingsLimits.STREAK_MAX_MINUTES,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// ---------------------------------------------------------------- Debt levels

@Composable
internal fun DebtLevelsDialogCard(
    lowMax: Duration,
    highMin: Duration,
    onSave: (lowMaxMinutes: Int, highMinMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val step = SettingsLimits.DEBT_LEVEL_STEP_MINUTES
    var high by rememberSaveable {
        mutableIntStateOf(highMin.toMinutes().toInt().coerceIn(SettingsLimits.DEBT_LEVEL_MIN_MINUTES + step, SettingsLimits.DEBT_LEVEL_MAX_MINUTES))
    }
    var low by rememberSaveable {
        mutableIntStateOf(lowMax.toMinutes().toInt().coerceIn(SettingsLimits.DEBT_LEVEL_MIN_MINUTES, high - step))
    }
    SettingsDialogCard(
        title = stringResource(R.string.settings_debt_levels),
        body = stringResource(R.string.settings_debt_levels_dialog_body),
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onSave(low, high) },
        confirmEnabled = low < high,
        dismissLabel = stringResource(R.string.settings_cancel),
        onDismiss = onDismiss,
    ) {
        LevelStepper(
            label = stringResource(R.string.settings_debt_low_below),
            dot = DebtStatus.LOW.color,
            minutes = low,
            onMinutesChange = { low = it },
            min = SettingsLimits.DEBT_LEVEL_MIN_MINUTES,
            max = high - step,
        )
        Spacer(Modifier.height(Spacing.l))
        LevelStepper(
            label = stringResource(R.string.settings_debt_high_from),
            dot = DebtStatus.HIGH.color,
            minutes = high,
            onMinutesChange = { high = it },
            min = low + step,
            max = SettingsLimits.DEBT_LEVEL_MAX_MINUTES,
        )
        Spacer(Modifier.height(Spacing.xl))
        DebtLevelsPreview(low, high)
    }
}

@Composable
private fun LevelStepper(
    label: String,
    dot: Color,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    min: Int,
    max: Int,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(dot, CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        MinuteStepper(
            minutes = minutes,
            onMinutesChange = onMinutesChange,
            step = SettingsLimits.DEBT_LEVEL_STEP_MINUTES,
            min = min,
            max = max,
        )
    }
}

/** Three coloured segments (Low / Moderate / High) to scale, with a legend. */
@Composable
private fun DebtLevelsPreview(low: Int, high: Int) {
    val tail = maxOf(high - low, 120)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(DebtStatus.LOW to low, DebtStatus.MODERATE to high - low, DebtStatus.HIGH to tail).forEach { (status, weight) ->
                Box(
                    Modifier
                        .weight(weight.coerceAtLeast(1).toFloat())
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(status.color),
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        val lowText = formatDuration(Duration.ofMinutes(low.toLong()))
        val highText = formatDuration(Duration.ofMinutes(high.toLong()))
        val ranges = listOf(
            DebtStatus.LOW to stringResource(R.string.settings_debt_range_low, lowText),
            DebtStatus.MODERATE to stringResource(R.string.settings_debt_range_moderate, lowText, highText),
            DebtStatus.HIGH to stringResource(R.string.settings_debt_range_high, highText),
        )
        if (LocalDensity.current.fontScale >= LargeLegendFontScale) {
            // Three columns don't fit very large text: one level per line.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ranges.forEach { (status, range) -> LegendItem(status, range) }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                ranges.forEach { (status, range) -> LegendItem(status, range, Modifier.weight(1f)) }
            }
        }
    }
}

private const val LargeLegendFontScale = 1.5f

@Composable
private fun LegendItem(status: DebtStatus, range: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(status.color, CircleShape),
        )
        Column(Modifier.padding(start = 6.dp)) {
            Text(status.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(range, style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textSecondary)
        }
    }
}

// ---------------------------------------------------------------- Data source

private const val ALL_SOURCES_KEY = "*all*"

private fun SourceSelection.key(): String = when (this) {
    SourceSelection.All -> ALL_SOURCES_KEY
    is SourceSelection.Single -> packageName
}

@Composable
internal fun SourceDialogCard(
    options: List<SourceOption>,
    selected: SourceSelection,
    demoMode: Boolean,
    onSave: (SourceSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftKey by rememberSaveable { mutableStateOf(selected.key()) }
    val body = stringResource(R.string.settings_source_dialog_body) +
        if (demoMode) " " + stringResource(R.string.settings_source_dialog_demo) else ""
    SettingsDialogCard(
        title = stringResource(R.string.settings_source),
        body = body,
        confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { options.firstOrNull { it.selection.key() == draftKey }?.let { onSave(it.selection) } ?: onDismiss() },
        dismissLabel = stringResource(R.string.settings_cancel),
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            options.forEach { option ->
                val isSelected = option.selection.key() == draftKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { draftKey = option.selection.key() })
                        .padding(vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = SleepSaverTheme.colors.textTertiary,
                        ),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = Spacing.m),
                    ) {
                        Text(option.title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        option.detail()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** The option's name: "Garmin Connect", "All sources (duplicates removed)", an app's label or its package. */
@Composable
internal fun SourceOption.title(short: Boolean = false): String = when (kind) {
    SourceKind.GARMIN -> stringResource(R.string.settings_source_garmin)
    SourceKind.ALL -> stringResource(if (short) R.string.settings_source_all_short else R.string.settings_source_all)
    SourceKind.APP -> appLabel ?: packageName.orEmpty()
}

@Composable
private fun SourceOption.detail(): String? = when (kind) {
    SourceKind.GARMIN -> stringResource(if (hasData) R.string.settings_source_default else R.string.settings_source_no_data)
    SourceKind.ALL -> stringResource(R.string.settings_source_all_body)
    SourceKind.APP -> if (hasData) null else stringResource(R.string.settings_source_not_installed)
}

// ---------------------------------------------------------------- Demo off

@Composable
internal fun DemoOffDialogCard(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SettingsDialogCard(
        title = stringResource(R.string.settings_demo_off_title),
        body = stringResource(R.string.settings_demo_off_body),
        confirmLabel = stringResource(R.string.settings_demo_off_confirm),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.settings_demo_off_keep),
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------- Reset

@Composable
internal fun ResetDialogCard(resetting: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SettingsDialogCard(
        title = stringResource(R.string.settings_reset_title),
        body = stringResource(R.string.settings_reset_dialog_body),
        confirmLabel = stringResource(if (resetting) R.string.settings_resetting else R.string.settings_reset_confirm),
        onConfirm = onConfirm,
        confirmEnabled = !resetting,
        dismissLabel = stringResource(R.string.settings_cancel),
        dismissEnabled = !resetting,
        onDismiss = onDismiss,
        destructive = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ResetItem(Icons.Rounded.Bedtime, stringResource(R.string.settings_reset_item_nights))
            ResetItem(Icons.Rounded.Checklist, stringResource(R.string.settings_reset_item_habits))
            ResetItem(Icons.Rounded.Tune, stringResource(R.string.settings_reset_item_settings))
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.settings_reset_untouched),
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ResetItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = SleepSaverTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.m),
        )
    }
}

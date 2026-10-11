package com.sleepsaver.feature.winddown.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.ui.components.CustomActivityForm
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.SleepSaverSwitch
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.platform.rememberNotificationAccess
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.LoadingBody
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.UnavailableBody
import com.sleepsaver.feature.winddown.WinddownSurface
import com.sleepsaver.feature.winddown.steps.EmojiCircle
import com.sleepsaver.feature.winddown.steps.stepLength

/** What the routine editor can do; every action defaults to doing nothing. */
@Stable
internal class RoutineEditorActions(
    val onBack: () -> Unit = {},
    val onSameChange: (Boolean) -> Unit = {},
    val onIncludedChange: (activityId: Long, included: Boolean) -> Unit = { _, _ -> },
    val onLeadChange: (activityId: Long, minutes: Int) -> Unit = { _, _ -> },
    val onDurationChange: (activityId: Long, minutes: Int) -> Unit = { _, _ -> },
    val onAddFromLibrary: () -> Unit = {},
    val onAddCustom: (label: String, emoji: String) -> Unit = { _, _ -> },
    val onRemove: (activityId: Long) -> Unit = {},
    val onReminderChange: (Boolean) -> Unit = {},
)

@Composable
internal fun RoutineEditorScreen(
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifications = rememberNotificationAccess()
    RoutineEditorContent(
        state = state,
        actions = RoutineEditorActions(
            onBack = onBack,
            onSameChange = viewModel::setSameAsEveryEvening,
            onIncludedChange = viewModel::setIncluded,
            onLeadChange = viewModel::setLead,
            onDurationChange = viewModel::setDuration,
            onAddFromLibrary = onOpenLibrary,
            onAddCustom = viewModel::addCustom,
            onRemove = viewModel::remove,
            onReminderChange = { on ->
                viewModel.setReminder(on)
                if (on) notifications.onReminderTurnedOn()
            },
        ),
        modifier = modifier,
    )
}

/**
 * "Every evening" or "Tuesday evening": which activities are Night steps, when each starts before bedtime and how long
 * it lasts, "Add from the library" / "Add your own", and the reminder. [removing] and [addingCustom] open with a dialog
 * already showing (screenshots); [formAutoFocus] = false keeps the custom form's keyboard closed (tests).
 */
@Composable
internal fun RoutineEditorContent(
    state: RoutineEditorUiState,
    actions: RoutineEditorActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    removing: Long? = null,
    addingCustom: Boolean = false,
    formAutoFocus: Boolean = true,
) {
    var removeId by rememberSaveable { mutableStateOf(removing) }
    var showForm by rememberSaveable { mutableStateOf(addingCustom) }
    WinddownSurface(onBack = actions.onBack, modifier = modifier) {
        when (state) {
            RoutineEditorUiState.Loading -> LoadingBody()
            RoutineEditorUiState.Unavailable -> UnavailableBody(actions.onBack)
            is RoutineEditorUiState.Ready -> EditorBody(
                state = state,
                actions = actions,
                scrollState = scrollState,
                onRemove = { removeId = it },
                onAddOwn = { showForm = true },
            )
        }
    }

    val ready = state as? RoutineEditorUiState.Ready
    val removeRow = ready?.rows?.firstOrNull { it.activity.id == removeId }
    if (removeRow != null) {
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text(stringResource(R.string.winddown_editor_remove_title, removeRow.activity.label)) },
            text = { Text(stringResource(R.string.winddown_editor_remove_body)) },
            confirmButton = {
                TextLinkButton(
                    stringResource(R.string.winddown_editor_remove),
                    onClick = {
                        removeId = null
                        actions.onRemove(removeRow.activity.id)
                    },
                )
            },
            dismissButton = {
                TextLinkButton(
                    stringResource(R.string.winddown_editor_remove_cancel),
                    onClick = { removeId = null },
                    color = SleepSaverTheme.colors.textSecondary,
                )
            },
            containerColor = SleepSaverTheme.colors.card,
        )
    }
    if (showForm && ready != null) {
        Dialog(onDismissRequest = { showForm = false }) {
            CustomActivityForm(
                onCancel = { showForm = false },
                onAdd = { label, emoji ->
                    showForm = false
                    actions.onAddCustom(label, emoji)
                },
                autoFocus = formAutoFocus,
            )
        }
    }
}

@Composable
private fun EditorBody(
    state: RoutineEditorUiState.Ready,
    actions: RoutineEditorActions,
    scrollState: ScrollState,
    onRemove: (Long) -> Unit,
    onAddOwn: () -> Unit,
) {
    val day = state.day
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        ScreenHeader(
            title = if (day == null) {
                stringResource(R.string.winddown_editor_every_evening)
            } else {
                stringResource(R.string.winddown_editor_day, formatDayLong(day))
            },
            subtitle = stringResource(R.string.winddown_editor_subtitle),
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (day != null) {
            ToggleRow(
                title = stringResource(R.string.winddown_editor_same),
                checked = state.editsEveryEvening,
                onCheckedChange = actions.onSameChange,
                supporting = if (state.editsEveryEvening) {
                    stringResource(R.string.winddown_editor_same_on)
                } else {
                    stringResource(R.string.winddown_editor_same_off, formatDayLong(day))
                },
                modifier = Modifier.padding(top = Spacing.l),
            )
        }
        Text(
            stringResource(R.string.winddown_editor_steps),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = Spacing.xl, bottom = Spacing.m)
                .semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            state.rows.forEach { row ->
                EditorRowCard(
                    row = row,
                    state = state,
                    onIncludedChange = { actions.onIncludedChange(row.activity.id, it) },
                    onLeadChange = { actions.onLeadChange(row.activity.id, it) },
                    onDurationChange = { actions.onDurationChange(row.activity.id, it) },
                    onRemove = { onRemove(row.activity.id) },
                )
            }
        }
        SecondaryButton(
            stringResource(R.string.winddown_editor_add_library),
            onClick = actions.onAddFromLibrary,
            icon = Icons.Rounded.AutoStories,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        )
        SecondaryButton(
            stringResource(R.string.winddown_editor_add_own),
            onClick = onAddOwn,
            icon = Icons.Rounded.Add,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.m),
        )
        SleepSaverCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = Spacing.s),
        ) {
            ToggleRow(
                title = stringResource(R.string.winddown_editor_reminder),
                checked = state.reminderOn,
                onCheckedChange = actions.onReminderChange,
                icon = Icons.Rounded.NotificationsActive,
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

/**
 * One activity: emoji, label and its include switch (the whole top row toggles), "Starts 45 min before bedtime ·
 * 10 min", and for an included one the two steppers. Custom activities can be removed.
 */
@Composable
private fun EditorRowCard(
    row: EditorRow,
    state: RoutineEditorUiState.Ready,
    onIncludedChange: (Boolean) -> Unit,
    onLeadChange: (Int) -> Unit,
    onDurationChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val activity = row.activity
    val leadMinutes = activity.lead.toMinutes().toInt()
    val durationMinutes = activity.duration.toMinutes().toInt()
    SleepSaverCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = Spacing.m, bottom = if (row.included || activity.isCustom) Spacing.s else Spacing.m),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = row.included, role = Role.Switch, onValueChange = onIncludedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiCircle(activity.emoji)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.m),
            ) {
                Text(activity.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                // An included step shows the same numbers on its steppers below.
                if (!row.included) {
                    Text(
                        stringResource(R.string.winddown_editor_summary, minutesText(leadMinutes), stepLength(activity.duration)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            SleepSaverSwitch(checked = row.included, onCheckedChange = null)
        }
        if (row.included) {
            StepperRow(
                label = stringResource(R.string.winddown_editor_lead),
                minutes = leadMinutes,
                onChange = onLeadChange,
                step = state.leadStepMinutes,
                max = state.leadMaxMinutes,
                modifier = Modifier.padding(top = Spacing.m),
            )
            StepperRow(
                label = stringResource(R.string.winddown_editor_length),
                minutes = durationMinutes,
                onChange = onDurationChange,
                step = state.durationStepMinutes,
                max = state.durationMaxMinutes,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            if (durationMinutes == 0) {
                Text(
                    stringResource(R.string.winddown_editor_length_zero),
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textSecondary,
                )
            }
        }
        if (activity.isCustom) {
            TextLinkButton(
                stringResource(R.string.winddown_editor_remove),
                onClick = onRemove,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.offset(x = (-12).dp),
            )
        }
    }
}

/** "Starts before bedtime  − 45 min +": the label beside the stepper, or above it at large font sizes. */
@Composable
private fun StepperRow(label: String, minutes: Int, onChange: (Int) -> Unit, step: Int, max: Int, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val stepper = @Composable {
        MinuteStepper(
            minutes = minutes,
            onMinutesChange = onChange,
            step = step,
            min = 0,
            max = max,
            format = { resources.getString(R.string.winddown_minutes, it) },
        )
    }
    val labelText = @Composable { textModifier: Modifier ->
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SleepSaverTheme.colors.textSecondary, modifier = textModifier)
    }
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(modifier) {
            labelText(Modifier)
            stepper()
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            labelText(Modifier.weight(1f))
            stepper()
        }
    }
}

/** "45 min" (also "0 min" and "240 min": steppers never switch to hours). */
@Composable
private fun minutesText(minutes: Int): String = stringResource(R.string.winddown_minutes, minutes)

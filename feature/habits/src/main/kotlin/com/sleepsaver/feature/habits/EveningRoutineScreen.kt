package com.sleepsaver.feature.habits

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

@Composable
internal fun EveningRoutineScreen(
    onClose: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EveningRoutineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val done = (state as? EveningRoutineUiState.Ready)?.done == true
    LaunchedEffect(done) { if (done) onDone() }
    EveningRoutineContent(
        state = state,
        onClose = onClose,
        onToggle = viewModel::setSelected,
        onAddCustom = viewModel::addCustom,
        onDelete = viewModel::delete,
        onAddToSchedule = viewModel::addToSchedule,
        modifier = modifier,
    )
}

/**
 * A card sheet over the app background: close, a short intro, a two-column grid of activity tiles with a
 * "+ Custom" tile at the end, and the save button pinned to the bottom.
 */
@Composable
internal fun EveningRoutineContent(
    state: EveningRoutineUiState,
    onClose: () -> Unit,
    onToggle: (id: Long, selected: Boolean) -> Unit,
    onAddCustom: (label: String, emoji: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onAddToSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = Spacing.m)
                .clip(SheetShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            TopIconButton(
                icon = Icons.Rounded.Close,
                description = stringResource(R.string.habits_routine_close),
                onClick = onClose,
                modifier = Modifier.padding(start = Spacing.s, top = Spacing.s),
            )
            when (state) {
                EveningRoutineUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingOrb(label = stringResource(R.string.habits_loading))
                }
                EveningRoutineUiState.Unavailable -> EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(R.string.habits_unavailable_title),
                    body = stringResource(R.string.habits_unavailable_body),
                    primaryActionLabel = stringResource(R.string.habits_go_back),
                    onPrimaryAction = onClose,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
                )
                is EveningRoutineUiState.Ready -> RoutinePicker(
                    state = state,
                    scrollState = scrollState,
                    onToggle = onToggle,
                    onAddCustom = onAddCustom,
                    onDelete = onDelete,
                    onAddToSchedule = onAddToSchedule,
                )
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val TileShape = RoundedCornerShape(20.dp)

/** Tiles are about 3:2 on a phone (≈175 dp wide) and grow for large text. */
private val TileMinHeight = 116.dp

@Composable
private fun RoutinePicker(
    state: EveningRoutineUiState.Ready,
    scrollState: ScrollState,
    onToggle: (Long, Boolean) -> Unit,
    onAddCustom: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
    onAddToSchedule: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HabitBadge(HabitType.EVENING_ROUTINE, active = true, size = 52.dp)
            Text(
                text = stringResource(R.string.habits_routine_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.l)
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.habits_routine_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.s)
                    .widthIn(max = 340.dp),
            )
            Text(
                text = stringResource(R.string.habits_routine_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.l)
                    .widthIn(max = 320.dp),
            )
            ActivityGrid(
                activities = state.activities,
                onToggle = onToggle,
                onRequestDelete = { deletingId = it },
                onAddCustom = { adding = true },
                modifier = Modifier.padding(top = Spacing.xl),
            )
            if (state.hasCustom) {
                Text(
                    text = stringResource(R.string.habits_routine_long_press_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.l),
                )
            }
            // Room to scroll the last row clear of the pinned button.
            Spacer(Modifier.height(BottomBarSpace))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
        }
        BottomBar(
            label = stringResource(if (state.routineEnabled) R.string.habits_routine_save else R.string.habits_routine_add_to_schedule),
            enabled = state.canSave && !state.done,
            onClick = onAddToSchedule,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (adding) {
        Dialog(onDismissRequest = { adding = false }) {
            CustomActivityForm(
                onCancel = { adding = false },
                onAdd = { label, emoji ->
                    onAddCustom(label, emoji)
                    adding = false
                },
            )
        }
    }
    val deleting = state.activities.firstOrNull { it.id == deletingId && it.isCustom }
    if (deleting != null) {
        DeleteActivityDialog(
            activity = deleting,
            onConfirm = {
                onDelete(deleting.id)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

private val BottomBarSpace = 112.dp

@Composable
private fun BottomBar(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sheet = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to sheet, 1f to sheet))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xxl, bottom = Spacing.l),
    ) {
        PrimaryButton(text = label, onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ActivityGrid(
    activities: List<RoutineActivity>,
    onToggle: (Long, Boolean) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onAddCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = the "+ Custom" tile, always last.
    val cells: List<RoutineActivity?> = activities + null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        cells.chunked(2).forEach { pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                pair.forEach { activity ->
                    val cell = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                    if (activity == null) {
                        AddCustomTile(onClick = onAddCustom, modifier = cell)
                    } else {
                        ActivityTile(
                            activity = activity,
                            onToggle = { onToggle(activity.id, !activity.selected) },
                            onRequestDelete = if (activity.isCustom) ({ onRequestDelete(activity.id) }) else null,
                            modifier = cell,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityTile(
    activity: RoutineActivity,
    onToggle: () -> Unit,
    onRequestDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tide = MaterialTheme.colorScheme.primary
    val selected = activity.selected
    val removeLabel = stringResource(R.string.habits_routine_remove_action)
    Box(
        modifier = modifier
            .heightIn(min = TileMinHeight)
            .clip(TileShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.card)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) tide else MaterialTheme.colorScheme.outlineVariant,
                shape = TileShape,
            )
            .combinedClickable(
                role = Role.Checkbox,
                onLongClickLabel = if (onRequestDelete != null) removeLabel else null,
                onLongClick = onRequestDelete,
                onClick = onToggle,
            )
            .semantics {
                toggleableState = ToggleableState(selected)
                if (onRequestDelete != null) {
                    customActions = listOf(CustomAccessibilityAction(removeLabel) { onRequestDelete(); true })
                }
            }
            .padding(Spacing.l),
    ) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(activity.emoji, fontSize = 30.sp, lineHeight = 36.sp)
            Text(
                text = activity.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(tide),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AddCustomTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    val dash = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.habits_routine_add_custom)
    Box(
        modifier = modifier
            .heightIn(min = TileMinHeight)
            .clip(TileShape)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                )
            }
            .combinedClickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(Spacing.l),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = tide, modifier = Modifier.size(28.dp))
            Text(
                text = stringResource(R.string.habits_routine_custom),
                style = MaterialTheme.typography.titleSmall,
                color = tide,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun DeleteActivityDialog(activity: RoutineActivity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SleepSaverTheme.colors.card,
        title = { Text(stringResource(R.string.habits_routine_delete_title, activity.label)) },
        text = {
            Text(
                stringResource(R.string.habits_routine_delete_body),
                color = SleepSaverTheme.colors.textSecondary,
            )
        },
        confirmButton = { TextLinkButton(stringResource(R.string.habits_routine_delete_confirm), onClick = onConfirm) },
        dismissButton = {
            TextLinkButton(
                stringResource(R.string.habits_cancel),
                onClick = onDismiss,
                color = SleepSaverTheme.colors.textSecondary,
            )
        },
    )
}

package com.sleepsaver.feature.review.morning

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.ui.components.ChangeChip
import com.sleepsaver.core.ui.components.DebtGauge
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.color
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatHours1
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.ConfirmDialog
import com.sleepsaver.feature.review.common.HowWeMeasureSheet
import com.sleepsaver.feature.review.common.NavIcon
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.common.PageHeader
import com.sleepsaver.feature.review.common.ReviewPage
import com.sleepsaver.feature.review.common.nightDates
import com.sleepsaver.feature.review.common.nightTitle
import com.sleepsaver.feature.review.edit.EditActions
import com.sleepsaver.feature.review.edit.EditKind
import com.sleepsaver.feature.review.edit.NightEditorCallbacks
import com.sleepsaver.feature.review.edit.NightEditorForm
import kotlinx.coroutines.delay
import java.time.Duration
import kotlin.math.roundToLong

/** Every action the review flow can take. */
class MorningReviewActions(
    val onClose: () -> Unit,
    val onSyncNow: () -> Unit,
    val onLooksRight: () -> Unit,
    val onAdjust: () -> Unit,
    val onAdjustCancel: () -> Unit,
    val onAdjustDone: () -> Unit,
    val onDone: () -> Unit,
    val onDontShowAgain: () -> Unit,
    val editor: NightEditorCallbacks,
    val onOpenSettings: (() -> Unit)? = null,
) {
    companion object {
        val Noop = MorningReviewActions({}, {}, {}, {}, {}, {}, {}, {}, NightEditorCallbacks.Noop)
    }
}

@Composable
internal fun MorningReviewScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MorningReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onClose() }
    // System back: leave the editor first; elsewhere it's the close X.
    BackHandler(enabled = !state.finished) {
        if (state.step is ReviewStep.Adjust) viewModel.onAdjustCancel() else viewModel.onClose()
    }
    MorningReviewContent(
        state = state,
        actions = MorningReviewActions(
            onClose = viewModel::onClose,
            onSyncNow = viewModel::onSyncNow,
            onLooksRight = viewModel::onLooksRight,
            onAdjust = viewModel::onAdjust,
            onAdjustCancel = viewModel::onAdjustCancel,
            onAdjustDone = viewModel::onAdjustDone,
            onDone = viewModel::onDone,
            onDontShowAgain = viewModel::onDontShowAgain,
            editor = NightEditorCallbacks(
                onFellAsleepChange = viewModel::onFellAsleepChange,
                onWokeUpChange = viewModel::onWokeUpChange,
                onAwakeChange = viewModel::onAwakeChange,
                onNapsChange = viewModel::onNapsChange,
                onHowWeMeasure = {},
            ),
            onOpenSettings = onOpenSettings,
        ),
    )
}

/**
 * The morning review: syncing → last night's summary (or "isn't here yet") → optional adjust → recalculation →
 * how the debt moved. Stateless apart from its sheet and dialog. Pass `animateResult = false` to show the
 * result's final debt at once.
 */
@Composable
internal fun MorningReviewContent(
    state: MorningReviewUiState,
    actions: MorningReviewActions,
    modifier: Modifier = Modifier,
    animateResult: Boolean = true,
) {
    var howWeMeasure by rememberSaveable { mutableStateOf(false) }
    var confirmDontShow by rememberSaveable { mutableStateOf(false) }
    val step = state.step

    AnimatedContent(
        targetState = step,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) },
        modifier = modifier,
        label = "reviewStep",
    ) { target ->
        when (target) {
            is ReviewStep.Loading -> LoadingStep(target.recalculating, actions.onClose)
            is ReviewStep.Summary -> SummaryStep(
                state = state,
                night = target.night,
                actions = actions,
                onHowCalculated = { howWeMeasure = true },
                onDontShowAgain = { confirmDontShow = true },
            )
            is ReviewStep.Missing -> MissingStep(target.syncFailed, actions)
            is ReviewStep.Adjust -> ReviewPage(
                navIcon = NavIcon.BACK,
                onNavigate = actions.onAdjustCancel,
                bottomBar = {
                    EditActions(
                        canSave = target.editor.error == null,
                        onCancel = actions.onAdjustCancel,
                        onDone = actions.onAdjustDone,
                    )
                },
            ) {
                PageHeader(
                    title = stringResource(
                        if (target.editor.kind == EditKind.NEW) R.string.review_add_title else R.string.review_adjust_title,
                    ),
                    subtitle = nightDates(state.wakeDate, state.today),
                )
                Spacer(Modifier.height(Spacing.l))
                NightEditorForm(
                    state = target.editor,
                    callbacks = NightEditorCallbacks(
                        onFellAsleepChange = actions.editor.onFellAsleepChange,
                        onWokeUpChange = actions.editor.onWokeUpChange,
                        onAwakeChange = actions.editor.onAwakeChange,
                        onNapsChange = actions.editor.onNapsChange,
                        onHowWeMeasure = { howWeMeasure = true },
                    ),
                )
            }
            is ReviewStep.Result -> ResultStep(state, target.update, actions, animateResult)
        }
    }

    if (howWeMeasure) {
        val source: NightSource? = when (step) {
            is ReviewStep.Summary -> step.source
            is ReviewStep.Adjust -> step.editor.source
            else -> null
        }
        HowWeMeasureSheet(source = source, onDismiss = { howWeMeasure = false }, onChangeSource = actions.onOpenSettings)
    }
    if (confirmDontShow) {
        ConfirmDialog(
            title = stringResource(R.string.review_dont_show_title),
            body = stringResource(R.string.review_dont_show_body),
            confirmLabel = stringResource(R.string.review_dont_show_confirm),
            dismissLabel = stringResource(R.string.review_dont_show_keep),
            onConfirm = {
                confirmDontShow = false
                actions.onDontShowAgain()
            },
            onDismiss = { confirmDontShow = false },
        )
    }
}

@Composable
private fun LoadingStep(recalculating: Boolean, onClose: () -> Unit) {
    ReviewPage(navIcon = NavIcon.CLOSE, onNavigate = onClose, scrollState = null) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            LoadingOrb(
                size = 128.dp,
                label = stringResource(if (recalculating) R.string.review_recalculating else R.string.review_loading_sync),
            )
        }
    }
}

@Composable
private fun SummaryStep(
    state: MorningReviewUiState,
    night: Night,
    actions: MorningReviewActions,
    onHowCalculated: () -> Unit,
    onDontShowAgain: () -> Unit,
) {
    ReviewPage(
        navIcon = NavIcon.CLOSE,
        onNavigate = actions.onClose,
        bottomBar = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                SecondaryButton(
                    text = stringResource(R.string.review_adjust),
                    onClick = actions.onAdjust,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.review_looks_right),
                    onClick = actions.onLooksRight,
                    modifier = Modifier.weight(1f),
                )
            }
            TextLinkButton(
                text = stringResource(R.string.review_dont_show),
                onClick = onDontShowAgain,
                color = SleepSaverTheme.colors.textTertiary,
            )
        },
    ) {
        PageHeader(title = nightTitle(state.wakeDate, state.today), subtitle = nightDates(state.wakeDate, state.today))
        Spacer(Modifier.height(Spacing.l))
        NightSummaryCard(night = night, onEdit = actions.onAdjust)
        Spacer(Modifier.height(Spacing.cardGap))
        SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
            val fell = night.fellAsleep
            val woke = night.wokeUp
            val fellZone = night.fellAsleepOffset ?: night.wokeUpOffset
            val wokeZone = night.wokeUpOffset ?: night.fellAsleepOffset
            SummaryRow(
                icon = Icons.Rounded.Bedtime,
                label = stringResource(R.string.review_row_fell_asleep),
                value = if (fell != null && fellZone != null) clockTime(fell, fellZone) else "–",
                onClick = actions.onAdjust,
            )
            SummaryRow(
                icon = Icons.Rounded.WbTwilight,
                label = stringResource(R.string.review_row_woke_up),
                value = if (woke != null && wokeZone != null) clockTime(woke, wokeZone) else "–",
                onClick = actions.onAdjust,
            )
            SummaryRow(
                icon = Icons.Rounded.Visibility,
                label = stringResource(R.string.review_row_awake),
                value = formatDuration(night.awakeInMain),
                onClick = actions.onAdjust,
            )
            SummaryRow(
                icon = Icons.Rounded.Snooze,
                label = stringResource(R.string.review_row_naps),
                value = formatDuration(night.napSleep),
                onClick = actions.onAdjust,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.Center) {
            TextLinkButton(text = stringResource(R.string.review_how_calculated), onClick = onHowCalculated)
        }
    }
}

/** Big total with a pencil, over the night's arc. Tapping anywhere edits. */
@Composable
private fun NightSummaryCard(night: Night, onEdit: () -> Unit) {
    val fell = night.fellAsleep
    val woke = night.wokeUp
    val fellZone = night.fellAsleepOffset ?: night.wokeUpOffset
    val wokeZone = night.wokeUpOffset ?: night.fellAsleepOffset
    SleepSaverCard(Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.review_total_sleep),
                    style = MaterialTheme.typography.labelLarge,
                    color = SleepSaverTheme.colors.textSecondary,
                )
                Text(
                    text = formatDuration(night.totalSleep),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(44.dp)
                    .background(SleepSaverTheme.colors.cardRaised, CircleShape),
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.review_edit_times),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (fell != null && woke != null && fellZone != null && wokeZone != null) {
            val fellText = clockTime(fell, fellZone)
            val wokeText = clockTime(woke, wokeZone)
            val awakeStretches = night.interruptions
            NightArc(
                fellAsleep = fell,
                wokeUp = woke,
                awake = awakeStretches,
                description = pluralStringResource(
                    R.plurals.review_arc_description,
                    awakeStretches.size,
                    fellText,
                    wokeText,
                    awakeStretches.size,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.m)
                    .height(112.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fellText, style = MaterialTheme.typography.labelMedium, color = SleepSaverTheme.colors.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(wokeText, style = MaterialTheme.typography.labelMedium, color = SleepSaverTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    IconListItem(
        icon = icon,
        title = label,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier
                        .padding(start = Spacing.xs)
                        .size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun MissingStep(syncFailed: Boolean, actions: MorningReviewActions) {
    ReviewPage(navIcon = NavIcon.CLOSE, onNavigate = actions.onClose, scrollState = null) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Rounded.CloudSync,
                title = stringResource(R.string.review_missing_title),
                body = stringResource(if (syncFailed) R.string.review_missing_body_error else R.string.review_missing_body),
                primaryActionLabel = stringResource(R.string.review_sync_now),
                onPrimaryAction = actions.onSyncNow,
                secondaryActionLabel = stringResource(R.string.review_add_manually),
                onSecondaryAction = actions.onAdjust,
            )
        }
    }
}

@Composable
private fun ResultStep(state: MorningReviewUiState, update: DebtUpdate, actions: MorningReviewActions, animate: Boolean) {
    val breakdown = update.breakdown
    ReviewPage(
        navIcon = NavIcon.CLOSE,
        onNavigate = actions.onClose,
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.review_done),
                onClick = actions.onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        PageHeader(title = stringResource(R.string.review_result_title), subtitle = nightDates(state.wakeDate, state.today))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.l),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedDebt(update, animate)
        }
        SleepSaverCard(Modifier.fillMaxWidth()) {
            BreakdownRow(
                title = stringResource(R.string.review_result_last_night),
                detail = stringResource(
                    R.string.review_result_need_slept,
                    formatDuration(breakdown.need),
                    formatDuration(breakdown.slept),
                ),
            ) { ChangeChip(breakdown.lastNightDelta, suffix = null) }
            HorizontalDivider(Modifier.padding(vertical = Spacing.m), color = SleepSaverTheme.colors.gridLine)
            BreakdownRow(
                title = stringResource(R.string.review_result_fading),
                detail = stringResource(R.string.review_result_fading_body),
            ) { ChangeChip(breakdown.fade, suffix = null) }
            HorizontalDivider(Modifier.padding(vertical = Spacing.m), color = SleepSaverTheme.colors.gridLine)
            BreakdownRow(
                title = stringResource(R.string.review_result_now),
                detail = stringResource(R.string.review_result_was, formatHours1(breakdown.before)),
            ) {
                Text(
                    text = stringResource(R.string.review_hours_value, formatHours1(breakdown.after)),
                    style = MaterialTheme.typography.titleLarge,
                    color = update.statusAfter.color,
                    textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        WellnessDisclaimer()
    }
}

/** The gauge sweeps from yesterday's debt to today's and takes on the new status colour. */
@Composable
private fun AnimatedDebt(update: DebtUpdate, animate: Boolean) {
    val before = update.breakdown.before.toHours1()
    val after = update.breakdown.after.toHours1()
    val hours = remember(update) { Animatable(if (animate) before else after) }
    var arrived by remember(update) { mutableStateOf(!animate) }
    LaunchedEffect(update, animate) {
        if (!animate) return@LaunchedEffect
        delay(400)
        arrived = true
        hours.animateTo(after, tween(durationMillis = 1_400, easing = FastOutSlowInEasing))
    }
    val status: DebtStatus = if (arrived) update.statusAfter else update.statusBefore
    DebtGauge(
        debt = Duration.ofSeconds((hours.value * 3600).roundToLong().coerceAtLeast(0)),
        status = status,
        size = 220.dp,
        label = stringResource(R.string.review_result_label),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun BreakdownRow(title: String, detail: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing()
    }
}

private fun Duration.toHours1(): Float = toMillis() / 3_600_000f

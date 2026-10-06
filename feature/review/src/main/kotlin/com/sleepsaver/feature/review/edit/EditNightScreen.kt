package com.sleepsaver.feature.review.edit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.CloseWhenFinished
import com.sleepsaver.feature.review.common.ConfirmDialog
import com.sleepsaver.feature.review.common.InlineNote
import com.sleepsaver.feature.review.common.HowWeMeasureSheet
import com.sleepsaver.feature.review.common.NavIcon
import com.sleepsaver.feature.review.common.PageHeader
import com.sleepsaver.feature.review.common.ReviewPage
import com.sleepsaver.feature.review.common.nightDates
import com.sleepsaver.feature.review.common.originLabel

@Composable
internal fun EditNightScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: EditNightViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CloseWhenFinished(state.finished, onClose)
    EditNightContent(
        state = state,
        callbacks = NightEditorCallbacks(
            onFellAsleepChange = viewModel::onFellAsleepChange,
            onWokeUpChange = viewModel::onWokeUpChange,
            onAwakeChange = viewModel::onAwakeChange,
            onNapsChange = viewModel::onNapsChange,
            onHowWeMeasure = {},
        ),
        onCancel = onClose,
        onDone = viewModel::onDone,
        onRevert = viewModel::onRevert,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * Edit a night, or add a missing one. Stateless apart from its dialogs. [callbacks]' onHowWeMeasure is
 * ignored: the sheet is opened here.
 */
@Composable
internal fun EditNightContent(
    state: EditNightUiState,
    callbacks: NightEditorCallbacks,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    val editor = state.editor
    var showHowWeMeasure by rememberSaveable { mutableStateOf(false) }
    var confirmRevert by rememberSaveable { mutableStateOf(false) }
    val formCallbacks = NightEditorCallbacks(
        onFellAsleepChange = callbacks.onFellAsleepChange,
        onWokeUpChange = callbacks.onWokeUpChange,
        onAwakeChange = callbacks.onAwakeChange,
        onNapsChange = callbacks.onNapsChange,
        onHowWeMeasure = { showHowWeMeasure = true },
    )
    ReviewPage(
        navIcon = NavIcon.CLOSE,
        onNavigate = onCancel,
        modifier = modifier,
        scrollState = if (editor != null) scrollState else null,
        bottomBar = if (editor == null) {
            null
        } else {
            {
                EditActions(
                    canSave = editor.error == null && !state.saving,
                    onCancel = onCancel,
                    onDone = onDone,
                )
            }
        },
    ) {
        if (editor == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingOrb() }
        } else {
            PageHeader(
                title = stringResource(if (editor.kind == EditKind.NEW) R.string.review_add_title else R.string.review_edit_title),
                subtitle = nightDates(state.wakeDate, state.today),
            )
            Spacer(Modifier.height(Spacing.l))
            NightEditorForm(state = editor, callbacks = formCallbacks)
            if (state.saveFailed) {
                Spacer(Modifier.height(Spacing.cardGap))
                InlineNote(stringResource(R.string.review_save_failed))
            }
            if (editor.isEdited) {
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextLinkButton(
                        text = stringResource(
                            if (editor.kind == EditKind.MANUAL) R.string.review_remove_night else R.string.review_revert_original,
                        ),
                        onClick = { confirmRevert = true },
                        color = SleepSaverTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
    if (editor != null && confirmRevert) {
        RevertDialog(
            editor = editor,
            onConfirm = {
                confirmRevert = false
                onRevert()
            },
            onDismiss = { confirmRevert = false },
        )
    }
    if (editor != null && showHowWeMeasure) {
        HowWeMeasureSheet(
            source = editor.source,
            onDismiss = { showHowWeMeasure = false },
            onChangeSource = onOpenSettings,
        )
    }
}

/** "Revert this night?" (or "Remove this night?" for one the user added). */
@Composable
internal fun RevertDialog(editor: NightEditorState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val manual = editor.kind == EditKind.MANUAL
    ConfirmDialog(
        title = stringResource(if (manual) R.string.review_remove_title else R.string.review_revert_title),
        body = if (manual) {
            stringResource(R.string.review_remove_body)
        } else {
            stringResource(R.string.review_revert_body, originLabel(editor.source.origin))
        },
        confirmLabel = stringResource(if (manual) R.string.review_remove_confirm else R.string.review_revert_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Cancel / Done, side by side. */
@Composable
internal fun EditActions(canSave: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SecondaryButton(
            text = stringResource(R.string.review_cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = stringResource(R.string.review_done),
            onClick = onDone,
            enabled = canSave,
            modifier = Modifier.weight(1f),
        )
    }
}

package com.sleepsaver.feature.review.nap

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SegmentedControl
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TimeScrubber
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.ConfirmDialog
import com.sleepsaver.feature.review.common.NavIcon
import com.sleepsaver.feature.review.common.PageHeader
import com.sleepsaver.feature.review.common.ReviewPage
import com.sleepsaver.feature.review.detail.NapRow
import java.time.Instant

@Composable
internal fun AddNapScreen(
    onClose: () -> Unit,
    viewModel: AddNapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onClose() }
    AddNapContent(
        state = state,
        onClose = onClose,
        onDayChange = viewModel::onDayChange,
        onStartChange = viewModel::onStartChange,
        onMinutesChange = viewModel::onMinutesChange,
        onSave = viewModel::onSave,
        onDeleteNap = viewModel::onDeleteNap,
    )
}

/** "+ Nap": pick the day, when it started and how long; lists that day's naps. Stateless apart from its dialog. */
@Composable
internal fun AddNapContent(
    state: AddNapUiState,
    onClose: () -> Unit,
    onDayChange: (NapDay) -> Unit,
    onStartChange: (Instant) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onSave: () -> Unit,
    onDeleteNap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var napToDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    ReviewPage(
        navIcon = NavIcon.CLOSE,
        onNavigate = onClose,
        modifier = modifier,
        scrollState = scrollState,
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.review_save_nap),
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        PageHeader(title = stringResource(R.string.review_add_nap_title), subtitle = stringResource(R.string.review_add_nap_subtitle))
        Spacer(Modifier.height(Spacing.xl))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            SegmentedControl(
                options = listOf(stringResource(R.string.review_day_today), stringResource(R.string.review_day_yesterday)),
                selectedIndex = state.day.ordinal,
                onSelectedChange = { onDayChange(NapDay.entries[it]) },
            )
            SleepSaverCard(Modifier.fillMaxWidth()) {
                TimeScrubber(
                    value = state.start,
                    onValueChange = onStartChange,
                    zone = state.zone,
                    min = state.startMin,
                    max = state.startMax,
                    label = stringResource(R.string.review_nap_started),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SleepSaverCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.review_nap_length), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.review_nap_until, clockTime(state.end, state.zone)),
                            style = MaterialTheme.typography.bodySmall,
                            color = SleepSaverTheme.colors.textSecondary,
                        )
                    }
                    MinuteStepper(
                        minutes = state.minutes,
                        onMinutesChange = onMinutesChange,
                        step = AddNapUiState.STEP,
                        min = AddNapUiState.MIN_MINUTES,
                        max = AddNapUiState.MAX_MINUTES,
                    )
                }
            }
            if (state.endsInFuture) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.review_nap_error_future),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = Spacing.m),
                    )
                }
            }
            SectionCard(title = stringResource(R.string.review_naps_that_day), modifier = Modifier.fillMaxWidth()) {
                if (state.naps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.review_naps_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                    )
                }
                state.naps.forEach { nap -> NapRow(nap, onDelete = { napToDelete = it }) }
            }
        }
    }
    napToDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.review_delete_nap_title),
            body = stringResource(R.string.review_delete_nap_body),
            confirmLabel = stringResource(R.string.review_delete),
            onConfirm = {
                napToDelete = null
                onDeleteNap(id)
            },
            onDismiss = { napToDelete = null },
        )
    }
}

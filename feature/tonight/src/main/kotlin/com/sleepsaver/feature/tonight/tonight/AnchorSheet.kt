package com.sleepsaver.feature.tonight.tonight

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.R
import kotlinx.coroutines.launch
import com.sleepsaver.core.ui.R as UiR

/**
 * "Settling in": the sheet behind Drop anchor. The whole-night movement log is opt-in (off by default) and says
 * where the phone goes and that it uses more battery; then Drop anchor ([onDrop] gets the log choice).
 */
@Composable
internal fun AnchorSheet(onDrop: (wholeNightLog: Boolean) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var wholeNightLog by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnchorSheetContent(
            wholeNightLog = wholeNightLog,
            onWholeNightLog = { wholeNightLog = it },
            onDrop = {
                val log = wholeNightLog
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    onDismiss()
                    onDrop(log)
                }
            },
        )
    }
}

/** The sheet's body, separate so it can be screenshot-tested. */
@Composable
internal fun AnchorSheetContent(
    wholeNightLog: Boolean,
    onWholeNightLog: (Boolean) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
    ) {
        Text(
            stringResource(R.string.tonight_anchor_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.tonight_anchor_sheet_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Spacer(Modifier.height(Spacing.m))
        ToggleRow(
            title = stringResource(R.string.tonight_anchor_log),
            checked = wholeNightLog,
            onCheckedChange = onWholeNightLog,
            supporting = stringResource(UiR.string.ss_placement) + " " + stringResource(R.string.tonight_anchor_battery),
            icon = Icons.Rounded.Sensors,
        )
        Spacer(Modifier.height(Spacing.l))
        PrimaryButton(
            text = stringResource(R.string.tonight_drop_anchor),
            onClick = onDrop,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Anchor,
        )
    }
}

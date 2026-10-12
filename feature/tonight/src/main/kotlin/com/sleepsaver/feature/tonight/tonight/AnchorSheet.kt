package com.sleepsaver.feature.tonight.tonight

import androidx.annotation.StringRes
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
import androidx.compose.runtime.remember
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
import com.sleepsaver.feature.tonight.night.NightAlarm
import kotlinx.coroutines.launch
import com.sleepsaver.core.ui.R as UiR

/**
 * "Settling in": the sheet behind Drop anchor. Its first line follows the morning's [alarm] (the Night screen's alarm
 * line), so it never promises an alarm the morning doesn't have. The whole-night movement log is opt-in (off by
 * default) and says where the phone goes and that it uses more battery; then Drop anchor ([onDrop] gets the log
 * choice), once: a second tap while the sheet hides is ignored, since each drop saves an anchor.
 */
@Composable
internal fun AnchorSheet(alarm: NightAlarm, onDrop: (wholeNightLog: Boolean) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var wholeNightLog by rememberSaveable { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AnchorSheetContent(
            alarm = alarm,
            wholeNightLog = wholeNightLog,
            onWholeNightLog = { wholeNightLog = it },
            onDrop = {
                if (!dropping) {
                    dropping = true
                    val log = wholeNightLog
                    // Runs once, also when the hide is cancelled (the sheet leaving composition).
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        onDrop(log)
                    }
                }
            },
        )
    }
}

/** The sheet's body, separate so it can be screenshot-tested. */
@Composable
internal fun AnchorSheetContent(
    alarm: NightAlarm,
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
            stringResource(alarm.anchorBody()),
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

/**
 * The sheet's first line: "Your alarm stays as it is." when the morning has an alarm, "There's no alarm set for the
 * morning." when it has none (none set, a day off or paused), each followed by "Reminders stop for the night."
 */
@StringRes
internal fun NightAlarm.anchorBody(): Int =
    if (this == NightAlarm.None) R.string.tonight_anchor_sheet_body_no_alarm else R.string.tonight_anchor_sheet_body

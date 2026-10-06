package com.sleepsaver.feature.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.TimeScrubber
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.energy.timeline.maxAwakeMinutes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A long press on last night's block: the night, its bounds and the (rounded) time pressed. */
data class AwakeDraft(
    val wakeDate: LocalDate,
    val blockStart: Instant,
    val blockEnd: Instant,
    val start: Instant,
) {
    companion object {
        /** Saves an open dialog's draft (or none) across rotation and process death. */
        val Saver: Saver<AwakeDraft?, Any> = listSaver(
            save = { draft ->
                if (draft == null) {
                    emptyList()
                } else {
                    listOf(
                        draft.wakeDate.toEpochDay(),
                        draft.blockStart.toEpochMilli(),
                        draft.blockEnd.toEpochMilli(),
                        draft.start.toEpochMilli(),
                    )
                }
            },
            restore = { saved ->
                if (saved.size != 4) {
                    null
                } else {
                    AwakeDraft(
                        wakeDate = LocalDate.ofEpochDay(saved[0]),
                        blockStart = Instant.ofEpochMilli(saved[1]),
                        blockEnd = Instant.ofEpochMilli(saved[2]),
                        start = Instant.ofEpochMilli(saved[3]),
                    )
                }
            },
        )
    }
}

internal const val DefaultAwakeMinutes = 15
internal const val MinAwakeMinutes = 5
internal const val MaxAwakeMinutes = 120
private val MinAwake: Duration = Duration.ofMinutes(MinAwakeMinutes.toLong())

@Composable
internal fun AddAwakeDialog(
    draft: AwakeDraft,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onConfirm: (start: Instant, end: Instant) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AddAwakeDialogContent(
            draft = draft,
            zone = zone,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
            modifier = Modifier
                .padding(horizontal = Spacing.l)
                .widthIn(max = 480.dp),
        )
    }
}

/**
 * "Add awake time": when it started (a scrubber kept inside the block, starting at the pressed time) and how long
 * it lasted (5–120 min, never past the end of the block).
 */
@Composable
internal fun AddAwakeDialogContent(
    draft: AwakeDraft,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onConfirm: (start: Instant, end: Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    var startMillis by rememberSaveable(draft) { mutableLongStateOf(draft.start.toEpochMilli()) }
    var minutes by rememberSaveable(draft) { mutableIntStateOf(DefaultAwakeMinutes) }
    // One tap adds one row, however fast the second tap comes.
    var submitted by remember(draft) { mutableStateOf(false) }

    val start = Instant.ofEpochMilli(startMillis)
    val maxMinutes = maxAwakeMinutes(start, draft.blockEnd, MinAwakeMinutes, MaxAwakeMinutes)
    val length = minutes.coerceIn(MinAwakeMinutes, maxMinutes)
    val end = start.plus(Duration.ofMinutes(length.toLong()))
    val latestStart = maxOf(draft.blockStart, draft.blockEnd - MinAwake)

    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(SleepSaverTheme.colors.card)
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.energy_add_awake_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = stringResource(R.string.energy_add_awake_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xl))
        TimeScrubber(
            value = start,
            onValueChange = { startMillis = it.toEpochMilli() },
            zone = zone,
            min = draft.blockStart,
            max = latestStart,
            label = stringResource(R.string.energy_add_awake_from),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.energy_add_awake_for),
            style = MaterialTheme.typography.labelLarge,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.s))
        MinuteStepper(
            minutes = length,
            onMinutesChange = { minutes = it },
            step = 5,
            min = MinAwakeMinutes,
            max = maxMinutes,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = timeRange(start, end, zone),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xl))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextLinkButton(stringResource(R.string.energy_cancel), onClick = { if (!submitted) onDismiss() })
            PrimaryButton(
                text = stringResource(R.string.energy_add_awake_confirm),
                onClick = {
                    if (!submitted) {
                        submitted = true
                        onConfirm(start, end)
                    }
                },
            )
        }
    }
}

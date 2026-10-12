package com.sleepsaver.feature.winddown.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.ui.components.EvidenceBadge
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.howTo
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.platform.DimScreen
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.WinddownSurface
import kotlinx.coroutines.delay

/** How a guided tool's screen dims: not at all (start and end screens), after `steps.dimAfter`, or at once (eyes closed). */
internal enum class ToolDim { OFF, AFTER_DELAY, NOW }

/**
 * A guided tool's frame: the near-black background, a close button, and the screen brightness. With
 * [ToolDim.AFTER_DELAY] the screen drops to `steps.dimBrightness` after `steps.dimAfter`; a touch (or a change of
 * [wakeKey], e.g. typed text) brings it back up for another [SleepConfig] `dimAfter`. [keepScreenOn] while a run's
 * cues are going (see [KeepScreenOn]).
 */
@Composable
internal fun ToolFrame(
    onClose: () -> Unit,
    dim: ToolDim,
    modifier: Modifier = Modifier,
    keepScreenOn: Boolean = false,
    wakeKey: Any? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (keepScreenOn) KeepScreenOn()
    var touches by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(dim == ToolDim.NOW) }
    LaunchedEffect(dim, touches, wakeKey) {
        dimmed = dim == ToolDim.NOW
        if (dim == ToolDim.AFTER_DELAY) {
            delay(config.steps.dimAfter.toMillis())
            dimmed = true
        }
    }
    DimScreen(if (dimmed) config.steps.dimBrightness else null)

    WinddownSurface(
        onBack = onClose,
        backIcon = Icons.Rounded.Close,
        backDescription = stringResource(R.string.winddown_close),
        modifier = modifier.pointerInput(Unit) {
            // Watches touches without consuming them, so the controls underneath still work.
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touches++
                }
            }
        },
        content = content,
    )
}

/**
 * Keeps the screen on (dimmed by the tool) while in composition. A running tool needs it: when the screen times out
 * the app goes to the background, and Android drops vibration from background apps, so an eyes-closed run would lose
 * its cues.
 */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/** A scrolling column with the screen's side padding that ends with the wellness line. */
@Composable
internal fun ToolScrollBody(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = horizontalAlignment,
    ) {
        content()
        WellnessDisclaimer(Modifier.padding(top = Spacing.xxl))
        Spacer(Modifier.height(Spacing.xl))
    }
}

/** A tool's title block on its start screen: "🌊", "Slow tide breathing", the evidence badge and how to do it. */
@Composable
internal fun ToolHeader(technique: Technique, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(technique.emoji, style = MaterialTheme.typography.displaySmall)
        Text(
            technique.title(),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = Spacing.s)
                .semantics { heading() },
        )
        EvidenceBadge(technique.evidence, Modifier.padding(top = Spacing.s))
        Text(
            technique.howTo(),
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

/**
 * A running tool's spoken line ("Breathe in", "Tense your hands…"): big, centred, announced by screen readers as it
 * changes.
 */
@Composable
internal fun CueText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "That's it. Let the rest of the evening be easy." with "Checked off in tonight's steps." when it was, and Done. */
@Composable
internal fun ToolFinished(checkedOff: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    ToolScrollBody(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(Spacing.xxxl * 2))
        Text(
            stringResource(R.string.winddown_tool_finished),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (checkedOff) {
            Row(
                modifier = Modifier.padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.winddown_tool_checked),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SleepSaverTheme.colors.textSecondary,
                )
            }
        }
        PrimaryButton(
            stringResource(R.string.winddown_tool_done),
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxl),
        )
    }
}

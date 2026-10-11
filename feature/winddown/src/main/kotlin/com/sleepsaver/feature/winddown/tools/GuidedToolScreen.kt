package com.sleepsaver.feature.winddown.tools

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.WinddownSurface

/** The tools that have their own screen ([GuidedTool.HUSH] plays inline; [GuidedTool.NONE] has nothing to run). */
internal val ScreenTools: Set<GuidedTool> =
    setOf(GuidedTool.SLOW_TIDE, GuidedTool.BODY_SCAN, GuidedTool.MUSCLE_RELEASE, GuidedTool.TOMORROW_LIST)

/** A route's tool name back to a tool with a screen; null for anything else. */
internal fun screenToolOf(name: String): GuidedTool? = GuidedTool.entries.firstOrNull { it.name == name }?.takeIf { it in ScreenTools }

/** One guided tool, by [tool] (null = an unknown name: "This tool isn't available"). */
@Composable
internal fun GuidedToolScreen(tool: GuidedTool?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    when (tool) {
        GuidedTool.SLOW_TIDE -> SlowTideScreen(onClose, modifier)
        GuidedTool.BODY_SCAN -> BodyScanScreen(onClose, modifier)
        GuidedTool.MUSCLE_RELEASE -> MuscleReleaseScreen(onClose, modifier)
        GuidedTool.TOMORROW_LIST -> TomorrowListScreen(onClose, modifier)
        GuidedTool.HUSH, GuidedTool.NONE, null -> ToolNotFound(onClose, modifier)
    }
}

@Composable
internal fun ToolNotFound(onClose: () -> Unit, modifier: Modifier = Modifier) {
    WinddownSurface(onBack = onClose, modifier = modifier, backIcon = Icons.Rounded.Close, backDescription = stringResource(R.string.winddown_close)) {
        EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = stringResource(R.string.winddown_tool_not_found_title),
            body = stringResource(R.string.winddown_tool_not_found_body),
            primaryActionLabel = stringResource(R.string.winddown_go_back),
            onPrimaryAction = onClose,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
        )
    }
}

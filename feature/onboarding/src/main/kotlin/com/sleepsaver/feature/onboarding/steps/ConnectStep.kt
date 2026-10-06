package com.sleepsaver.feature.onboarding.steps

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.connect.AskListCard
import com.sleepsaver.feature.onboarding.connect.ConnectActions
import com.sleepsaver.feature.onboarding.connect.ConnectStage
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.connect.PermissionSummaryCard
import com.sleepsaver.feature.onboarding.connect.SourceCard
import com.sleepsaver.feature.onboarding.connect.StageActions
import com.sleepsaver.feature.onboarding.connect.stageCopy
import com.sleepsaver.feature.onboarding.ui.ConnectFlowGraphic
import com.sleepsaver.feature.onboarding.ui.StepHeading
import com.sleepsaver.feature.onboarding.ui.StepLayout

/**
 * Step 2: get Health Connect installed, sleep allowed and Garmin sharing. Every dead end has a way out:
 * install/update, try again, open Health Connect, or explore with demo data.
 */
@Composable
internal fun ConnectStep(
    connection: ConnectionUiState,
    busy: Boolean,
    actions: ConnectActions,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val stage = connection.stage
    val bottom: @Composable ColumnScope.() -> Unit = {
        if (stage == ConnectStage.CHECKING) {
            TextLinkButton(stringResource(R.string.onboarding_explore_demo), onClick = actions.onExploreDemo, enabled = !busy)
        } else {
            StageActions(stage, actions, enabled = !busy, demoAsLink = true, onContinue = onContinue)
        }
    }
    when (stage) {
        ConnectStage.CHECKING -> StepLayout(modifier, centered = true, scrollState = scrollState, bottom = bottom) {
            LoadingOrb(label = stringResource(R.string.onboarding_checking))
        }

        ConnectStage.NEEDS_PERMISSION -> StepLayout(modifier, scrollState = scrollState, bottom = bottom) {
            Spacer(Modifier.height(Spacing.l))
            ConnectFlowGraphic()
            Spacer(Modifier.height(Spacing.xxl))
            StepHeading(stringResource(R.string.onboarding_connect_title), stringResource(R.string.onboarding_connect_body))
            Spacer(Modifier.height(Spacing.xl))
            AskListCard()
        }

        ConnectStage.NOT_INSTALLED, ConnectStage.UPDATE_REQUIRED, ConnectStage.NOT_SUPPORTED, ConnectStage.DENIED -> {
            val copy = stageCopy(stage)!!
            StepLayout(modifier, centered = true, scrollState = scrollState, bottom = bottom) {
                EmptyState(icon = copy.icon, title = stringResource(copy.title), body = stringResource(copy.body))
            }
        }

        ConnectStage.CONNECTED -> StepLayout(modifier, scrollState = scrollState, bottom = bottom) {
            StepHeading(stringResource(R.string.onboarding_connected_title), stringResource(R.string.onboarding_connected_body))
            Spacer(Modifier.height(Spacing.xl))
            connection.permissions?.let { permissions ->
                PermissionSummaryCard(permissions, onAllow = actions.onConnect, onManage = actions.onOpenHealthConnect)
            }
            Spacer(Modifier.height(Spacing.cardGap))
            SourceCard(connection.source, connection.garminInstalled, actions)
        }
    }
}

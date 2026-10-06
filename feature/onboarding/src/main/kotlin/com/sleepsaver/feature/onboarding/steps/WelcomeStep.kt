package com.sleepsaver.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.ui.CrescentMark
import com.sleepsaver.feature.onboarding.ui.StepLayout

/** Step 1: the mark, what the app does, where the data lives. */
@Composable
internal fun WelcomeStep(onStart: () -> Unit, onOpenPrivacyPolicy: () -> Unit, modifier: Modifier = Modifier) {
    StepLayout(
        modifier = modifier,
        centered = true,
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.onboarding_privacy_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
            TextLinkButton(stringResource(R.string.onboarding_privacy_link), onClick = onOpenPrivacyPolicy)
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(stringResource(R.string.onboarding_get_started), onClick = onStart, modifier = Modifier.fillMaxWidth())
            WellnessDisclaimer(Modifier.padding(top = Spacing.l))
        },
    ) {
        CrescentMark(size = 136.dp)
        Text(
            text = stringResource(R.string.onboarding_app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Spacing.s)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.m, start = Spacing.s, end = Spacing.s),
        )
        Spacer(Modifier.height(Spacing.xxl))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            IconListItem(
                icon = Icons.Rounded.DataUsage,
                title = stringResource(R.string.onboarding_value_debt_title),
                supporting = stringResource(R.string.onboarding_value_debt_body),
            )
            IconListItem(
                icon = Icons.Rounded.Bolt,
                title = stringResource(R.string.onboarding_value_energy_title),
                supporting = stringResource(R.string.onboarding_value_energy_body),
            )
            IconListItem(
                icon = Icons.Rounded.NotificationsActive,
                title = stringResource(R.string.onboarding_value_habits_title),
                supporting = stringResource(R.string.onboarding_value_habits_body),
            )
        }
    }
}

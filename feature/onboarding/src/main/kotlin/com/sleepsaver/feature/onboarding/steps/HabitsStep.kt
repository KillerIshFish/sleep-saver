package com.sleepsaver.feature.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.description
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.HabitOption
import com.sleepsaver.feature.onboarding.HabitsUiState
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.ui.StepHeading
import com.sleepsaver.feature.onboarding.ui.StepLayout

/** Step 5: which habits to follow and whether to be reminded. */
@Composable
internal fun HabitsStep(
    state: HabitsUiState,
    busy: Boolean,
    onToggle: (HabitType) -> Unit,
    onRemindChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepLayout(
        modifier = modifier,
        bottom = {
            PrimaryButton(stringResource(R.string.onboarding_finish), onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth())
        },
    ) {
        StepHeading(stringResource(R.string.onboarding_habits_title), stringResource(R.string.onboarding_habits_body))
        Spacer(Modifier.height(Spacing.xl))
        RemindCard(remind = state.remind, onRemindChange = onRemindChange)
        Spacer(Modifier.height(Spacing.cardGap))
        SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.s)) {
            state.options.forEachIndexed { index, option ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(start = 74.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                HabitRow(option, state, onToggle)
            }
        }
    }
}

@Composable
private fun RemindCard(remind: Boolean, onRemindChange: (Boolean) -> Unit) {
    SleepSaverCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .toggleable(value = remind, role = Role.Switch, onValueChange = onRemindChange),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (remind) 0.14f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (remind) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                    contentDescription = null,
                    tint = if (remind) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(stringResource(R.string.onboarding_remind_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.onboarding_remind_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = remind,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = SleepSaverTheme.colors.cardRaised,
                    uncheckedThumbColor = SleepSaverTheme.colors.textSecondary,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }
}

@Composable
private fun HabitRow(option: HabitOption, state: HabitsUiState, onToggle: (HabitType) -> Unit) {
    val type = option.type
    IconListItem(
        icon = type.icon,
        title = type.title(),
        supporting = type.description(),
        tint = if (option.selected) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
        modifier = Modifier
            .toggleable(value = option.selected, role = Role.Checkbox, onValueChange = { onToggle(type) })
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.time != null) {
                    Text(
                        text = clockTime(option.time, state.zone),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (option.selected) SleepSaverTheme.colors.textSecondary else SleepSaverTheme.colors.textTertiary,
                    )
                }
                Checkbox(
                    checked = option.selected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(horizontal = Spacing.m),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedColor = SleepSaverTheme.colors.textTertiary,
                    ),
                )
            }
        },
    )
}

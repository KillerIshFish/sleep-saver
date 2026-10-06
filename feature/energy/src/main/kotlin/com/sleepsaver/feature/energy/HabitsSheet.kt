package com.sleepsaver.feature.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.ZoneId

/** What the sheet shows while collapsed: the drag handle plus this header. */
private val HandleArea = 20.dp
private val MinPeekHeader = 56.dp

/**
 * What the sheet shows while collapsed: the handle plus the "Habits" header, which grows with the font size so
 * the next-habit line is never cut off.
 */
@Composable
internal fun sheetPeekHeight(): Dp {
    val title = MaterialTheme.typography.titleMedium.lineHeight
    val body = MaterialTheme.typography.bodySmall.lineHeight
    return with(LocalDensity.current) { HandleArea + maxOf(MinPeekHeader, title.toDp() + body.toDp() + Spacing.l) }
}

/** A small handle; Material's default is taller than our peek allows. */
@Composable
internal fun SheetHandle() {
    Box(Modifier.fillMaxWidth().height(HandleArea), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(SleepSaverTheme.colors.textTertiary.copy(alpha = 0.6f), CircleShape),
        )
    }
}

/**
 * The habits sheet. Collapsed it shows "Habits", what's next and a "+"; expanded it lists every habit (the ones
 * in the plan first) with its time and a reminder bell.
 */
@Composable
internal fun HabitsSheetContent(
    habits: List<ScheduledHabit>,
    nextHabit: ScheduledHabit?,
    zone: ZoneId,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(sheetPeekHeight() - HandleArea)
                .padding(start = Spacing.screenHorizontal, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.energy_habits),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = nextHabit?.let { stringResource(R.string.energy_habits_next, it.type.title(), clockTime(it.time, zone)) }
                        ?: stringResource(R.string.energy_habits_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onAddHabit,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.energy_habits_add))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl),
        ) {
            val (planned, available) = habits.partition { it.enabled }
            if (planned.isNotEmpty()) {
                SheetSection(stringResource(R.string.energy_habits_in_plan))
                planned.forEach { HabitRow(it, zone, onOpenHabit, onToggleReminder) }
            }
            if (available.isNotEmpty()) {
                SheetSection(stringResource(R.string.energy_habits_available))
                available.forEach { HabitRow(it, zone, onOpenHabit, onToggleReminder) }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = SleepSaverTheme.colors.textTertiary,
        modifier = Modifier
            .padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

@Composable
private fun HabitRow(
    habit: ScheduledHabit,
    zone: ZoneId,
    onOpenHabit: (HabitType) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
) {
    val type = habit.type
    val time = habit.windowEnd?.let { timeRange(habit.time, it, zone) } ?: clockTime(habit.time, zone)
    IconListItem(
        icon = type.icon,
        title = type.title(),
        supporting = time,
        tint = if (habit.enabled) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
        onClick = { onOpenHabit(type) },
        modifier = Modifier
            .heightIn(min = 60.dp)
            .padding(start = Spacing.screenHorizontal, end = Spacing.s),
        trailing = {
            if (habit.enabled) {
                IconToggleButton(
                    checked = habit.reminderEnabled,
                    onCheckedChange = { onToggleReminder(type, it) },
                ) {
                    Icon(
                        imageVector = if (habit.reminderEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        contentDescription = stringResource(R.string.energy_reminder_for, type.title()),
                        tint = if (habit.reminderEnabled) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.energy_habit_off),
                    style = MaterialTheme.typography.labelMedium,
                    color = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier.padding(end = Spacing.m),
                )
            }
        },
    )
}

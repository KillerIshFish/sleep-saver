package com.sleepsaver.feature.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.ui.components.CardPadding
import com.sleepsaver.core.ui.components.ChangeChip
import com.sleepsaver.core.ui.components.DebtGauge
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PhaseCard
import com.sleepsaver.core.ui.components.PhaseCardDefaults
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.StreakBadge
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.format.formatShift
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Duration

private val ScreenPadding = Modifier.padding(horizontal = Spacing.screenHorizontal)

@Composable
internal fun ColumnScope.ReadyBody(state: SleepUiState.Ready, actions: SleepActions, onOpenSource: () -> Unit) {
    val banner = state.banner
    if (banner != null) {
        Spacer(Modifier.height(Spacing.xl))
        LastNightBannerCard(
            banner = banner,
            state = state,
            actions = actions,
            modifier = ScreenPadding.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(if (banner != null) Spacing.xl else Spacing.l))
    DebtSummary(state, actions, onOpenSource)

    state.needSuggestion?.let { suggestion ->
        Spacer(Modifier.height(Spacing.xl))
        NeedSuggestionCard(
            suggestion = suggestion,
            onUse = actions.onUseSuggestedNeed,
            onDismiss = actions.onDismissNeedSuggestion,
            modifier = ScreenPadding.fillMaxWidth(),
        )
    }
    if (state.missingNights.isNotEmpty()) {
        Spacer(Modifier.height(if (state.needSuggestion != null) Spacing.cardGap else Spacing.xl))
        MissingNightsCard(state, actions, ScreenPadding.fillMaxWidth())
    }

    Spacer(Modifier.height(Spacing.xl))
    EnergySection(state, actions)

    state.nextHabit?.let { habit ->
        Spacer(Modifier.height(Spacing.l))
        NextUpCard(
            habit = habit,
            state = state,
            onOpenHabits = actions.onOpenHabits,
            onReminderChange = actions.onReminderChange,
            modifier = ScreenPadding.fillMaxWidth(),
        )
    }

    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(Spacing.xxl))
    WellnessDisclaimer(ScreenPadding)
}

// ---------------------------------------------------------------- Last-night banner

@Composable
private fun LastNightBannerCard(
    banner: LastNightBanner,
    state: SleepUiState.Ready,
    actions: SleepActions,
    modifier: Modifier = Modifier,
) {
    when (banner) {
        LastNightBanner.WAITING -> BannerCard(
            icon = Icons.Rounded.HourglassTop,
            title = when (val source = state.source) {
                SourceLabel.Garmin -> stringResource(R.string.sleep_waiting_title_garmin)
                is SourceLabel.App -> stringResource(R.string.sleep_waiting_title_app, source.name)
                SourceLabel.AllSources, SourceLabel.Demo -> stringResource(R.string.sleep_waiting_title_generic)
            },
            body = stringResource(
                if (state.syncOpensGarmin) R.string.sleep_waiting_body_garmin else R.string.sleep_waiting_body_generic,
            ),
            modifier = modifier,
        ) {
            // The orb takes the link's place while a sync runs, so the line doesn't jump.
            Box(Modifier.heightIn(min = BannerActionHeight), contentAlignment = Alignment.CenterStart) {
                if (state.isSyncing) {
                    SyncingIndicator(Modifier.offset(x = (-6).dp))
                } else {
                    BannerAction(stringResource(R.string.sleep_sync_now), actions.onSyncNowViaGarmin)
                }
            }
        }
        LastNightBanner.MISSING -> BannerCard(
            icon = Icons.Rounded.EventBusy,
            title = stringResource(R.string.sleep_missing_title),
            body = stringResource(R.string.sleep_missing_body),
            modifier = modifier,
        ) {
            BannerAction(stringResource(R.string.sleep_missing_action), onClick = { actions.onAddMissingNight(state.today) })
        }
        LastNightBanner.REVIEW_READY -> SleepSaverCard(
            modifier = modifier,
            onClick = { actions.onOpenReview(state.today) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Insights)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sleep_review_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.sleep_review_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textTertiary,
                )
            }
        }
    }
}

/** A text button's minimum touch target. */
private val BannerActionHeight = 48.dp

/** Icon + title + body, with an optional action underneath, aligned with the text. */
@Composable
private fun BannerCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionSpacing: Dp = Spacing.xs,
    /** Bottom padding under [action]; a text action's touch target already carries its own. */
    actionBottomPadding: Dp = 8.dp,
    action: (@Composable () -> Unit)? = null,
) {
    SleepSaverCard(
        modifier = modifier,
        contentPadding = if (action == null) CardPadding else PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = actionBottomPadding),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                )
                if (action != null) {
                    Spacer(Modifier.height(actionSpacing))
                    action()
                }
            }
        }
    }
}

/** A tide text action whose label lines up with the banner text (the button's own padding hangs outside). */
@Composable
private fun BannerAction(text: String, onClick: () -> Unit) {
    TextLinkButton(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = BannerActionHeight)
            .offset(x = (-12).dp),
    )
}

@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun SyncingIndicator(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LoadingOrb(size = 32.dp)
        Text(
            text = stringResource(R.string.sleep_syncing),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

// ---------------------------------------------------------------- Debt gauge + chips

@Composable
private fun DebtSummary(state: SleepUiState.Ready, actions: SleepActions, onOpenSource: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.debtIsFromYesterday) {
            DebtGauge(state.debt, state.status, label = stringResource(R.string.sleep_debt_label_yesterday))
        } else {
            DebtGauge(state.debt, state.status)
        }
        state.debtChange?.let { change ->
            Spacer(Modifier.height(Spacing.m))
            if (state.debtIsFromYesterday) {
                ChangeChip(change, suffix = stringResource(R.string.sleep_change_since_day_before))
            } else {
                ChangeChip(change)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        FlowRow(
            modifier = Modifier.padding(horizontal = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // A zero streak says nothing useful, so the badge waits until there is one.
            if (state.streakDays > 0) StreakBadge(days = state.streakDays)
            state.lastNightSleep?.let { slept ->
                InfoChip(
                    text = stringResource(R.string.sleep_last_night_chip, formatDuration(slept)),
                    icon = Icons.Rounded.Bedtime,
                    onClick = { actions.onOpenNight(state.today) },
                )
            }
            val sourceName = state.source.shortName()
            val sourceDescription = stringResource(R.string.sleep_source_chip_description, sourceName)
            InfoChip(
                text = sourceName,
                icon = state.source.icon,
                onClick = onOpenSource,
                modifier = Modifier.semantics { contentDescription = sourceDescription },
            )
            InfoChip(
                text = stringResource(R.string.sleep_nap_chip),
                icon = Icons.Rounded.Add,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = actions.onAddNap,
            )
        }
    }
}

internal val SourceLabel.icon: ImageVector
    get() = when (this) {
        SourceLabel.Garmin -> Icons.Rounded.Watch
        SourceLabel.AllSources -> Icons.Rounded.Layers
        SourceLabel.Demo -> Icons.Rounded.Science
        is SourceLabel.App -> Icons.Rounded.Smartphone
    }

@Composable
@ReadOnlyComposable
internal fun SourceLabel.shortName(): String = when (this) {
    SourceLabel.Garmin -> stringResource(R.string.sleep_source_garmin)
    SourceLabel.AllSources -> stringResource(R.string.sleep_source_all)
    SourceLabel.Demo -> stringResource(R.string.sleep_source_demo)
    is SourceLabel.App -> name
}

// ---------------------------------------------------------------- Sleep need suggestion

@Composable
private fun NeedSuggestionCard(
    suggestion: NeedSuggestion,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val need = formatDuration(suggestion.need).unbreakable()
    SleepSaverCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.sleep_need_overline),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.sleep_need_title, need),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = pluralStringResource(R.plurals.sleep_need_basis, suggestion.freeNights, suggestion.freeNights) +
                " " + stringResource(suggestion.confidence.wordingRes),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.l))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(text = stringResource(R.string.sleep_need_use, need), onClick = onUse)
            TextLinkButton(
                text = stringResource(R.string.sleep_need_not_now),
                onClick = onDismiss,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
    }
}

private val Confidence.wordingRes: Int
    get() = when (this) {
        Confidence.HIGH -> R.string.sleep_need_confidence_high
        Confidence.MEDIUM -> R.string.sleep_need_confidence_medium
        Confidence.LOW -> R.string.sleep_need_confidence_low
    }

// ---------------------------------------------------------------- Missing nights

@Composable
private fun MissingNightsCard(state: SleepUiState.Ready, actions: SleepActions, modifier: Modifier = Modifier) {
    val count = state.missingNights.size
    BannerCard(
        icon = Icons.Rounded.EventBusy,
        title = pluralStringResource(R.plurals.sleep_missing_nights_title, count, count),
        body = stringResource(R.string.sleep_missing_nights_body),
        modifier = modifier,
        actionSpacing = Spacing.m,
        actionBottomPadding = 20.dp,
    ) {
        // A new watch can leave most of the window empty: show the newest few and keep the rest one tap away.
        var expanded by rememberSaveable { mutableStateOf(false) }
        val shown = if (expanded || count <= MaxMissingChips) state.missingNights else state.missingNights.take(MaxMissingChips - 1)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            shown.forEach { date ->
                val label = formatNightLabel(date, state.today)
                val description = stringResource(R.string.sleep_add_night_description, label)
                InfoChip(
                    text = label,
                    icon = Icons.Rounded.Add,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { actions.onAddMissingNight(date) },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
            val hidden = count - shown.size
            if (hidden > 0) {
                InfoChip(
                    text = pluralStringResource(R.plurals.sleep_missing_nights_more, hidden, hidden),
                    icon = Icons.Rounded.ExpandMore,
                    onClick = { expanded = true },
                )
            }
        }
    }
}

/** Chips shown before the rest fold into "N more" (three rows at most on a phone). */
private const val MaxMissingChips = 6

// ---------------------------------------------------------------- Energy

@Composable
private fun EnergySection(state: SleepUiState.Ready, actions: SleepActions) {
    Column(Modifier.fillMaxWidth()) {
        // The row's ripple reaches 8 dp past the text on each side.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal - Spacing.s)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = actions.onOpenEnergy)
                .padding(horizontal = Spacing.s, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sleep_energy_potential),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        if (state.phasesAreTonight) R.string.sleep_energy_caption_tonight else R.string.sleep_energy_caption,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                )
            }
            Text(
                text = stringResource(R.string.sleep_energy_potential_value, state.energyPotential),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.m),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = SleepSaverTheme.colors.textTertiary,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
        if (state.phases.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.m))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .height(IntrinsicSize.Max)
                    .padding(horizontal = Spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                val card = Modifier
                    .width(PhaseCardDefaults.CompactWidth)
                    .fillMaxHeight()
                state.phases.forEach { item ->
                    val phase = item.phase
                    PhaseCard(
                        type = phase.type,
                        timeRange = if (item.isCurrent) {
                            stringResource(R.string.sleep_phase_until, clockTime(phase.end, state.zone).unbreakable())
                        } else {
                            timeRange(phase.start, phase.end, state.zone).keepTimesTogether()
                        },
                        modifier = card,
                        shift = formatShift(phase.shiftVsYesterday),
                        highlighted = item.isCurrent,
                        onClick = actions.onOpenEnergy,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Next up

@Composable
private fun NextUpCard(
    habit: NextHabit,
    state: SleepUiState.Ready,
    onOpenHabits: () -> Unit,
    onReminderChange: (com.sleepsaver.core.domain.model.HabitType, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowEnd = habit.windowEnd
    val supporting = if (windowEnd != null && !habit.time.isAfter(state.now)) {
        stringResource(R.string.sleep_next_up_now_until, clockTime(windowEnd, state.zone).unbreakable())
    } else {
        val at = if (windowEnd != null) {
            timeRange(habit.time, windowEnd, state.zone).keepTimesTogether()
        } else {
            clockTime(habit.time, state.zone).unbreakable()
        }
        val wait = Duration.between(state.now, habit.time).coerceAtLeast(Duration.ofMinutes(1))
        stringResource(R.string.sleep_next_up_at_in, at, formatDuration(wait).unbreakable())
    }
    SectionCard(
        title = stringResource(R.string.sleep_next_up),
        actionLabel = stringResource(R.string.sleep_next_up_all),
        onAction = onOpenHabits,
        onClick = onOpenHabits,
        modifier = modifier,
    ) {
        IconListItem(
            icon = habit.type.icon,
            title = habit.type.title(),
            supporting = supporting,
            trailing = {
                IconToggleButton(
                    checked = habit.reminderOn,
                    onCheckedChange = { onReminderChange(habit.type, it) },
                ) {
                    Icon(
                        imageVector = if (habit.reminderOn) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        contentDescription = stringResource(R.string.sleep_reminder),
                        tint = if (habit.reminderOn) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
                    )
                }
            },
        )
    }
}

/** "8h 35m" or "9:30 AM" never splits across lines. */
internal fun String.unbreakable(): String = replace(' ', '\u00A0')

/** "9:30 AM – 12:30 PM" may wrap at the dash, but never between a time and its AM/PM. */
internal fun String.keepTimesTogether(): String = replace(" AM", "\u00A0AM").replace(" PM", "\u00A0PM")

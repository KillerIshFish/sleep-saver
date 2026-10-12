package com.sleepsaver.feature.review.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.StageTotals
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.MarkChips
import com.sleepsaver.core.ui.components.MorningFeelScale
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.formatAboutMinutes
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.ConfirmDialog
import com.sleepsaver.feature.review.common.DataSourceCard
import com.sleepsaver.feature.review.common.HowWeMeasureSheet
import com.sleepsaver.feature.review.common.NavIcon
import com.sleepsaver.feature.review.common.PageHeader
import com.sleepsaver.feature.review.common.ReviewPage
import com.sleepsaver.feature.review.common.manualNapId
import com.sleepsaver.feature.review.common.nightDates
import com.sleepsaver.feature.review.common.nightLabel
import com.sleepsaver.feature.review.common.originLabel
import com.sleepsaver.feature.review.common.originLabelRes
import com.sleepsaver.feature.review.landing.WakeLandingCard
import java.time.Duration
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
internal fun NightDetailScreen(
    onBack: () -> Unit,
    onAdjust: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NightDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NightDetailContent(
        state = state,
        onBack = onBack,
        onAdjust = onAdjust,
        onDeleteNap = viewModel::onDeleteNap,
        onRevert = viewModel::onRevert,
        onOpenSettings = onOpenSettings,
        onMarkToggle = viewModel::onMarkToggle,
        onFeel = viewModel::onFeel,
    )
}

/**
 * One night in full: totals, stages, marks, Morning feel, Drop anchor, Wake landing, interruptions, naps and where it
 * came from. Stateless apart from dialogs.
 */
@Composable
internal fun NightDetailContent(
    state: NightDetailUiState,
    onBack: () -> Unit,
    onAdjust: () -> Unit,
    onDeleteNap: (Long) -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    onMarkToggle: (Mark) -> Unit = {},
    onFeel: (MorningFeel) -> Unit = {},
) {
    var howWeMeasure by rememberSaveable { mutableStateOf(false) }
    var napToDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmRevert by rememberSaveable { mutableStateOf(false) }
    val night = state.night

    ReviewPage(
        navIcon = NavIcon.BACK,
        onNavigate = onBack,
        modifier = modifier,
        scrollState = if (state.loading) null else scrollState,
    ) {
        PageHeader(
            title = nightLabel(state.wakeDate, state.today),
            subtitle = nightDates(state.wakeDate, state.today),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingOrb() }
            night == null || !night.isPresent -> {
                Spacer(Modifier.height(Spacing.xxxl))
                EmptyState(
                    icon = Icons.Rounded.NightsStay,
                    title = stringResource(R.string.review_detail_missing_title),
                    body = stringResource(R.string.review_detail_missing_body),
                    primaryActionLabel = stringResource(R.string.review_add_it),
                    onPrimaryAction = onAdjust,
                )
                if (night != null && night.naps.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xxl))
                    NapsCard(night, onDelete = { napToDelete = it })
                }
            }
            else -> {
                val zone: ZoneId = night.wokeUpOffset ?: night.fellAsleepOffset ?: state.source?.offset ?: ZoneId.systemDefault()
                Spacer(Modifier.height(Spacing.l))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                    OverviewCard(night, zone)
                    val main = night.main
                    if (main != null) StagesCard(night, main, zone, state.source?.origin)
                    MarksCard(state, onMarkToggle)
                    FeelCard(state, onFeel)
                    state.anchor?.let { AnchorCard(it) }
                    state.landing?.let { landing ->
                        WakeLandingCard(landing = landing, night = night, compact = true, movement = state.movement)
                    }
                    InterruptionsCard(night, zone)
                    NapsCard(night, onDelete = { napToDelete = it })
                    state.source?.let { DataSourceCard(it, onHowWeMeasure = { howWeMeasure = true }) }
                }
                Spacer(Modifier.height(Spacing.xl))
                SecondaryButton(
                    text = stringResource(R.string.review_adjust),
                    onClick = onAdjust,
                    icon = Icons.Rounded.Edit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (night.isEdited) {
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.Center) {
                        TextLinkButton(
                            text = if (night.isManual) {
                                stringResource(R.string.review_remove_night)
                            } else {
                                stringResource(R.string.review_revert_to_source, originLabel(night.origin))
                            },
                            onClick = { confirmRevert = true },
                            color = SleepSaverTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }

    napToDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.review_delete_nap_title),
            body = stringResource(R.string.review_delete_nap_body),
            confirmLabel = stringResource(R.string.review_delete),
            onConfirm = {
                napToDelete = null
                onDeleteNap(id)
            },
            onDismiss = { napToDelete = null },
        )
    }
    if (confirmRevert && night != null) {
        val manual = night.isManual
        ConfirmDialog(
            title = stringResource(if (manual) R.string.review_remove_title else R.string.review_revert_title),
            body = if (manual) {
                stringResource(R.string.review_remove_body)
            } else {
                stringResource(R.string.review_revert_body, originLabel(night.origin))
            },
            confirmLabel = stringResource(if (manual) R.string.review_remove_confirm else R.string.review_revert_confirm),
            onConfirm = {
                confirmRevert = false
                onRevert()
            },
            onDismiss = { confirmRevert = false },
        )
    }
    if (howWeMeasure) {
        HowWeMeasureSheet(source = state.source, onDismiss = { howWeMeasure = false }, onChangeSource = onOpenSettings)
    }
}

@Composable
private fun OverviewCard(night: Night, zone: ZoneId) {
    val fell = night.fellAsleep
    val woke = night.wokeUp
    SleepSaverCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.review_total_sleep),
                style = MaterialTheme.typography.labelLarge,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            when {
                night.isManual -> InfoChip(stringResource(R.string.review_detail_manual), icon = Icons.Rounded.EditNote)
                night.isEdited -> InfoChip(stringResource(R.string.review_detail_edited), icon = Icons.Rounded.Edit)
            }
        }
        Text(
            text = formatDuration(night.totalSleep),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (fell != null && woke != null) {
            Text(
                text = timeRange(fell, woke, zone),
                style = MaterialTheme.typography.bodyLarge,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        if (night.extraSleep >= Duration.ofMinutes(1)) {
            Text(
                text = stringResource(R.string.review_detail_extra, formatDuration(night.extraSleep)),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = Spacing.l), color = SleepSaverTheme.colors.gridLine)
        Row(Modifier.fillMaxWidth()) {
            Stat(stringResource(R.string.review_detail_in_bed), night.timeInBed?.let(::formatDuration) ?: "–", Modifier.weight(1f))
            Stat(stringResource(R.string.review_detail_awake), formatDuration(night.awakeInMain), Modifier.weight(1f))
            Stat(
                stringResource(R.string.review_detail_efficiency),
                night.efficiency?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textSecondary)
    }
}

@Composable
private fun StagesCard(night: Night, main: SleepBlock, zone: ZoneId, origin: String?) {
    val totals = night.stageTotals
    SectionCard(title = stringResource(R.string.review_detail_stages), modifier = Modifier.fillMaxWidth()) {
        if (totals != null && totals.hasStageData) {
            Hypnogram(
                block = main,
                zone = zone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
            )
            StageLegend(totals, night.timeInBed ?: Duration.between(main.start, main.end))
        } else {
            Column {
                SleepTimelineBar(
                    block = main,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                    val axis = MaterialTheme.typography.labelMedium
                    Text(clockTime(main.start, main.startOffset), style = axis, color = SleepSaverTheme.colors.textTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(clockTime(main.end, main.endOffset), style = axis, color = SleepSaverTheme.colors.textTertiary)
                }
            }
            Text(
                text = when {
                    night.isManual -> stringResource(R.string.review_no_stages_manual)
                    originLabelRes(origin) != null -> stringResource(R.string.review_no_stages, originLabel(origin))
                    else -> stringResource(R.string.review_no_stages_generic)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
    }
}

/** The night's marks: tickable while [NightDetailUiState.marksEditable]; afterwards only the ones set, read-only. */
@Composable
private fun MarksCard(state: NightDetailUiState, onToggle: (Mark) -> Unit) {
    val editable = state.marksEditable
    if (!editable && state.marks.isEmpty()) return
    SectionCard(title = stringResource(R.string.review_detail_marks), modifier = Modifier.fillMaxWidth()) {
        MarkChips(
            selected = state.marks,
            onToggle = onToggle,
            marks = if (editable) Mark.stored else Mark.stored.filter { it in state.marks },
            enabled = editable,
        )
    }
}

/** Morning feel: the five words while it can still change, then the answer as plain text (nothing if unanswered). */
@Composable
private fun FeelCard(state: NightDetailUiState, onFeel: (MorningFeel) -> Unit) {
    val feel = state.feel
    if (!state.feelEditable && feel == null) return
    SectionCard(title = stringResource(R.string.review_detail_feel), modifier = Modifier.fillMaxWidth()) {
        if (state.feelEditable) {
            MorningFeelScale(value = feel, onValueChange = onFeel)
        } else if (feel != null) {
            Text(feel.label(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * "Anchor dropped 10:58 PM · fell asleep about 15 min later · 91% of the time after it asleep" (missing parts left out).
 * A latency that rounds to zero (the watch saw sleep start at, or up to 30 min before, the anchor) reads "fell asleep
 * right away", never "about 5 min later".
 */
@Composable
private fun AnchorCard(stats: AnchorNightStats) {
    val parts = buildList {
        add(stringResource(R.string.review_anchor_dropped, clockTime(stats.anchor.at, stats.anchor.offset)))
        stats.latencyShown?.let { latency ->
            add(
                if (latency.isZero || latency.isNegative) {
                    stringResource(R.string.review_anchor_latency_none)
                } else {
                    stringResource(R.string.review_anchor_latency, formatAboutMinutes(latency))
                },
            )
        }
        stats.efficiency?.let { add(stringResource(R.string.review_anchor_efficiency, (it * 100).roundToInt())) }
    }
    val line = parts.reduce { text, part -> stringResource(R.string.review_joined, text, part) }.keepTimesTogether()
    SleepSaverCard(Modifier.fillMaxWidth()) {
        IconListItem(icon = Icons.Rounded.Anchor, title = line)
    }
}

@Composable
private fun StageLegend(totals: StageTotals, inBed: Duration) {
    val colors = SleepSaverTheme.colors
    val items = buildList {
        add(Triple(stringResource(R.string.review_stage_deep), colors.stageDeep, totals.deep))
        add(Triple(stringResource(R.string.review_stage_light), colors.stageLight, totals.light))
        add(Triple(stringResource(R.string.review_stage_rem), colors.stageRem, totals.rem))
        add(Triple(stringResource(R.string.review_stage_awake), colors.stageAwake, totals.awake))
        if (totals.unclassified >= Duration.ofMinutes(1)) {
            add(Triple(stringResource(R.string.review_stage_asleep), colors.textTertiary, totals.unclassified))
        }
    }
    val total = inBed.toMillis().coerceAtLeast(1L).toDouble()
    Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (name, color, duration) ->
                    LegendItem(
                        name = name,
                        color = color,
                        duration = formatDuration(duration),
                        percent = "${(duration.toMillis() / total * 100).roundToInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LegendItem(name: String, color: Color, duration: String, percent: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Column(Modifier.padding(start = Spacing.s)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = SleepSaverTheme.colors.textSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(duration, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = percent,
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun InterruptionsCard(night: Night, zone: ZoneId) {
    SectionCard(title = stringResource(R.string.review_interruptions), modifier = Modifier.fillMaxWidth()) {
        if (night.interruptions.isEmpty()) {
            Text(
                text = stringResource(R.string.review_interruptions_none),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        night.interruptions.forEach { period ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(timeRange(period.start, period.end, zone), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(
                            when (period.kind) {
                                AwakeKind.STAGE -> R.string.review_interruption_stage
                                AwakeKind.GAP -> R.string.review_interruption_gap
                                AwakeKind.ADDED -> R.string.review_interruption_added
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = SleepSaverTheme.colors.textSecondary,
                    )
                }
                Text(formatDuration(period.duration), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun NapsCard(night: Night, onDelete: (Long) -> Unit) {
    SectionCard(title = stringResource(R.string.review_naps_title), modifier = Modifier.fillMaxWidth()) {
        if (night.naps.isEmpty()) {
            Text(
                text = stringResource(R.string.review_naps_none),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        night.naps.forEach { nap -> NapRow(nap, onDelete) }
    }
}

/** One nap: time, how long asleep, who recorded it, and a delete button for naps the user added. */
@Composable
internal fun NapRow(nap: SleepBlock, onDelete: (Long) -> Unit, modifier: Modifier = Modifier) {
    val manualId = nap.manualNapId
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Snooze, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.m)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(timeRange(nap.start, nap.end, nap.startOffset), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.review_joined,
                    stringResource(R.string.review_nap_asleep, formatDuration(nap.asleep)),
                    if (manualId != null) {
                        stringResource(R.string.review_nap_manual)
                    } else {
                        stringResource(R.string.review_nap_recorded, originLabel(nap.origin))
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        if (manualId != null) {
            IconButton(onClick = { onDelete(manualId) }) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.review_delete_nap),
                    tint = SleepSaverTheme.colors.textSecondary,
                )
            }
        }
    }
}

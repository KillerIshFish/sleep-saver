package com.sleepsaver.feature.winddown.library

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.nightsteps.StepCategory
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.ui.components.EvidenceBadge
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.howTo
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.source
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.components.why
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.LOG_TAG
import com.sleepsaver.feature.winddown.LoadingBody
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.UnavailableBody
import com.sleepsaver.feature.winddown.WinddownSurface
import com.sleepsaver.feature.winddown.WinddownWriter
import com.sleepsaver.feature.winddown.navigation.StepLibraryRoute
import com.sleepsaver.feature.winddown.steps.EmojiCircle
import com.sleepsaver.feature.winddown.steps.textRes
import com.sleepsaver.feature.winddown.tools.ScreenTools
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

// ---------------------------------------------------------------- State

/** One library group: "Breathing", "Body", "Mind", "Room", "Routine". */
internal data class LibrarySection(val category: StepCategory, val techniques: List<Technique>)

internal sealed interface StepLibraryUiState {
    data object Loading : StepLibraryUiState

    data object Unavailable : StepLibraryUiState

    data class Ready(
        val suggestions: List<StepSuggestion>,
        val sections: List<LibrarySection>,
        /** Techniques already in the list "Add" adds to (the every-evening list, or the day's own list). */
        val inSteps: Set<Technique>,
    ) : StepLibraryUiState
}

/**
 * Screen-based techniques (TV, Gaming) are never listed; groups keep StepCategory order, techniques enum order.
 * [ownList] = the edited day's own list (activity ids); null = the every-evening list ("selected").
 */
internal fun stepLibraryUiState(
    suggestions: List<StepSuggestion>,
    activities: List<RoutineActivity>,
    ownList: List<Long>? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
): StepLibraryUiState.Ready {
    val listed = Technique.entries.filter { !it.screenBased }
    val inList = if (ownList == null) activities.filter { it.selected } else activities.filter { it.id in ownList }
    return StepLibraryUiState.Ready(
        suggestions = suggestions.filter { !it.technique.screenBased }.take(config.steps.suggestionMax),
        sections = StepCategory.entries.mapNotNull { category ->
            listed.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { LibrarySection(category, it) }
        },
        inSteps = inList.mapNotNullTo(LinkedHashSet()) { it.technique },
    )
}

/**
 * Adds [technique] to the list tonight's "Add" works on: the every-evening list, or [day]'s own list when it has one.
 * There it goes on that list only: addTechnique selects it for every evening, so that is undone unless it was already
 * there. Call it inside one [WinddownWriter.save], so nothing can land in between. [label] = the technique's title,
 * resolved in composition.
 */
internal suspend fun addToSteps(
    technique: Technique,
    label: String,
    day: DayOfWeek?,
    nightSteps: NightStepsRepository,
    schedule: ScheduleRepository,
) {
    val own = day?.let { schedule.weekPlan.first().day(it).evening.routine }
    val wasEveryEvening = nightSteps.activities.first().any { it.technique == technique && it.selected }
    val id = nightSteps.addTechnique(technique, label)
    if (day != null && own != null) {
        if (!wasEveryEvening) nightSteps.setIncluded(id, false)
        if (id !in own) schedule.setDayRoutine(day, own + id)
    }
}

/**
 * The step library: every technique with how-to, evidence and why it helps. "Add" puts it in the every-evening list,
 * or, opened for a day with its own list ([StepLibraryRoute.day]: from that day's editor, or from Night steps on such
 * an evening), on that day's list only.
 */
@HiltViewModel
internal class StepLibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val nightSteps: NightStepsRepository,
    private val schedule: ScheduleRepository,
    private val writer: WinddownWriter,
) : ViewModel() {

    /** StepLibraryRoute.day; null = every evening. */
    private val day: DayOfWeek? = runCatching { savedStateHandle.toRoute<StepLibraryRoute>().day }
        .getOrDefault(0)
        .takeIf { it in 1..7 }
        ?.let { DayOfWeek.of(it) }

    /** The edited day's own list, or null (every evening, or the day follows it). */
    private val ownList: Flow<List<Long>?> =
        if (day == null) flowOf(null) else schedule.weekPlan.map { it.day(day).evening.routine }.distinctUntilChanged()

    val uiState: StateFlow<StepLibraryUiState> = combine(
        nightSteps.suggestions.catch {
            Log.w(LOG_TAG, "Suggestions failed", it)
            emit(emptyList())
        },
        nightSteps.activities,
        ownList,
    ) { suggestions, activities, own -> stepLibraryUiState(suggestions, activities, own) }
        .catch<StepLibraryUiState> {
            Log.w(LOG_TAG, "Loading the step library failed", it)
            emit(StepLibraryUiState.Unavailable)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepLibraryUiState.Loading)

    /**
     * [label] is the technique's title, resolved in composition (never inside the click lambda). On a day with its own
     * list the step goes on that list only ([addToSteps]).
     */
    fun add(technique: Technique, label: String) {
        viewModelScope.launch { writer.save("Adding $technique") { addToSteps(technique, label, day, nightSteps, schedule) } }
    }
}

// ---------------------------------------------------------------- Screen

@Stable
internal class StepLibraryActions(
    val onBack: () -> Unit = {},
    /** [label] = the technique's title, resolved in composition. */
    val onAdd: (Technique, String) -> Unit = { _, _ -> },
    val onTry: (GuidedTool) -> Unit = {},
)

@Composable
internal fun StepLibraryScreen(
    onBack: () -> Unit,
    onOpenTool: (GuidedTool) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StepLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StepLibraryContent(state, StepLibraryActions(onBack, viewModel::add, onOpenTool), modifier)
}

/**
 * "Suggested for you" (with reasons), then the groups. Each card: emoji, title, evidence, how-to, an expandable
 * "Why it helps" (why + source), and "Add" / "In your steps" plus "Try now" for a guided tool. [expanded] opens
 * those cards' "Why it helps" (screenshots).
 */
@Composable
internal fun StepLibraryContent(
    state: StepLibraryUiState,
    actions: StepLibraryActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    expanded: Set<Technique> = emptySet(),
) {
    WinddownSurface(onBack = actions.onBack, modifier = modifier) {
        when (state) {
            StepLibraryUiState.Loading -> LoadingBody()
            StepLibraryUiState.Unavailable -> UnavailableBody(actions.onBack)
            is StepLibraryUiState.Ready -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = Spacing.screenHorizontal),
            ) {
                ScreenHeader(
                    title = stringResource(R.string.winddown_library_title),
                    subtitle = stringResource(R.string.winddown_library_subtitle),
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                if (state.suggestions.isNotEmpty()) {
                    GroupTitle(stringResource(R.string.winddown_library_suggested))
                    state.suggestions.forEach { suggestion ->
                        TechniqueCard(
                            technique = suggestion.technique,
                            reason = suggestion.reason,
                            inSteps = suggestion.technique in state.inSteps,
                            initiallyExpanded = suggestion.technique in expanded,
                            actions = actions,
                            keySuffix = "suggested",
                        )
                    }
                }
                state.sections.forEach { section ->
                    GroupTitle(section.category.title(), section.category)
                    section.techniques.forEach { technique ->
                        TechniqueCard(
                            technique = technique,
                            reason = null,
                            inSteps = technique in state.inSteps,
                            initiallyExpanded = technique in expanded,
                            actions = actions,
                            keySuffix = "all",
                        )
                    }
                }
                WellnessDisclaimer(Modifier.padding(top = Spacing.xxl))
                Spacer(Modifier.height(Spacing.xl))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String, category: StepCategory? = null) {
    Row(
        modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (category != null) {
            Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun TechniqueCard(
    technique: Technique,
    reason: SuggestionReason?,
    inSteps: Boolean,
    initiallyExpanded: Boolean,
    actions: StepLibraryActions,
    keySuffix: String,
) {
    // A suggested technique also appears in its group; each card keeps its own "Why it helps" state.
    var open by rememberSaveable(technique.name + keySuffix) { mutableStateOf(initiallyExpanded) }
    // Resolved here, in composition: the click lambda below must not call a @Composable title().
    val title = technique.title()
    SleepSaverCard(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.cardGap),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            EmojiCircle(technique.emoji)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = Spacing.m),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (reason != null) {
                    Text(
                        stringResource(reason.textRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                EvidenceBadge(technique.evidence, Modifier.padding(top = Spacing.s))
            }
        }
        Text(
            technique.howTo(),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.m),
        )
        WhyToggle(open = open, onToggle = { open = !open })
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = Spacing.s)) {
                Text(technique.why(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    technique.source(),
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
        CardButtons(technique, title, inSteps, actions)
    }
}

/** "Why it helps ⌄": a 48 dp row that opens and closes the explanation. */
@Composable
private fun WhyToggle(open: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.winddown_library_why)
    val state = stringResource(if (open) R.string.winddown_expanded else R.string.winddown_collapsed)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(
            if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** "Add" (or a disabled "In your steps") and, for a guided tool, "Try now". Stacked at large font sizes. */
@Composable
private fun CardButtons(technique: Technique, title: String, inSteps: Boolean, actions: StepLibraryActions) {
    val tool = technique.tool.takeIf { it in ScreenTools }
    val addDescription = stringResource(R.string.winddown_add_description, title)
    val add = @Composable { modifier: Modifier ->
        if (inSteps) {
            SecondaryButton(stringResource(R.string.winddown_library_in_steps), onClick = {}, enabled = false, icon = Icons.Rounded.Check, modifier = modifier)
        } else {
            SecondaryButton(
                stringResource(R.string.winddown_add),
                onClick = { actions.onAdd(technique, title) },
                icon = Icons.Rounded.Add,
                modifier = modifier.semantics { contentDescription = addDescription },
            )
        }
    }
    val tryNow = @Composable { modifier: Modifier ->
        if (tool != null) {
            TextLinkButton(stringResource(R.string.winddown_library_try), onClick = { actions.onTry(tool) }, modifier = modifier)
        }
    }
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            add(Modifier.fillMaxWidth())
            tryNow(Modifier)
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            add(Modifier)
            tryNow(Modifier)
        }
    }
}

package com.sleepsaver.feature.winddown.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.LoadingBody
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.WinddownWriter
import com.sleepsaver.feature.winddown.safely
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal sealed interface TomorrowListUiState {
    data object Loading : TomorrowListUiState

    /** Tonight's list ([nightOf] = the evening); [saved] after Done, [checkedOff] when that ticked tonight's step. */
    data class Ready(
        val nightOf: LocalDate,
        val text: String,
        val saved: Boolean = false,
        val checkedOff: Boolean = false,
    ) : TomorrowListUiState
}

/**
 * The evening a list written [now] belongs to, when there is no Night steps session: the evening before the wake
 * date of [now] (23:10 Monday and 01:00 Tuesday both → Monday).
 */
internal fun fallbackNightOf(now: Instant, zone: ZoneId): LocalDate =
    NightRules.wakeDateOf(now, zone.rules.getOffset(now)).minusDays(1)

/**
 * Tomorrow list (`GuidedTool.TOMORROW_LIST`): tonight's list, shown again in the morning. Done saves it
 * (`JournalRepository.setTomorrowList`, blank deletes) and checks the step off when the list isn't blank.
 */
@HiltViewModel
internal class TomorrowListViewModel @Inject constructor(
    private val nightSteps: NightStepsRepository,
    private val journal: JournalRepository,
    private val time: TimeProvider,
    private val completion: ToolCompletion,
    private val writer: WinddownWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TomorrowListUiState>(TomorrowListUiState.Loading)
    val uiState: StateFlow<TomorrowListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val nightOf = safely("Reading tonight's steps") { nightSteps.tonight.first()?.nightOf }
                ?: fallbackNightOf(time.now(), time.zone())
            val text = safely("Reading the Tomorrow list") { journal.tomorrowList(nightOf).first()?.text }.orEmpty()
            _uiState.value = TomorrowListUiState.Ready(nightOf, text)
        }
    }

    fun save(text: String) {
        val ready = _uiState.value as? TomorrowListUiState.Ready ?: return
        viewModelScope.launch {
            if (!writer.save("Saving the Tomorrow list") { journal.setTomorrowList(ready.nightOf, text) }) return@launch
            val checkedOff = text.isNotBlank() && completion.markDone(Technique.TOMORROW_LIST)
            _uiState.value = ready.copy(text = text, saved = true, checkedOff = checkedOff)
        }
    }
}

@Composable
internal fun TomorrowListScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: TomorrowListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TomorrowListContent(state = state, onDone = viewModel::save, onClose = onClose, modifier = modifier)
}

/** The list as a free text field ("One thing per line."), Done, then "Parked for tomorrow." */
@Composable
internal fun TomorrowListContent(
    state: TomorrowListUiState,
    onDone: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? TomorrowListUiState.Ready
    // Keyed on the evening only: the save echoing the text back must not wipe what was typed after Done.
    var text by rememberSaveable(ready?.nightOf) { mutableStateOf(ready?.text.orEmpty()) }
    ToolFrame(onClose = onClose, dim = ToolDim.AFTER_DELAY, wakeKey = text, modifier = modifier) {
        if (ready == null) {
            LoadingBody()
            return@ToolFrame
        }
        ToolScrollBody {
            ToolHeader(Technique.TOMORROW_LIST)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.winddown_list_hint)) },
                minLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xl),
            )
            if (ready.saved) {
                Row(
                    modifier = Modifier
                        .padding(top = Spacing.l)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.winddown_list_parked),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (ready.checkedOff) {
                    Text(
                        stringResource(R.string.winddown_tool_checked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = Spacing.xs, start = 28.dp),
                    )
                }
            }
            if (ready.saved && text == ready.text) {
                SecondaryButton(
                    stringResource(R.string.winddown_close),
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl),
                )
            } else {
                PrimaryButton(
                    stringResource(R.string.winddown_tool_done),
                    onClick = { onDone(text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl),
                )
            }
        }
    }
}

package com.sleepsaver.feature.winddown.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.feature.winddown.WinddownWriter
import com.sleepsaver.feature.winddown.safely
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** A guided tool's three stages: the start screen, the run, and "That's it." ([checkedOff]: ticked in tonight's steps). */
internal sealed interface ToolStage<out C> {
    data object Setup : ToolStage<Nothing>

    data class Running<C>(val position: RunPosition<C>) : ToolStage<C>

    data class Finished(val checkedOff: Boolean) : ToolStage<Nothing>
}

/**
 * Finishing a guided tool checks its technique off for tonight, when it is one of tonight's steps (and Night steps
 * are on this evening). Example: finishing Slow tide ticks "🌊 Slow tide breathing" in tonight's checklist.
 */
internal class ToolCompletion @Inject constructor(
    private val nightSteps: NightStepsRepository,
    private val writer: WinddownWriter,
) {
    /** True when [technique] is (now) checked in tonight's steps. */
    suspend fun markDone(technique: Technique): Boolean {
        val session = safely("Reading tonight's steps") { nightSteps.tonight.first() } ?: return false
        if (!session.enabled) return false
        val step = session.steps.firstOrNull { it.activity.technique == technique } ?: return false
        if (step.done) return true
        return writer.save("Checking off $technique") { nightSteps.setDone(session.nightOf, step.activity.id, true) }
    }
}

/**
 * The ViewModel shape of the timed tools (Slow tide, Body scan, Muscle release): [stage] goes Setup → Running →
 * Finished, driven by a [GuidedRunner] on [clock] with cues through [haptics]. Finishing checks [technique] off.
 */
internal abstract class GuidedToolViewModel<C>(
    private val technique: Technique,
    haptics: Haptics,
    clock: GuidedClock,
    private val completion: ToolCompletion,
) : ViewModel() {

    private val _stage = MutableStateFlow<ToolStage<C>>(ToolStage.Setup)
    val stage: StateFlow<ToolStage<C>> = _stage.asStateFlow()

    private val runner = GuidedRunner<C>(
        scope = viewModelScope,
        clock = clock,
        haptics = haptics,
        onPosition = { _stage.value = ToolStage.Running(it) },
        onFinish = { _stage.value = ToolStage.Finished(checkedOff = completion.markDone(technique)) },
    )

    /** Starts the run (again, after Finished). */
    protected fun begin(segments: List<GuidedSegment<C>>) = runner.start(segments)

    fun pause() = runner.pause()

    fun resume() = runner.resume()
}

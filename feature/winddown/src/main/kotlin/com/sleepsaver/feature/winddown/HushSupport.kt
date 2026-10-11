package com.sleepsaver.feature.winddown

import com.sleepsaver.core.data.hooks.HushState
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.LocalDate

/**
 * The Hush settings picked on a screen before Play. While Hush plays, its own state ([HushState]) is shown instead.
 * Defaults: the first colour (White), `hush.defaultTimer` (45 min) and `hush.defaultVolume` (25 %).
 */
internal data class HushChoice(
    val color: HushColor = HushColor.entries.first(),
    /** null = "Until my window". */
    val timer: Duration? = SleepConfig.DEFAULT.hush.defaultTimer,
    val volume: Float = SleepConfig.DEFAULT.hush.defaultVolume,
)

/**
 * The alarm "Until my window" waits for: the one that ends the night of [wakeDate], never a later one. Example: on a
 * Friday night with no Saturday alarm, Monday's 6:30 doesn't count (Hush would play on through the weekend), so null.
 */
internal fun List<PlannedAlarm>.endingNightOf(wakeDate: LocalDate): PlannedAlarm? = firstOrNull { it.occurrence.date == wakeDate }

/**
 * What [com.sleepsaver.core.ui.components.HushControls] shows: Hush's own settings while it [playing], else the
 * screen's [choice]. "Until my window" falls back to the default timer when this night has no alarm
 * ([untilWindowAvailable] false, see [endingNightOf]). The media-volume line is filled in by the screen (it reads the
 * phone's volume).
 */
internal fun hushControlsState(
    choice: HushChoice,
    playing: HushState?,
    untilWindowAvailable: Boolean,
    night: Boolean,
    config: SleepConfig = SleepConfig.DEFAULT,
): HushControlsState {
    val timer = if (playing != null) playing.timer else choice.timer
    return HushControlsState(
        color = playing?.color ?: choice.color,
        timer = if (timer == null && !untilWindowAvailable) config.hush.defaultTimer else timer,
        volume = playing?.volume ?: choice.volume,
        playing = playing != null,
        untilWindowAvailable = untilWindowAvailable,
        mediaVolumeHigh = false,
        night = night,
    )
}

/**
 * Hush for one screen: keeps the [choice] made before Play and forwards changes to the [NightController] while Hush
 * plays (a new colour or timer restarts it with the new setting; the volume changes in place). [onPlay] runs after
 * Play, e.g. to check off a Hush step.
 */
internal class HushPicker(
    private val nightController: NightController,
    private val untilWindowAvailable: () -> Boolean,
    private val config: SleepConfig = SleepConfig.DEFAULT,
    private val onPlay: () -> Unit = {},
) {
    private val _choice = MutableStateFlow(HushChoice(timer = config.hush.defaultTimer, volume = config.hush.defaultVolume))
    val choice: StateFlow<HushChoice> = _choice.asStateFlow()

    private val playing: HushState? get() = nightController.state.value.hush

    val actions = HushControlsActions(
        onColor = { color ->
            _choice.update { it.copy(color = color) }
            playing?.let { nightController.startHush(color, it.timer, it.volume) }
        },
        onTimer = onTimer@{ timer ->
            if (timer == null && !untilWindowAvailable()) return@onTimer
            _choice.update { it.copy(timer = timer) }
            playing?.let { nightController.startHush(it.color, timer, it.volume) }
        },
        onVolume = { volume ->
            val clamped = volume.coerceIn(config.hush.minVolume, 1f)
            _choice.update { it.copy(volume = clamped) }
            if (playing != null) nightController.setHushVolume(clamped)
        },
        onPlay = {
            val choice = _choice.value
            val timer = if (choice.timer == null && !untilWindowAvailable()) config.hush.defaultTimer else choice.timer
            nightController.startHush(choice.color, timer, choice.volume)
            onPlay()
        },
        onStop = { nightController.stopHush() },
    )
}

package com.sleepsaver.feature.winddown

import com.sleepsaver.core.data.hooks.HushState
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.repository.HushSettings
import com.sleepsaver.core.data.repository.HushSettingsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Whether "Until my window" can be offered for the night that ends on [wakeDate]: Hush's timer then waits for the
 * armed alarm, which is [nextAlarm], so only when that alarm is this night's. Example: on a Friday night with no
 * weekend alarms the next one is Monday's, and Hush would play through Saturday and Sunday.
 */
internal fun untilWindowAvailable(nextAlarm: PlannedAlarm?, wakeDate: LocalDate): Boolean = nextAlarm?.occurrence?.date == wakeDate

/**
 * What [com.sleepsaver.core.ui.components.HushControls] shows: Hush's own settings while it [playing], else the shared
 * [choice] (the last pick on any screen). "Until my window" falls back to the default timer when this night has no
 * alarm ([untilWindowAvailable] false). The media-volume line is filled in by the screen (it reads the phone's volume).
 */
internal fun hushControlsState(
    choice: HushSettings,
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
 * Hush for one screen, on the pick every screen shares ([HushSettingsRepository]: Tonight, the Night screen, Night
 * steps and Night reset all show the same one). A change is saved for all of them. While Hush plays, the pick becomes
 * what plays plus the change and Hush follows it: a new colour or timer restarts it, the volume changes in place. Play
 * starts what the controls show ([shown], the screen's [HushControlsState]); [onPlay] runs after it, e.g. to check off
 * a Hush step.
 */
internal class HushPicker(
    private val nightController: NightController,
    private val hushSettings: HushSettingsRepository,
    private val shown: () -> HushControlsState?,
    private val config: SleepConfig = SleepConfig.DEFAULT,
    private val onPlay: () -> Unit = {},
) {
    /** The shared pick, for the screen's state ([hushControlsState]). */
    val choice: Flow<HushSettings> get() = hushSettings.settings

    private val playing: HushState? get() = nightController.state.value.hush

    val actions = HushControlsActions(
        onColor = { color -> change { it.copy(color = color) } },
        onTimer = onTimer@{ timer ->
            if (timer == null && shown()?.untilWindowAvailable != true) return@onTimer
            change { it.copy(timer = timer) }
        },
        onVolume = { volume ->
            val clamped = volume.coerceIn(config.hush.minVolume, 1f)
            val playing = playing
            if (playing == null) {
                hushSettings.update { it.copy(volume = clamped) }
            } else {
                hushSettings.update { playing.asSettings().copy(volume = clamped) }
                nightController.setHushVolume(clamped)
            }
        },
        onPlay = onPlay@{
            val hush = shown() ?: return@onPlay
            nightController.startHush(hush.color, hush.timer, hush.volume)
            onPlay()
        },
        onStop = { nightController.stopHush() },
    )

    /** Saves a colour or timer change; while Hush plays, starts it again with the change (from what is playing). */
    private fun change(edit: (HushSettings) -> HushSettings) {
        val playing = playing
        if (playing == null) {
            hushSettings.update(edit)
        } else {
            val next = edit(playing.asSettings())
            hushSettings.update { next }
            nightController.startHush(next.color, next.timer, next.volume)
        }
    }
}

/** The settings Hush is playing with, as a pick. */
internal fun HushState.asSettings(): HushSettings = HushSettings(color, timer, volume)

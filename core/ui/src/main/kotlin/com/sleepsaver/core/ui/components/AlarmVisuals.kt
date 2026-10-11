package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.platform.AlarmBlocker

// ---------------------------------------------------------------- Wake-up style

/** Calm / Standard / Firm / Quiet bed. */
@get:StringRes
val WakeStyle.titleRes: Int
    get() = when (this) {
        WakeStyle.CALM -> R.string.ss_style_calm
        WakeStyle.STANDARD -> R.string.ss_style_standard
        WakeStyle.FIRM -> R.string.ss_style_firm
        WakeStyle.QUIET_BED -> R.string.ss_style_quiet_bed
    }

/** How it starts: "A 60-second swell. Vibration after 30 seconds." */
@get:StringRes
val WakeStyle.descriptionRes: Int
    get() = when (this) {
        WakeStyle.CALM -> R.string.ss_style_calm_desc
        WakeStyle.STANDARD -> R.string.ss_style_standard_desc
        WakeStyle.FIRM -> R.string.ss_style_firm_desc
        WakeStyle.QUIET_BED -> R.string.ss_style_quiet_bed_desc
    }

@Composable
@ReadOnlyComposable
fun WakeStyle.title(): String = stringResource(titleRes)

@Composable
@ReadOnlyComposable
fun WakeStyle.description(): String = stringResource(descriptionRes)

val WakeStyle.icon: ImageVector
    get() = when (this) {
        WakeStyle.CALM -> Icons.Rounded.Spa
        WakeStyle.STANDARD -> Icons.Rounded.Alarm
        WakeStyle.FIRM -> Icons.Rounded.NotificationsActive
        WakeStyle.QUIET_BED -> Icons.Rounded.Vibration
    }

// ---------------------------------------------------------------- Sounds

/** Soft chimes / Warm rise / Bright bells / Phone's alarm sound. */
@get:StringRes
val AlarmTone.titleRes: Int
    get() = when (this) {
        AlarmTone.SOFT_CHIMES -> R.string.ss_tone_soft_chimes
        AlarmTone.WARM_RISE -> R.string.ss_tone_warm_rise
        AlarmTone.BRIGHT_BELLS -> R.string.ss_tone_bright_bells
        AlarmTone.SYSTEM_ALARM -> R.string.ss_tone_system_alarm
    }

@Composable
@ReadOnlyComposable
fun AlarmTone.title(): String = stringResource(titleRes)

// ---------------------------------------------------------------- Alarm mode

/** Off / At wake time / Surfacing. */
@get:StringRes
val AlarmMode.titleRes: Int
    get() = when (this) {
        AlarmMode.OFF -> R.string.ss_alarm_mode_off
        AlarmMode.FIXED -> R.string.ss_alarm_mode_fixed
        AlarmMode.SURFACING -> R.string.ss_alarm_mode_surfacing
    }

/** "Rings at your wake time." / "May ring at a restless moment in your window, …"; null for OFF (nothing to explain). */
@get:StringRes
val AlarmMode.descriptionRes: Int?
    get() = when (this) {
        AlarmMode.OFF -> null
        AlarmMode.FIXED -> R.string.ss_alarm_mode_fixed_desc
        AlarmMode.SURFACING -> R.string.ss_alarm_mode_surfacing_desc
    }

@Composable
@ReadOnlyComposable
fun AlarmMode.title(): String = stringResource(titleRes)

/** See [descriptionRes]: null for OFF. */
@Composable
@ReadOnlyComposable
fun AlarmMode.description(): String? = descriptionRes?.let { stringResource(it) }

val AlarmMode.icon: ImageVector
    get() = when (this) {
        AlarmMode.OFF -> Icons.Rounded.AlarmOff
        AlarmMode.FIXED -> Icons.Rounded.Alarm
        AlarmMode.SURFACING -> Icons.Rounded.Waves
    }

// ---------------------------------------------------------------- Snooze

/** Tapering / Fixed / No snooze. */
@get:StringRes
val SnoozeStyle.titleRes: Int
    get() = when (this) {
        SnoozeStyle.TAPERING -> R.string.ss_snooze_tapering
        SnoozeStyle.FIXED -> R.string.ss_snooze_fixed
        SnoozeStyle.NONE -> R.string.ss_snooze_none
    }

/**
 * "9, 7, 5, 3, then 2 minutes" for TAPERING; null for FIXED (its length and count are the user's, so the screen
 * spells them out) and NONE.
 */
@get:StringRes
val SnoozeStyle.descriptionRes: Int?
    get() = when (this) {
        SnoozeStyle.TAPERING -> R.string.ss_snooze_tapering_desc
        SnoozeStyle.FIXED, SnoozeStyle.NONE -> null
    }

@Composable
@ReadOnlyComposable
fun SnoozeStyle.title(): String = stringResource(titleRes)

/** See [descriptionRes]: null for FIXED and NONE. */
@Composable
@ReadOnlyComposable
fun SnoozeStyle.description(): String? = descriptionRes?.let { stringResource(it) }

// ---------------------------------------------------------------- Hush

/** White / Pink / Brown. */
@get:StringRes
val HushColor.titleRes: Int
    get() = when (this) {
        HushColor.WHITE -> R.string.ss_hush_white
        HushColor.PINK -> R.string.ss_hush_pink
        HushColor.BROWN -> R.string.ss_hush_brown
    }

/** What it sounds like: "Softer, like steady rain". */
@get:StringRes
val HushColor.descriptionRes: Int
    get() = when (this) {
        HushColor.WHITE -> R.string.ss_hush_white_desc
        HushColor.PINK -> R.string.ss_hush_pink_desc
        HushColor.BROWN -> R.string.ss_hush_brown_desc
    }

@Composable
@ReadOnlyComposable
fun HushColor.title(): String = stringResource(titleRes)

@Composable
@ReadOnlyComposable
fun HushColor.description(): String = stringResource(descriptionRes)

// ---------------------------------------------------------------- Alarm check

/** What's wrong: "Alarm volume is off". */
@get:StringRes
val AlarmBlocker.titleRes: Int
    get() = when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> R.string.ss_blocker_notifications_title
        AlarmBlocker.RING_CHANNEL_OFF -> R.string.ss_blocker_ring_channel_title
        AlarmBlocker.EXACT_ALARMS_OFF -> R.string.ss_blocker_exact_title
        AlarmBlocker.FULL_SCREEN_OFF -> R.string.ss_blocker_full_screen_title
        AlarmBlocker.BACKGROUND_RESTRICTED -> R.string.ss_blocker_background_title
        AlarmBlocker.ALARM_VOLUME_ZERO -> R.string.ss_blocker_volume_title
        AlarmBlocker.TOTAL_SILENCE -> R.string.ss_blocker_silence_title
        AlarmBlocker.BATTERY_OPTIMIZED -> R.string.ss_blocker_battery_title
        AlarmBlocker.BLUETOOTH_OUTPUT -> R.string.ss_blocker_bluetooth_title
    }

/** Why it matters and what to do: "Turn up the alarm volume, or the alarm will be silent." EXACT_ALARMS_OFF takes the minutes. */
@get:StringRes
val AlarmBlocker.fixRes: Int
    get() = when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> R.string.ss_blocker_notifications_fix
        AlarmBlocker.RING_CHANNEL_OFF -> R.string.ss_blocker_ring_channel_fix
        AlarmBlocker.EXACT_ALARMS_OFF -> R.string.ss_blocker_exact_fix
        AlarmBlocker.FULL_SCREEN_OFF -> R.string.ss_blocker_full_screen_fix
        AlarmBlocker.BACKGROUND_RESTRICTED -> R.string.ss_blocker_background_fix
        AlarmBlocker.ALARM_VOLUME_ZERO -> R.string.ss_blocker_volume_fix
        AlarmBlocker.TOTAL_SILENCE -> R.string.ss_blocker_silence_fix
        AlarmBlocker.BATTERY_OPTIMIZED -> R.string.ss_blocker_battery_fix
        AlarmBlocker.BLUETOOTH_OUTPUT -> R.string.ss_blocker_bluetooth_fix
    }

/** The fix button ("Allow", "Open settings", "Sound settings"); null for BLUETOOTH_OUTPUT, which has nothing to fix. */
@get:StringRes
val AlarmBlocker.actionLabelRes: Int?
    get() = when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> R.string.ss_blocker_notifications_action
        AlarmBlocker.RING_CHANNEL_OFF -> R.string.ss_blocker_ring_channel_action
        AlarmBlocker.EXACT_ALARMS_OFF -> R.string.ss_blocker_exact_action
        AlarmBlocker.FULL_SCREEN_OFF -> R.string.ss_blocker_full_screen_action
        AlarmBlocker.BACKGROUND_RESTRICTED -> R.string.ss_blocker_background_action
        AlarmBlocker.ALARM_VOLUME_ZERO -> R.string.ss_blocker_volume_action
        AlarmBlocker.TOTAL_SILENCE -> R.string.ss_blocker_silence_action
        AlarmBlocker.BATTERY_OPTIMIZED -> R.string.ss_blocker_battery_action
        AlarmBlocker.BLUETOOTH_OUTPUT -> null
    }

@Composable
@ReadOnlyComposable
fun AlarmBlocker.title(): String = stringResource(titleRes)

/**
 * The fix line. EXACT_ALARMS_OFF says how early an inexact alarm may ring: "… up to 10 minutes early"
 * (`config.alarm.inexactRingWindow`).
 */
@Composable
@ReadOnlyComposable
fun AlarmBlocker.fix(config: SleepConfig = SleepConfig.DEFAULT): String =
    if (this == AlarmBlocker.EXACT_ALARMS_OFF) {
        stringResource(fixRes, config.alarm.inexactRingWindow.toMinutes().toInt())
    } else {
        stringResource(fixRes)
    }

/** See [actionLabelRes]: null for BLUETOOTH_OUTPUT. */
@Composable
@ReadOnlyComposable
fun AlarmBlocker.actionLabel(): String? = actionLabelRes?.let { stringResource(it) }

val AlarmBlocker.icon: ImageVector
    get() = when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> Icons.Rounded.NotificationsOff
        AlarmBlocker.RING_CHANNEL_OFF -> Icons.Rounded.NotificationsPaused
        AlarmBlocker.EXACT_ALARMS_OFF -> Icons.Rounded.Schedule
        AlarmBlocker.FULL_SCREEN_OFF -> Icons.Rounded.Fullscreen
        AlarmBlocker.BACKGROUND_RESTRICTED -> Icons.Rounded.BatteryAlert
        AlarmBlocker.ALARM_VOLUME_ZERO -> Icons.AutoMirrored.Rounded.VolumeOff
        AlarmBlocker.TOTAL_SILENCE -> Icons.Rounded.DoNotDisturbOn
        AlarmBlocker.BATTERY_OPTIMIZED -> Icons.Rounded.BatterySaver
        AlarmBlocker.BLUETOOTH_OUTPUT -> Icons.Rounded.BluetoothAudio
    }

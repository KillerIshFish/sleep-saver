package com.sleepsaver.feature.tonight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.ui.components.NoticeCard
import com.sleepsaver.core.ui.components.NoticeTone
import com.sleepsaver.core.ui.components.actionLabel
import com.sleepsaver.core.ui.components.fix
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.platform.AlarmAccess
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.platform.rememberAlarmAccess

/** The notification channel every alarm check reads: core:alarm's ring channel ("alarm_ring"). */
internal const val RING_CHANNEL: String = AlarmIntents.CHANNEL_RING

/**
 * The arming gate (SPEC_V2 §5.0): anything that can make the phone ring (an alarm mode, a preset with alarms, Just
 * this once with a mode, a catnap, a test ring) goes through [arm]. It first makes sure notifications are allowed,
 * asking for POST_NOTIFICATIONS where Android still shows the prompt; then it checks that the "Alarm" category is on.
 * Only when both pass does the change apply. Otherwise nothing changes and [blocker] (NOTIFICATIONS_OFF or
 * RING_CHANNEL_OFF) names what stopped it, for the inline notice ([ArmBlockerNotice]). The notice goes away once the
 * problem is fixed (the checks are re-read when the screen resumes) or the next attempt passes.
 *
 * The other blockers (exact alarms, full screen, volume, …) never stop arming; they show on the Tonight card and in
 * Alarm check.
 */
@Stable
internal class AlarmGate(val access: AlarmAccess, private val lastBlocker: MutableState<AlarmBlocker?>) {

    /** What stopped the last [arm], while it still applies; null when nothing did. */
    val blocker: AlarmBlocker? get() = lastBlocker.value?.takeIf { it in access.blockers }

    /** Runs [apply] once notifications are allowed and the alarm channel is on; otherwise records the [blocker]. */
    fun arm(apply: () -> Unit) {
        access.requestNotifications { allowed ->
            val stoppedBy = when {
                !allowed -> AlarmBlocker.NOTIFICATIONS_OFF
                AlarmBlocker.RING_CHANNEL_OFF in access.blockers -> AlarmBlocker.RING_CHANNEL_OFF
                else -> null
            }
            lastBlocker.value = stoppedBy
            if (stoppedBy == null) apply()
        }
    }

    /** Runs [apply] through [arm] when [arming], else at once (switching something off never needs a check). */
    fun armIf(arming: Boolean, apply: () -> Unit) = if (arming) arm(apply) else apply()

    /** Opens the fix for [blocker] (the prompt or the right settings page). */
    fun fix(blocker: AlarmBlocker) = access.fix(blocker)
}

/** An [AlarmGate] for this screen, reading the alarm channel [channelId] (always [RING_CHANNEL] outside tests). */
@Composable
internal fun rememberAlarmGate(channelId: String = RING_CHANNEL): AlarmGate {
    val access = rememberAlarmAccess(channelId)
    val lastBlocker = rememberSaveable { mutableStateOf<AlarmBlocker?>(null) }
    return remember(access, lastBlocker) { AlarmGate(access, lastBlocker) }
}

/**
 * Why an alarm wasn't switched on, with its fix: "Notifications are off / Alarms need notifications to show the Stop
 * button. / Allow", or "Alarm notifications are turned off / Turn on the "Alarm" category in notification settings. /
 * Open settings".
 */
@Composable
internal fun ArmBlockerNotice(blocker: AlarmBlocker, onFix: (AlarmBlocker) -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(
        title = blocker.title(),
        body = blocker.fix(),
        modifier = modifier,
        icon = blocker.icon,
        actionLabel = blocker.actionLabel(),
        onAction = { onFix(blocker) },
        tone = NoticeTone.WARNING,
    )
}

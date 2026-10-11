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
 * Which control an arming attempt came from. Its blocker shows next to that control only, so a blocker from the
 * one-off sheet never turns up in the Catnap card.
 */
internal enum class ArmSource {
    /** The alarm itself: a mode, the week plan switch, a preset, "Turn back on", "Resume alarms", an Undo. */
    ALARM,
    CATNAP,

    /** The one-off sheet's "Just this once". */
    ONE_OFF,

    /** The day plan's "Copy to…" onto days without an alarm. */
    COPY,
    TEST_RING,
}

/** What stopped the last arming attempt, and the control it came from. */
internal data class ArmAttempt(val source: ArmSource, val blocker: AlarmBlocker) : java.io.Serializable

/**
 * The arming gate (SPEC_V2 §5.0): anything that can make the phone ring (an alarm mode, a preset with alarms, Just
 * this once with a mode, "Turn back on", "Resume alarms", an Undo or a copy that brings an alarm back, a catnap, a test
 * ring) goes through [arm]. It first makes sure notifications are allowed, asking for POST_NOTIFICATIONS where Android
 * still shows the prompt; then it checks that the "Alarm" category is on. Only when both pass does the change apply.
 * Otherwise nothing changes and [blocker] (NOTIFICATIONS_OFF or RING_CHANNEL_OFF) names what stopped it, for the inline
 * notice ([ArmBlockerNotice]) next to the control it came from ([ArmSource]). The notice goes away once the problem is
 * fixed (the checks are re-read when the screen resumes) or the next attempt passes.
 *
 * The other blockers (exact alarms, full screen, volume, …) never stop arming; they show on the Tonight card and in
 * Alarm check.
 */
@Stable
internal class AlarmGate(val access: AlarmAccess, private val lastAttempt: MutableState<ArmAttempt?>) {

    /** What stopped the last [arm] from [source], while it still applies; null when nothing did. */
    fun blocker(source: ArmSource): AlarmBlocker? =
        lastAttempt.value?.takeIf { it.source == source && it.blocker in access.blockers }?.blocker

    /**
     * Runs [apply] once notifications are allowed and the alarm channel is on; otherwise records what stopped it, as
     * the [blocker] of [source].
     */
    fun arm(source: ArmSource, apply: () -> Unit) {
        access.requestNotifications { allowed ->
            val stoppedBy = when {
                !allowed -> AlarmBlocker.NOTIFICATIONS_OFF
                AlarmBlocker.RING_CHANNEL_OFF in access.blockers -> AlarmBlocker.RING_CHANNEL_OFF
                else -> null
            }
            lastAttempt.value = stoppedBy?.let { ArmAttempt(source, it) }
            if (stoppedBy == null) apply()
        }
    }

    /** Runs [apply] through [arm] when [arming], else at once (switching something off never needs a check). */
    fun armIf(arming: Boolean, source: ArmSource, apply: () -> Unit) = if (arming) arm(source, apply) else apply()

    /** Opens the fix for [blocker] (the prompt or the right settings page). */
    fun fix(blocker: AlarmBlocker) = access.fix(blocker)
}

/** An [AlarmGate] for this screen, reading the alarm channel [channelId] (always [RING_CHANNEL] outside tests). */
@Composable
internal fun rememberAlarmGate(channelId: String = RING_CHANNEL): AlarmGate {
    val access = rememberAlarmAccess(channelId)
    val lastAttempt = rememberSaveable { mutableStateOf<ArmAttempt?>(null) }
    return remember(access, lastAttempt) { AlarmGate(access, lastAttempt) }
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

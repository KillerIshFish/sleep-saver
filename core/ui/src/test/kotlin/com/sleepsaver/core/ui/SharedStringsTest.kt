package com.sleepsaver.core.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.domain.nightsteps.StepCategory
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.KeelBand
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.ui.components.actionLabelRes
import com.sleepsaver.core.ui.components.descriptionRes
import com.sleepsaver.core.ui.components.fix
import com.sleepsaver.core.ui.components.fixRes
import com.sleepsaver.core.ui.components.howToRes
import com.sleepsaver.core.ui.components.labelRes
import com.sleepsaver.core.ui.components.sourceRes
import com.sleepsaver.core.ui.components.titleRes
import com.sleepsaver.core.ui.components.whyRes
import com.sleepsaver.core.ui.platform.AlarmBlocker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** The shared copy in SPEC_V2 §5.1, word for word, reached through the visuals features use. */
@RunWith(AndroidJUnit4::class)
class SharedStringsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun <E> assertCopy(expected: Map<E, String?>, res: (E) -> Int?) {
        expected.forEach { (key, english) ->
            assertWithMessage("$key").that(res(key)?.let { text(it) }).isEqualTo(english)
        }
    }

    @Test
    fun `marks, feel and keel`() {
        assertCopy(
            mapOf(
                Mark.LATE_CAFFEINE to "Late caffeine", Mark.ALCOHOL to "Alcohol", Mark.LATE_MEAL to "Late meal",
                Mark.LATE_WORKOUT to "Late workout", Mark.STRESS to "Stress", Mark.SCREENS_IN_BED to "Screens in bed",
                Mark.AWAY_FROM_HOME to "Away from home", Mark.UNWELL to "Unwell", Mark.NIGHT_STEPS_DONE to "Night steps done",
                Mark.HUSH_USED to "Hush on", Mark.ANCHOR_DROPPED to "Anchor dropped",
            ),
        ) { it.labelRes }
        assertCopy(
            mapOf(
                MorningFeel.FOGGY to "Foggy", MorningFeel.SLOW to "Slow", MorningFeel.OKAY to "Okay",
                MorningFeel.CLEAR to "Clear", MorningFeel.BRIGHT to "Bright",
            ),
        ) { it.labelRes }
        assertThat(text(R.string.ss_feel_question)).isEqualTo("How do you feel?")
        assertCopy(mapOf(KeelBand.EVEN to "Even keel", KeelBand.FAIR to "Fair", KeelBand.CHOPPY to "Choppy")) { it.labelRes }
    }

    @Test
    fun `evidence and step groups`() {
        assertCopy(
            mapOf(
                EvidenceLevel.GOOD to "Good evidence", EvidenceLevel.MODERATE to "Some evidence",
                EvidenceLevel.EARLY to "Early evidence", EvidenceLevel.LITTLE to "Little evidence",
            ),
        ) { it.labelRes }
        assertCopy(
            mapOf(
                StepCategory.BREATHING to "Breathing", StepCategory.BODY to "Body", StepCategory.MIND to "Mind",
                StepCategory.ENVIRONMENT to "Room", StepCategory.ROUTINE to "Routine",
            ),
        ) { it.titleRes }
    }

    @Test
    fun `wake-up styles, sounds, modes and snooze`() {
        assertCopy(
            mapOf(
                WakeStyle.CALM to "Calm", WakeStyle.STANDARD to "Standard", WakeStyle.FIRM to "Firm",
                WakeStyle.QUIET_BED to "Quiet bed",
            ),
        ) { it.titleRes }
        assertCopy(
            mapOf(
                WakeStyle.CALM to "A soft chime first, then a slow 90-second swell. Vibration after a minute.",
                WakeStyle.STANDARD to "A 60-second swell. Vibration after 30 seconds.",
                WakeStyle.FIRM to "A quick 20-second swell, with vibration from the start.",
                WakeStyle.QUIET_BED to "Vibration only for 2 minutes, then sound. Kind to whoever shares your bed.",
            ),
        ) { it.descriptionRes }
        assertCopy(
            mapOf(
                AlarmTone.SOFT_CHIMES to "Soft chimes", AlarmTone.WARM_RISE to "Warm rise",
                AlarmTone.BRIGHT_BELLS to "Bright bells", AlarmTone.SYSTEM_ALARM to "Phone's alarm sound",
            ),
        ) { it.titleRes }
        assertCopy(mapOf(AlarmMode.OFF to "Off", AlarmMode.FIXED to "At wake time", AlarmMode.SURFACING to "Surfacing")) {
            it.titleRes
        }
        assertCopy(
            mapOf(
                AlarmMode.OFF to null,
                AlarmMode.FIXED to "Rings at your wake time.",
                AlarmMode.SURFACING to "May ring at a restless moment in your window, never later than your wake time.",
            ),
        ) { it.descriptionRes }
        assertCopy(mapOf(SnoozeStyle.TAPERING to "Tapering", SnoozeStyle.FIXED to "Fixed", SnoozeStyle.NONE to "No snooze")) {
            it.titleRes
        }
        assertCopy(
            mapOf(SnoozeStyle.TAPERING to "9, 7, 5, 3, then 2 minutes", SnoozeStyle.FIXED to null, SnoozeStyle.NONE to null),
        ) { it.descriptionRes }
    }

    @Test
    fun hush() {
        assertCopy(mapOf(HushColor.WHITE to "White", HushColor.PINK to "Pink", HushColor.BROWN to "Brown")) { it.titleRes }
        assertCopy(
            mapOf(
                HushColor.WHITE to "Even and bright, like a fan",
                HushColor.PINK to "Softer, like steady rain",
                HushColor.BROWN to "Deep and low, like distant surf",
            ),
        ) { it.descriptionRes }
        assertThat(text(R.string.ss_hush_title)).isEqualTo("Hush")
        assertThat(text(R.string.ss_hush_tip))
            .isEqualTo("Hush masks background noise. Keep it low; for sudden noises, earplugs work better.")
        assertThat(text(R.string.ss_hush_play)).isEqualTo("Play")
        assertThat(text(R.string.ss_hush_stop)).isEqualTo("Stop")
        assertThat(text(R.string.ss_hush_until_window)).isEqualTo("Until my window")
        assertThat(text(R.string.ss_hush_minutes, 45)).isEqualTo("45 min")
        assertThat(text(R.string.ss_hush_volume)).isEqualTo("Volume")
        assertThat(text(R.string.ss_hush_fades_after, "45 min")).isEqualTo("Fades out after 45 min")
        assertThat(text(R.string.ss_hush_fades_before_window)).isEqualTo("Fades out before your window")
        assertThat(text(R.string.ss_hush_media_loud)).isEqualTo("Your media volume is high. Turn it down for Hush.")
    }

    @Test
    fun `alarm check blockers`() {
        assertCopy(
            mapOf(
                AlarmBlocker.NOTIFICATIONS_OFF to "Notifications are off",
                AlarmBlocker.RING_CHANNEL_OFF to "Alarm notifications are turned off",
                AlarmBlocker.EXACT_ALARMS_OFF to "Alarms & reminders is off",
                AlarmBlocker.FULL_SCREEN_OFF to "Full-screen alarms are off",
                AlarmBlocker.BACKGROUND_RESTRICTED to "Battery use is restricted",
                AlarmBlocker.ALARM_VOLUME_ZERO to "Alarm volume is off",
                AlarmBlocker.TOTAL_SILENCE to "Do Not Disturb is on total silence",
                AlarmBlocker.BATTERY_OPTIMIZED to "Battery optimisation is on",
                AlarmBlocker.BLUETOOTH_OUTPUT to "Sound goes to a Bluetooth device",
            ),
        ) { it.titleRes }
        assertCopy(
            mapOf(
                AlarmBlocker.NOTIFICATIONS_OFF to "Alarms need notifications to show the Stop button.",
                AlarmBlocker.RING_CHANNEL_OFF to "Turn on the \"Alarm\" category in notification settings.",
                AlarmBlocker.EXACT_ALARMS_OFF to null,
                AlarmBlocker.FULL_SCREEN_OFF to "The alarm shows as a banner instead of filling the screen.",
                AlarmBlocker.BACKGROUND_RESTRICTED to
                    "Android may stop the alarm. Set Sleep Saver's battery use to Unrestricted or Optimized.",
                AlarmBlocker.ALARM_VOLUME_ZERO to "Turn up the alarm volume, or the alarm will be silent.",
                AlarmBlocker.TOTAL_SILENCE to "Total silence mutes alarms too.",
                AlarmBlocker.BATTERY_OPTIMIZED to
                    "Usually fine. If alarms are late on this phone, turn it off for Sleep Saver.",
                AlarmBlocker.BLUETOOTH_OUTPUT to "Your alarm may play there instead of on the phone.",
            ),
        ) { it.fixRes }
        assertCopy(
            mapOf(
                AlarmBlocker.NOTIFICATIONS_OFF to "Allow",
                AlarmBlocker.RING_CHANNEL_OFF to "Open settings",
                AlarmBlocker.EXACT_ALARMS_OFF to "Allow",
                AlarmBlocker.FULL_SCREEN_OFF to "Allow",
                AlarmBlocker.BACKGROUND_RESTRICTED to "Open settings",
                AlarmBlocker.ALARM_VOLUME_ZERO to "Sound settings",
                AlarmBlocker.TOTAL_SILENCE to "Open settings",
                AlarmBlocker.BATTERY_OPTIMIZED to "Open settings",
                AlarmBlocker.BLUETOOTH_OUTPUT to null,
            ),
        ) { it.actionLabelRes }
    }

    @Test
    fun `the exact-alarm fix counts the minutes an alarm may ring early`() {
        fun window(minutes: Long) = SleepConfig.DEFAULT.run { copy(alarm = alarm.copy(inexactRingWindow = Duration.ofMinutes(minutes))) }
        var usual = ""
        var one = ""
        compose.setContent {
            usual = AlarmBlocker.EXACT_ALARMS_OFF.fix()
            one = AlarmBlocker.EXACT_ALARMS_OFF.fix(window(1))
        }
        compose.waitForIdle()

        assertThat(usual).isEqualTo("Without it, your alarm may ring up to 10 minutes early.")
        assertThat(one).isEqualTo("Without it, your alarm may ring up to 1 minute early.")
    }

    @Test
    fun `placement and the Night steps habit`() {
        assertThat(text(R.string.ss_placement)).isEqualTo(
            "Place the phone face down on the mattress, away from your pillow. Never put it under pillows or covers.",
        )
        assertThat(text(R.string.ss_habit_evening_routine)).isEqualTo("Night steps")
        assertThat(text(R.string.ss_habit_evening_routine_body)).isEqualTo("A reminder when your Night steps start.")
    }

    @Test
    fun `every technique has its four lines`() {
        val slowTide = Technique.SLOW_TIDE
        assertThat(text(slowTide.titleRes)).isEqualTo("Slow tide breathing")
        assertThat(text(slowTide.howToRes))
            .isEqualTo("Breathe in for about 4 seconds and out for about 6: around six breaths a minute.")
        assertThat(text(slowTide.whyRes)).isEqualTo(
            "Slow breathing with a longer out-breath can help you feel calmer. People report better sleep after a few " +
                "weeks of practice.",
        )
        assertThat(text(slowTide.sourceRes)).isEqualTo("Small studies and a 2026 review; mostly self-reported.")
        assertThat(text(Technique.TOMORROW_LIST.whyRes)).isEqualTo(
            "Parking tomorrow's tasks on paper can quiet the mind. In one study, people who wrote specific lists fell " +
                "asleep sooner.",
        )
        assertThat(text(Technique.COOL_DARK_ROOM.howToRes))
            .isEqualTo("Air the room, dim the lights and choose bedding that won't make you too warm.")
        assertCopy(
            mapOf(
                Technique.WARM_SHOWER to "Warm shower or bath", Technique.SCREENS_DOWN to "Screens down",
                Technique.TOMORROW_LIST to "Tomorrow list", Technique.SLOW_TIDE to "Slow tide breathing",
                Technique.BODY_SCAN to "Body scan", Technique.MUSCLE_RELEASE to "Muscle release",
                Technique.READ_ON_PAPER to "Read on paper", Technique.STRETCHING to "Gentle stretching",
                Technique.JOURNALING to "Journaling", Technique.COOL_DARK_ROOM to "Cool, dark room", Technique.HUSH to "Hush",
                Technique.TV to "TV", Technique.GAMING to "Gaming",
            ),
        ) { it.titleRes }
        Technique.entries.forEach { technique ->
            listOf(technique.howToRes, technique.whyRes, technique.sourceRes).forEach { id ->
                assertWithMessage("$technique").that(text(id)).isNotEmpty()
            }
        }
    }
}

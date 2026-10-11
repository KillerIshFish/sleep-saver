package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Sick
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.patterns.KeelBand
import com.sleepsaver.core.ui.R

// ---------------------------------------------------------------- Marks

val Mark.icon: ImageVector
    get() = when (this) {
        Mark.LATE_CAFFEINE -> Icons.Rounded.LocalCafe
        Mark.ALCOHOL -> Icons.Rounded.WineBar
        Mark.LATE_MEAL -> Icons.Rounded.Restaurant
        Mark.LATE_WORKOUT -> Icons.Rounded.FitnessCenter
        Mark.STRESS -> Icons.Rounded.Psychology
        Mark.SCREENS_IN_BED -> Icons.Rounded.PhoneAndroid
        Mark.AWAY_FROM_HOME -> Icons.Rounded.Luggage
        Mark.UNWELL -> Icons.Rounded.Sick
        Mark.NIGHT_STEPS_DONE -> Icons.Rounded.Checklist
        Mark.HUSH_USED -> Icons.Rounded.GraphicEq
        Mark.ANCHOR_DROPPED -> Icons.Rounded.Anchor
    }

/** "Late caffeine", "Screens in bed"; the derived ones read "Night steps done", "Hush on", "Anchor dropped". */
@get:StringRes
val Mark.labelRes: Int
    get() = when (this) {
        Mark.LATE_CAFFEINE -> R.string.ss_mark_late_caffeine
        Mark.ALCOHOL -> R.string.ss_mark_alcohol
        Mark.LATE_MEAL -> R.string.ss_mark_late_meal
        Mark.LATE_WORKOUT -> R.string.ss_mark_late_workout
        Mark.STRESS -> R.string.ss_mark_stress
        Mark.SCREENS_IN_BED -> R.string.ss_mark_screens_in_bed
        Mark.AWAY_FROM_HOME -> R.string.ss_mark_away_from_home
        Mark.UNWELL -> R.string.ss_mark_unwell
        Mark.NIGHT_STEPS_DONE -> R.string.ss_mark_night_steps_done
        Mark.HUSH_USED -> R.string.ss_mark_hush_used
        Mark.ANCHOR_DROPPED -> R.string.ss_mark_anchor_dropped
    }

@Composable
@ReadOnlyComposable
fun Mark.label(): String = stringResource(labelRes)

// ---------------------------------------------------------------- Morning feel

/** One word each, no faces: Foggy / Slow / Okay / Clear / Bright. */
@get:StringRes
val MorningFeel.labelRes: Int
    get() = when (this) {
        MorningFeel.FOGGY -> R.string.ss_feel_foggy
        MorningFeel.SLOW -> R.string.ss_feel_slow
        MorningFeel.OKAY -> R.string.ss_feel_okay
        MorningFeel.CLEAR -> R.string.ss_feel_clear
        MorningFeel.BRIGHT -> R.string.ss_feel_bright
    }

@Composable
@ReadOnlyComposable
fun MorningFeel.label(): String = stringResource(labelRes)

// ---------------------------------------------------------------- Keel

/** "Even keel" (80 and up), "Fair" (70–79), "Choppy" (under 70). */
@get:StringRes
val KeelBand.labelRes: Int
    get() = when (this) {
        KeelBand.EVEN -> R.string.ss_keel_even
        KeelBand.FAIR -> R.string.ss_keel_fair
        KeelBand.CHOPPY -> R.string.ss_keel_choppy
    }

@Composable
@ReadOnlyComposable
fun KeelBand.label(): String = stringResource(labelRes)

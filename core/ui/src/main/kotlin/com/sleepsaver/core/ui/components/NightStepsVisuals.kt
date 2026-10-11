package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.domain.nightsteps.StepCategory
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.ui.R

// ---------------------------------------------------------------- Techniques (the emoji is `Technique.emoji`)

/** "Slow tide breathing", "Warm shower or bath". */
@get:StringRes
val Technique.titleRes: Int
    get() = when (this) {
        Technique.WARM_SHOWER -> R.string.ss_technique_warm_shower_title
        Technique.SCREENS_DOWN -> R.string.ss_technique_screens_down_title
        Technique.TOMORROW_LIST -> R.string.ss_technique_tomorrow_list_title
        Technique.SLOW_TIDE -> R.string.ss_technique_slow_tide_title
        Technique.BODY_SCAN -> R.string.ss_technique_body_scan_title
        Technique.MUSCLE_RELEASE -> R.string.ss_technique_muscle_release_title
        Technique.READ_ON_PAPER -> R.string.ss_technique_read_on_paper_title
        Technique.STRETCHING -> R.string.ss_technique_stretching_title
        Technique.JOURNALING -> R.string.ss_technique_journaling_title
        Technique.COOL_DARK_ROOM -> R.string.ss_technique_cool_dark_room_title
        Technique.HUSH -> R.string.ss_technique_hush_title
        Technique.TV -> R.string.ss_technique_tv_title
        Technique.GAMING -> R.string.ss_technique_gaming_title
    }

/** How to do it, in one or two sentences: "Breathe in for about 4 seconds and out for about 6: …". */
@get:StringRes
val Technique.howToRes: Int
    get() = when (this) {
        Technique.WARM_SHOWER -> R.string.ss_technique_warm_shower_how
        Technique.SCREENS_DOWN -> R.string.ss_technique_screens_down_how
        Technique.TOMORROW_LIST -> R.string.ss_technique_tomorrow_list_how
        Technique.SLOW_TIDE -> R.string.ss_technique_slow_tide_how
        Technique.BODY_SCAN -> R.string.ss_technique_body_scan_how
        Technique.MUSCLE_RELEASE -> R.string.ss_technique_muscle_release_how
        Technique.READ_ON_PAPER -> R.string.ss_technique_read_on_paper_how
        Technique.STRETCHING -> R.string.ss_technique_stretching_how
        Technique.JOURNALING -> R.string.ss_technique_journaling_how
        Technique.COOL_DARK_ROOM -> R.string.ss_technique_cool_dark_room_how
        Technique.HUSH -> R.string.ss_technique_hush_how
        Technique.TV -> R.string.ss_technique_tv_how
        Technique.GAMING -> R.string.ss_technique_gaming_how
    }

/** "Why it helps", hedged to match the evidence: "Slow breathing with a longer out-breath can help you feel calmer. …". */
@get:StringRes
val Technique.whyRes: Int
    get() = when (this) {
        Technique.WARM_SHOWER -> R.string.ss_technique_warm_shower_why
        Technique.SCREENS_DOWN -> R.string.ss_technique_screens_down_why
        Technique.TOMORROW_LIST -> R.string.ss_technique_tomorrow_list_why
        Technique.SLOW_TIDE -> R.string.ss_technique_slow_tide_why
        Technique.BODY_SCAN -> R.string.ss_technique_body_scan_why
        Technique.MUSCLE_RELEASE -> R.string.ss_technique_muscle_release_why
        Technique.READ_ON_PAPER -> R.string.ss_technique_read_on_paper_why
        Technique.STRETCHING -> R.string.ss_technique_stretching_why
        Technique.JOURNALING -> R.string.ss_technique_journaling_why
        Technique.COOL_DARK_ROOM -> R.string.ss_technique_cool_dark_room_why
        Technique.HUSH -> R.string.ss_technique_hush_why
        Technique.TV -> R.string.ss_technique_tv_why
        Technique.GAMING -> R.string.ss_technique_gaming_why
    }

/** Where the evidence comes from, in plain words: "A 2019 review of 17 studies." */
@get:StringRes
val Technique.sourceRes: Int
    get() = when (this) {
        Technique.WARM_SHOWER -> R.string.ss_technique_warm_shower_source
        Technique.SCREENS_DOWN -> R.string.ss_technique_screens_down_source
        Technique.TOMORROW_LIST -> R.string.ss_technique_tomorrow_list_source
        Technique.SLOW_TIDE -> R.string.ss_technique_slow_tide_source
        Technique.BODY_SCAN -> R.string.ss_technique_body_scan_source
        Technique.MUSCLE_RELEASE -> R.string.ss_technique_muscle_release_source
        Technique.READ_ON_PAPER -> R.string.ss_technique_read_on_paper_source
        Technique.STRETCHING -> R.string.ss_technique_stretching_source
        Technique.JOURNALING -> R.string.ss_technique_journaling_source
        Technique.COOL_DARK_ROOM -> R.string.ss_technique_cool_dark_room_source
        Technique.HUSH -> R.string.ss_technique_hush_source
        Technique.TV -> R.string.ss_technique_tv_source
        Technique.GAMING -> R.string.ss_technique_gaming_source
    }

@Composable
@ReadOnlyComposable
fun Technique.title(): String = stringResource(titleRes)

@Composable
@ReadOnlyComposable
fun Technique.howTo(): String = stringResource(howToRes)

@Composable
@ReadOnlyComposable
fun Technique.why(): String = stringResource(whyRes)

@Composable
@ReadOnlyComposable
fun Technique.source(): String = stringResource(sourceRes)

// ---------------------------------------------------------------- Step library groups

/** Breathing / Body / Mind / Room / Routine. */
@get:StringRes
val StepCategory.titleRes: Int
    get() = when (this) {
        StepCategory.BREATHING -> R.string.ss_step_category_breathing
        StepCategory.BODY -> R.string.ss_step_category_body
        StepCategory.MIND -> R.string.ss_step_category_mind
        StepCategory.ENVIRONMENT -> R.string.ss_step_category_environment
        StepCategory.ROUTINE -> R.string.ss_step_category_routine
    }

@Composable
@ReadOnlyComposable
fun StepCategory.title(): String = stringResource(titleRes)

val StepCategory.icon: ImageVector
    get() = when (this) {
        StepCategory.BREATHING -> Icons.Rounded.Air
        StepCategory.BODY -> Icons.Rounded.SelfImprovement
        StepCategory.MIND -> Icons.Rounded.Psychology
        StepCategory.ENVIRONMENT -> Icons.Rounded.Window
        StepCategory.ROUTINE -> Icons.Rounded.EventRepeat
    }

// ---------------------------------------------------------------- Evidence

/** Good / Some / Early / Little evidence. */
@get:StringRes
val EvidenceLevel.labelRes: Int
    get() = when (this) {
        EvidenceLevel.GOOD -> R.string.ss_evidence_good
        EvidenceLevel.MODERATE -> R.string.ss_evidence_moderate
        EvidenceLevel.EARLY -> R.string.ss_evidence_early
        EvidenceLevel.LITTLE -> R.string.ss_evidence_little
    }

@Composable
@ReadOnlyComposable
fun EvidenceLevel.label(): String = stringResource(labelRes)

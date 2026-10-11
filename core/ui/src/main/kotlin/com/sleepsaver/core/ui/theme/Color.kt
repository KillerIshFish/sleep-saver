package com.sleepsaver.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// One accent ("tide"), everything else is greys on near-black.
internal val Tide = Color(0xFF3DD6C3)
internal val TideDim = Color(0xFF1E6E66)
internal val TideContainer = Color(0xFF0F2E2B)
internal val OnTide = Color(0xFF03201C)

internal val Ink = Color(0xFF0A0C10)          // window background
internal val Surface1 = Color(0xFF12151B)     // sheets, bars
internal val Surface2 = Color(0xFF191D24)     // cards
internal val Surface3 = Color(0xFF222731)     // chips, pressed cards
internal val Outline = Color(0xFF2C323D)
internal val OutlineSoft = Color(0xFF1F242C)

internal val TextPrimary = Color(0xFFF1F3F6)
internal val TextSecondary = Color(0xFF9AA4B2)
internal val TextTertiary = Color(0xFF6B7482)

internal val Amber = Color(0xFFF2B84B)
internal val Coral = Color(0xFFFF6F61)

/**
 * Colours that Material's scheme has no slot for. Read via `SleepSaverTheme.colors`.
 * The ringing screen's warm glow is the only non-grey surface; it stays dark.
 */
@Immutable
data class SleepSaverExtendedColors(
    val debtLow: Color,
    val debtModerate: Color,
    val debtHigh: Color,
    val sleepBlock: Color,
    val sleepBlockStroke: Color,
    val napBlock: Color,
    val awakeGap: Color,
    val stageDeep: Color,
    val stageLight: Color,
    val stageRem: Color,
    val stageAwake: Color,
    val energyCurve: Color,
    val energyCurveFaded: Color,
    val melatonin: Color,
    val wakeZone: Color,
    val nowLine: Color,
    val gridLine: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val card: Color,
    val cardRaised: Color,
    /** The ringing screen's warm glow (`WarmGlow`): a dark ember, drawn at 35 % over Ink. Text stays far above 6:1 on it. */
    val ringGlow: Color,
)

val DarkExtendedColors = SleepSaverExtendedColors(
    debtLow = Tide,
    debtModerate = Amber,
    debtHigh = Coral,
    sleepBlock = Color(0xFF123634),
    sleepBlockStroke = Tide.copy(alpha = 0.55f),
    napBlock = Color(0xFF1B4B47),
    awakeGap = Color(0xFF3A2A2E),
    stageDeep = Color(0xFF1F8F84),
    stageLight = Color(0xFF59C9BD),
    stageRem = Color(0xFFA6EDE4),
    stageAwake = Coral.copy(alpha = 0.85f),
    energyCurve = Tide,
    energyCurveFaded = Tide.copy(alpha = 0.25f),
    melatonin = Color(0xFF7E8CE0),
    wakeZone = Amber.copy(alpha = 0.18f),
    nowLine = Color(0xFFF1F3F6),
    gridLine = Color(0xFF1C2028),
    textSecondary = TextSecondary,
    textTertiary = TextTertiary,
    card = Surface2,
    cardRaised = Surface3,
    ringGlow = Color(0xFF4A2814),
)

val LocalSleepSaverColors = staticCompositionLocalOf { DarkExtendedColors }

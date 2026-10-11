package com.sleepsaver.core.domain.nightsteps

import java.time.Duration

/**
 * How well studied a technique is for sleep. UI: Good / Some / Early / Little evidence. Rule: research MODERATE or
 * stronger → GOOD; LOW–MODERATE → MODERATE; LOW → EARLY; VERY LOW or none → LITTLE.
 */
enum class EvidenceLevel { GOOD, MODERATE, EARLY, LITTLE }

/** Library groups for the step library. */
enum class StepCategory { BREATHING, BODY, MIND, ENVIRONMENT, ROUTINE }

/** The guided tool that runs a technique. */
enum class GuidedTool { NONE, SLOW_TIDE, BODY_SCAN, MUSCLE_RELEASE, TOMORROW_LIST, HUSH }

/** Where a step sits: [lead] before the bedtime B it starts; [duration] long (zero = a checklist item). */
data class StepTiming(val lead: Duration, val duration: Duration)

/**
 * A known Night step. Default lead and duration live in config: `config.steps.timing(technique)` (§2.1).
 * Example: SLOW_TIDE is a BREATHING step with MODERATE evidence that opens the Slow tide tool.
 */
enum class Technique(
    val emoji: String,
    val category: StepCategory,
    val evidence: EvidenceLevel,
    val tool: GuidedTool,
    /** Seeded as "selected" for new installs. */
    val defaultOn: Boolean,
    /** Screen-based: never suggested, hidden from the library (mapping target for seeded rows only). */
    val screenBased: Boolean = false,
) {
    // Haghayegh 2019
    WARM_SHOWER("🚿", StepCategory.ROUTINE, EvidenceLevel.GOOD, GuidedTool.NONE, defaultOn = false),

    // Chang 2015, Brown 2022
    SCREENS_DOWN("📵", StepCategory.ENVIRONMENT, EvidenceLevel.GOOD, GuidedTool.NONE, defaultOn = true),

    // Scullin 2018
    TOMORROW_LIST("📝", StepCategory.MIND, EvidenceLevel.MODERATE, GuidedTool.TOMORROW_LIST, defaultOn = true),

    // Laborde 2019, Eide 2026
    SLOW_TIDE("🌊", StepCategory.BREATHING, EvidenceLevel.MODERATE, GuidedTool.SLOW_TIDE, defaultOn = true),

    // Black 2015, Ong 2014
    BODY_SCAN("🌙", StepCategory.MIND, EvidenceLevel.GOOD, GuidedTool.BODY_SCAN, defaultOn = false),

    // AASM 2021
    MUSCLE_RELEASE("💪", StepCategory.BODY, EvidenceLevel.MODERATE, GuidedTool.MUSCLE_RELEASE, defaultOn = false),

    READ_ON_PAPER("📖", StepCategory.ROUTINE, EvidenceLevel.EARLY, GuidedTool.NONE, defaultOn = false),
    STRETCHING("🧘", StepCategory.BODY, EvidenceLevel.EARLY, GuidedTool.NONE, defaultOn = false),
    JOURNALING("✍️", StepCategory.MIND, EvidenceLevel.EARLY, GuidedTool.NONE, defaultOn = false),

    // Okamoto-Mizuno 2012
    COOL_DARK_ROOM("❄️", StepCategory.ENVIRONMENT, EvidenceLevel.GOOD, GuidedTool.NONE, defaultOn = false),

    // Riedy 2021, Basner 2026
    HUSH("🔈", StepCategory.ENVIRONMENT, EvidenceLevel.LITTLE, GuidedTool.HUSH, defaultOn = false),

    TV("📺", StepCategory.ROUTINE, EvidenceLevel.LITTLE, GuidedTool.NONE, defaultOn = false, screenBased = true),
    GAMING("🎮", StepCategory.ROUTINE, EvidenceLevel.LITTLE, GuidedTool.NONE, defaultOn = false, screenBased = true),
}

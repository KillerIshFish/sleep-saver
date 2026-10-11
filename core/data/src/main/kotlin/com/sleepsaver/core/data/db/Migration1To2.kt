package com.sleepsaver.core.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (Sleep Saver v2: week plan, Night steps, journal, night log). Room adds the 15 new tables and the three
 * routine_activities columns (techniqueKey NULL, leadMin 45, durationMin 10); this spec then maps the seven v1 default
 * activities to their Night steps technique and timing. Rows are matched by emoji, never by label: emoji are
 * locale-proof, labels aren't. Custom rows keep the column defaults.
 *
 * The numbers are literals on purpose: a migration describes one moment and must not move when SleepConfig changes
 * (MigrationTest pins them to `config.steps.timing(...)` / `customLead` / `customDuration` as they are today).
 */
class Migration1To2 : AutoMigrationSpec {

    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        for (default in V1_DEFAULTS) {
            db.execSQL(
                "UPDATE routine_activities SET techniqueKey = ?, leadMin = ?, durationMin = ? WHERE isCustom = 0 AND emoji = ?",
                arrayOf<Any?>(default.techniqueKey, default.leadMin, default.durationMin, default.emoji),
            )
        }
    }

    /** One v1 default activity, as HabitsRepositoryImpl seeded it in v1. */
    internal class V1Default(val emoji: String, val techniqueKey: String?, val leadMin: Int, val durationMin: Int)

    internal companion object {
        /** The v1 seed list (emoji copied from v1's HabitsRepositoryImpl) and what each becomes. */
        val V1_DEFAULTS = listOf(
            V1Default("📖", "READ_ON_PAPER", 50, 20),
            V1Default("🧘", "STRETCHING", 40, 10),
            V1Default("🚿", "WARM_SHOWER", 90, 15),
            // Podcast has no technique: it stays a plain activity with its own length.
            V1Default("🎧", null, 45, 20),
            V1Default("✍️", "JOURNALING", 35, 5),
            V1Default("📺", "TV", 120, 30),
            V1Default("🎮", "GAMING", 120, 30),
        )
    }
}

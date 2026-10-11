package com.sleepsaver.core.data.db

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** v1 → v2 against the committed schemas (1.json, 2.json are test assets). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = SlashPathContext(ApplicationProvider.getApplicationContext())

    @get:Rule
    val helper = MigrationTestHelper(TestInstrumentation(context), SleepSaverDatabase::class.java)

    private var room: SleepSaverDatabase? = null

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun `v1 rows survive, defaults get their technique and timing, new tables start empty`() = runTest {
        helper.createDatabase(TEST_DB, 1).use { it.insertV1Rows() }

        helper.runMigrationsAndValidate(TEST_DB, 2, true).close()

        val db = Room.databaseBuilder(context, SleepSaverDatabase::class.java, TEST_DB).allowMainThreadQueries().build()
        room = db

        // Every v1 table kept its row.
        val session = db.sleepSessionDao().getWithStages(0, Long.MAX_VALUE).single()
        assertThat(session.session).isEqualTo(
            SleepSessionEntity("s1", "com.garmin.android.apps.connectmobile", 1_000, 2_000, 3_600, 3_600, "Europe/Berlin", null, 2_000, "HEALTH_CONNECT"),
        )
        assertThat(session.stages).containsExactly(SleepStageEntity("s1", 1_000, 2_000, 4))
        assertThat(db.nightOverrideDao().getAll()).containsExactly(NightOverrideEntity("2026-10-05", 1_000, 2_000, 15, null, false, 3_600, 3_000))
        assertThat(db.addedAwakeDao().getAll()).containsExactly(AddedAwakeEntity(1, "2026-10-05", 1_200, 1_300, 3_000))
        assertThat(db.manualNapDao().getAll()).containsExactly(ManualNapEntity(1, 5_000, 6_000, 3_600, 6_000))
        assertThat(db.vitalDao().getAll()).containsExactly(VitalEntity("v1", "RESTING_HR", 2_000, 3_600, 52.0, "com.garmin.android.apps.connectmobile", "HEALTH_CONNECT"))
        assertThat(db.morningReviewDao().getAll()).containsExactly(MorningReviewEntity("2026-10-05", "CONFIRMED", 3_000, 120, 90))
        assertThat(db.habitDao().getAll()).containsExactly(HabitEntity("BEDTIME", true, true, 0, 0))

        // Defaults are mapped by emoji whatever their (localized) label; custom rows keep the column defaults.
        val activities = db.routineActivityDao().getAll().associateBy { it.label }
        assertThat(activities).hasSize(V1_ROWS.size)
        for (default in Migration1To2.V1_DEFAULTS) {
            val row = activities.values.single { !it.isCustom && it.emoji == default.emoji }
            assertWithMessage(row.label).that(Triple(row.techniqueKey, row.leadMin, row.durationMin))
                .isEqualTo(Triple(default.techniqueKey, default.leadMin, default.durationMin))
        }
        for (label in listOf("Puzzles", "Bath (custom)")) {
            val row = activities.getValue(label)
            assertWithMessage(label).that(Triple(row.techniqueKey, row.leadMin, row.durationMin)).isEqualTo(Triple(null, 45, 10))
        }
        // Everything else about the routine rows is untouched.
        assertThat(activities.getValue("Lesen").selected).isTrue()
        assertThat(activities.getValue("Lesen").sortOrder).isEqualTo(0)

        // The 15 new tables are empty.
        val newTables = listOf(
            "day_plans", "day_routine_items", "plan_overrides", "plan_pauses", "night_step_logs", "tomorrow_lists",
            "night_marks", "morning_feel", "anchors", "movement_logs", "movement_epochs", "alarm_events", "alarm_tuning",
            "hush_plays", "prompt_log",
        )
        for (table in newTables) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertWithMessage(table).that(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun `migration literals match the config they were copied from`() {
        val steps = SleepConfig.DEFAULT.steps
        // Column defaults (custom and unmapped rows) = the custom lead and duration.
        val defaults = RoutineActivityEntity(label = "x", emoji = "x", isCustom = true, selected = false, sortOrder = 0)
        assertThat(defaults.leadMin.toLong()).isEqualTo(steps.customLead.toMinutes())
        assertThat(defaults.durationMin.toLong()).isEqualTo(steps.customDuration.toMinutes())
        // Each mapped default = its technique's timing.
        for (default in Migration1To2.V1_DEFAULTS) {
            val key = default.techniqueKey ?: continue
            val timing = steps.timing(Technique.valueOf(key))
            assertWithMessage(key).that(default.leadMin.toLong()).isEqualTo(timing.lead.toMinutes())
            assertWithMessage(key).that(default.durationMin.toLong()).isEqualTo(timing.duration.toMinutes())
        }
        // The emoji are the techniques' own.
        for (default in Migration1To2.V1_DEFAULTS) {
            val key = default.techniqueKey ?: continue
            assertWithMessage(key).that(default.emoji).isEqualTo(Technique.valueOf(key).emoji)
        }
    }

    private fun SupportSQLiteDatabase.insertV1Rows() {
        execSQL(
            "INSERT INTO sleep_sessions VALUES ('s1', 'com.garmin.android.apps.connectmobile', 1000, 2000, 3600, 3600, " +
                "'Europe/Berlin', NULL, 2000, 'HEALTH_CONNECT')",
        )
        execSQL("INSERT INTO sleep_stages VALUES ('s1', 1000, 2000, 4)")
        execSQL("INSERT INTO night_overrides VALUES ('2026-10-05', 1000, 2000, 15, NULL, 0, 3600, 3000)")
        execSQL("INSERT INTO added_awake VALUES (1, '2026-10-05', 1200, 1300, 3000)")
        execSQL("INSERT INTO manual_naps VALUES (1, 5000, 6000, 3600, 6000)")
        execSQL("INSERT INTO vitals VALUES ('v1', 'RESTING_HR', 2000, 3600, 52.0, 'com.garmin.android.apps.connectmobile', 'HEALTH_CONNECT')")
        execSQL("INSERT INTO morning_reviews VALUES ('2026-10-05', 'CONFIRMED', 3000, 120, 90)")
        execSQL("INSERT INTO habits VALUES ('BEDTIME', 1, 1, 0, 0)")
        V1_ROWS.forEachIndexed { index, (label, emoji, custom) ->
            execSQL(
                "INSERT INTO routine_activities (label, emoji, isCustom, selected, sortOrder) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(label, emoji, if (custom) 1 else 0, if (index == 0) 1 else 0, index),
            )
        }
    }

    /**
     * Room's SupportSQLiteDriver matches the requested file by the part after the last '/', which a Windows host's
     * database path doesn't have. This context hands out '/' absolute paths; the file itself is unchanged.
     */
    private class SlashPathContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = SlashFile(super.getDatabasePath(name))
    }

    private class SlashFile(file: File) : File(file.path) {
        override fun getAbsolutePath(): String = super.getAbsolutePath().replace('\\', '/')
    }

    /** The real instrumentation's assets (the schemas) with [target] as the database context. */
    private class TestInstrumentation(private val target: Context) : Instrumentation() {
        override fun getTargetContext(): Context = target
        override fun getContext(): Context = InstrumentationRegistry.getInstrumentation().context
    }

    private companion object {
        const val TEST_DB = "migration-test"

        /** v1's seven defaults (one with a German label) plus two custom rows, one reusing a default's emoji. */
        val V1_ROWS = listOf(
            Triple("Lesen", "📖", false),
            Triple("Stretching", "🧘", false),
            Triple("Warm shower", "🚿", false),
            Triple("Podcast", "🎧", false),
            Triple("Journaling", "✍️", false),
            Triple("TV", "📺", false),
            Triple("Gaming", "🎮", false),
            Triple("Puzzles", "🧩", true),
            Triple("Bath (custom)", "🚿", true),
        )
    }
}

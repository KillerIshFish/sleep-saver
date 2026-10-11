package com.sleepsaver.core.data.demo

import android.content.Context
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.TomorrowListEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The user's real edits, set aside while demo mode runs: every row of the four edit tables and (version 2) of the
 * four journal tables (marks, Morning feel, Night steps logs, Tomorrow lists), column for column, so they come back
 * exactly (ids included). Its own format (not the Room entities), versioned; a version 1 file reads with empty journal
 * lists.
 */
@Serializable
internal data class DemoEditBackup(
    val version: Int = VERSION,
    val overrides: List<OverrideRow> = emptyList(),
    val addedAwake: List<AddedAwakeRow> = emptyList(),
    val manualNaps: List<ManualNapRow> = emptyList(),
    val reviews: List<ReviewRow> = emptyList(),
    val nightMarks: List<MarkRow> = emptyList(),
    val morningFeel: List<FeelRow> = emptyList(),
    val nightStepLogs: List<StepLogRow> = emptyList(),
    val tomorrowLists: List<TomorrowListRow> = emptyList(),
) {
    @Serializable
    data class OverrideRow(
        val wakeDate: String,
        val fellAsleepEpochMs: Long?,
        val wokeUpEpochMs: Long?,
        val awakeMinutes: Int?,
        val napMinutes: Int?,
        val isManualNight: Boolean,
        val offsetSec: Int,
        val updatedAtEpochMs: Long,
    ) {
        fun toEntity() = NightOverrideEntity(
            wakeDate, fellAsleepEpochMs, wokeUpEpochMs, awakeMinutes, napMinutes, isManualNight, offsetSec, updatedAtEpochMs,
        )
    }

    @Serializable
    data class AddedAwakeRow(val id: Long, val wakeDate: String, val startEpochMs: Long, val endEpochMs: Long, val createdAtEpochMs: Long) {
        fun toEntity() = AddedAwakeEntity(id, wakeDate, startEpochMs, endEpochMs, createdAtEpochMs)
    }

    @Serializable
    data class ManualNapRow(val id: Long, val startEpochMs: Long, val endEpochMs: Long, val offsetSec: Int, val createdAtEpochMs: Long) {
        fun toEntity() = ManualNapEntity(id, startEpochMs, endEpochMs, offsetSec, createdAtEpochMs)
    }

    @Serializable
    data class ReviewRow(val wakeDate: String, val status: String, val atEpochMs: Long, val debtBeforeMin: Int?, val debtAfterMin: Int?) {
        fun toEntity() = MorningReviewEntity(wakeDate, status, atEpochMs, debtBeforeMin, debtAfterMin)
    }

    @Serializable
    data class MarkRow(val wakeDate: String, val mark: String, val createdAtEpochMs: Long, val offsetSec: Int) {
        fun toEntity() = NightMarkEntity(wakeDate, mark, createdAtEpochMs, offsetSec)
    }

    @Serializable
    data class FeelRow(val wakeDate: String, val level: Int, val atEpochMs: Long, val offsetSec: Int, val source: String) {
        fun toEntity() = MorningFeelEntity(wakeDate, level, atEpochMs, offsetSec, source)
    }

    @Serializable
    data class StepLogRow(val nightOf: String, val activityId: Long, val techniqueKey: String?, val doneAtEpochMs: Long, val offsetSec: Int) {
        fun toEntity() = NightStepLogEntity(nightOf, activityId, techniqueKey, doneAtEpochMs, offsetSec)
    }

    @Serializable
    data class TomorrowListRow(val nightOf: String, val text: String, val updatedAtEpochMs: Long, val offsetSec: Int) {
        fun toEntity() = TomorrowListEntity(nightOf, text, updatedAtEpochMs, offsetSec)
    }

    companion object {
        const val VERSION = 2

        fun of(
            overrides: List<NightOverrideEntity>,
            addedAwake: List<AddedAwakeEntity>,
            manualNaps: List<ManualNapEntity>,
            reviews: List<MorningReviewEntity>,
            nightMarks: List<NightMarkEntity> = emptyList(),
            morningFeel: List<MorningFeelEntity> = emptyList(),
            nightStepLogs: List<NightStepLogEntity> = emptyList(),
            tomorrowLists: List<TomorrowListEntity> = emptyList(),
        ) = DemoEditBackup(
            overrides = overrides.map {
                OverrideRow(
                    it.wakeDate, it.fellAsleepEpochMs, it.wokeUpEpochMs, it.awakeMinutes, it.napMinutes, it.isManualNight,
                    it.offsetSec, it.updatedAtEpochMs,
                )
            },
            addedAwake = addedAwake.map { AddedAwakeRow(it.id, it.wakeDate, it.startEpochMs, it.endEpochMs, it.createdAtEpochMs) },
            manualNaps = manualNaps.map { ManualNapRow(it.id, it.startEpochMs, it.endEpochMs, it.offsetSec, it.createdAtEpochMs) },
            reviews = reviews.map { ReviewRow(it.wakeDate, it.status, it.atEpochMs, it.debtBeforeMin, it.debtAfterMin) },
            nightMarks = nightMarks.map { MarkRow(it.wakeDate, it.mark, it.createdAtEpochMs, it.offsetSec) },
            morningFeel = morningFeel.map { FeelRow(it.wakeDate, it.level, it.atEpochMs, it.offsetSec, it.source) },
            nightStepLogs = nightStepLogs.map { StepLogRow(it.nightOf, it.activityId, it.techniqueKey, it.doneAtEpochMs, it.offsetSec) },
            tomorrowLists = tomorrowLists.map { TomorrowListRow(it.nightOf, it.text, it.updatedAtEpochMs, it.offsetSec) },
        )
    }
}

/**
 * `filesDir/demo_edit_backup.json`. Written atomically (a synced temp file renamed over the target), so it is either
 * complete or absent, never half written.
 */
internal class DemoEditBackupFile(private val file: File) {

    fun exists(): Boolean = file.isFile

    fun write(backup: DemoEditBackup) {
        val bytes = json.encodeToString(DemoEditBackup.serializer(), backup).toByteArray(Charsets.UTF_8)
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    /**
     * null when there is no backup. Throws IOException when it can't be read right now, and
     * kotlinx.serialization.SerializationException (an IllegalArgumentException) when its content is damaged.
     */
    fun read(): DemoEditBackup? {
        if (!exists()) return null
        return json.decodeFromString(DemoEditBackup.serializer(), file.readText(Charsets.UTF_8))
    }

    fun delete() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    companion object {
        const val FILE_NAME = "demo_edit_backup.json"

        fun inFilesDir(context: Context) = DemoEditBackupFile(File(context.filesDir, FILE_NAME))

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

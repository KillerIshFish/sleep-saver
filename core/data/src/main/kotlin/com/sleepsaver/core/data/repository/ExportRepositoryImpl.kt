package com.sleepsaver.core.data.repository

import androidx.room.withTransaction
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.export.CsvExtras
import com.sleepsaver.core.data.export.ExportDocument
import com.sleepsaver.core.data.export.NightsCsv
import com.sleepsaver.core.data.export.toDto
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.AnchorStatsCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Writes UTF-8 to the caller's stream and flushes it; the caller closes it. Never modifies any data. */
@Singleton
class ExportRepositoryImpl @Inject constructor(
    private val database: SleepSaverDatabase,
    private val sleepRepository: SleepRepository,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportRepository {

    private val anchorStats = AnchorStatsCalculator(SleepConfig.DEFAULT)

    /** The nights plus, per wake date, Morning feel, the stored marks and the Drop anchor latency as shown. */
    override suspend fun exportCsv(out: OutputStream) {
        val nights = sleepRepository.nights.first()
        val extras = csvExtras(nights)
        withContext(ioDispatcher) {
            val writer = out.writer(Charsets.UTF_8).buffered()
            NightsCsv.write(nights, writer) { extras[it] ?: CsvExtras.NONE }
            writer.flush()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun exportJson(out: OutputStream) {
        val document = snapshot()
        withContext(ioDispatcher) {
            json.encodeToStream(document, out)
            out.flush()
        }
    }

    private suspend fun csvExtras(nights: List<Night>): Map<LocalDate, CsvExtras> {
        val (marks, feel, anchors) = database.withTransaction {
            Triple(
                database.nightMarkDao().getAll(),
                database.morningFeelDao().getAll().mapNotNull { it.toDomainOrNull() }.associateBy { it.wakeDate },
                database.anchorDao().getAll().map { it.toDomain() },
            )
        }
        val marksByDate = marks.mapNotNull { row -> row.toDomainOrNull()?.let { LocalDate.parse(row.wakeDate) to it.name } }
            .groupBy({ it.first }, { it.second })
        return nights.associate { night ->
            night.wakeDate to CsvExtras(
                morningFeel = feel[night.wakeDate]?.feel?.level,
                marks = marksByDate[night.wakeDate].orEmpty().sorted(),
                anchorLatencyMin = shownLatency(anchors, night),
            )
        }
    }

    private fun shownLatency(anchors: List<Anchor>, night: Night): Long? =
        anchorStats.choose(anchors, night)?.let { anchorStats.night(it, night).latencyShown?.toMinutes() }

    /** Reads every table in one transaction so the export is consistent even while a sync is running. */
    private suspend fun snapshot(): ExportDocument {
        val settings = settingsRepository.current()
        return database.withTransaction {
            ExportDocument(
                schemaVersion = ExportDocument.SCHEMA_VERSION,
                exportedAt = timeProvider.now().toString(),
                settings = settings.toDto(),
                rawSessions = database.sleepSessionDao().getWithStages(Long.MIN_VALUE, Long.MAX_VALUE).map { it.toDto() },
                overrides = database.nightOverrideDao().getAll().sortedBy { it.wakeDate }.map { it.toDto() },
                manualNaps = database.manualNapDao().getAll().sortedBy { it.startEpochMs }.map { it.toDto() },
                addedAwake = database.addedAwakeDao().getAll().sortedBy { it.startEpochMs }.map { it.toDto() },
                vitals = database.vitalDao().getAll().sortedBy { it.timeEpochMs }.map { it.toDto() },
                habits = database.habitDao().getAll().map { it.toDto() },
                routineActivities = database.routineActivityDao().getAll().map { it.toDto() },
                reviews = database.morningReviewDao().getAll().map { it.toDto() },
                dayPlans = database.dayPlanDao().getAll().map { it.toDto() },
                dayRoutineItems = database.dayRoutineItemDao().getAll().map { it.toDto() },
                planOverrides = database.planOverrideDao().getAll().map { it.toDto() },
                planPauses = database.planPauseDao().getAll().map { it.toDto() },
                nightStepLogs = database.nightStepLogDao().getAll().map { it.toDto() },
                tomorrowLists = database.tomorrowListDao().getAll().map { it.toDto() },
                nightMarks = database.nightMarkDao().getAll().map { it.toDto() },
                morningFeel = database.morningFeelDao().getAll().map { it.toDto() },
                anchors = database.anchorDao().getAll().map { it.toDto() },
                movementLogs = database.movementLogDao().getAllLogs().map { it.toDto() },
                alarmEvents = database.alarmEventDao().getAll().map { it.toDto() },
                alarmTuning = database.alarmTuningDao().getAll().map { it.toDto() },
                hushPlays = database.hushPlayDao().getAll().map { it.toDto() },
            )
        }
    }

    private companion object {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

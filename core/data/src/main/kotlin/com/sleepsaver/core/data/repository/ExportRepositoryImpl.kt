package com.sleepsaver.core.data.repository

import androidx.room.withTransaction
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.export.ExportDocument
import com.sleepsaver.core.data.export.NightsCsv
import com.sleepsaver.core.data.export.toDto
import com.sleepsaver.core.data.hooks.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
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

    override suspend fun exportCsv(out: OutputStream) {
        val nights = sleepRepository.nights.first()
        withContext(ioDispatcher) {
            val writer = out.writer(Charsets.UTF_8).buffered()
            NightsCsv.write(nights, writer)
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

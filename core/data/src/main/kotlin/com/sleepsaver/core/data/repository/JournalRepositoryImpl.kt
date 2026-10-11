package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.journal.markEditable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marks, Morning feel, Tomorrow lists and the prompt log in Room. Rows written now carry the current device offset.
 * Example: `setMark(tuesday, Mark.ALCOHOL, on = true)` on Monday evening stores (2026-10-06, ALCOHOL); setting it again
 * keeps the first row and its time.
 *
 * DI edges (§3.4): the database and [TimeProvider] only.
 */
@Singleton
class JournalRepositoryImpl @Inject constructor(
    database: SleepSaverDatabase,
    private val timeProvider: TimeProvider,
) : JournalRepository {

    private val config = SleepConfig.DEFAULT
    private val markDao = database.nightMarkDao()
    private val feelDao = database.morningFeelDao()
    private val listDao = database.tomorrowListDao()
    private val promptDao = database.promptLogDao()

    override fun marks(wakeDate: LocalDate): Flow<Set<Mark>> = markDao.observe(wakeDate.toString())
        .orFallback(TAG, "Marks query") { emptyList() }
        .map { rows -> rows.mapNotNullTo(LinkedHashSet()) { it.toDomainOrNull() } }
        .distinctUntilChanged()

    override val marksByDate: Flow<Map<LocalDate, Set<Mark>>> = markDao.observeAll()
        .orFallback(TAG, "Marks query") { emptyList() }
        .map(::marksByDate)
        .distinctUntilChanged()

    override suspend fun setMark(wakeDate: LocalDate, mark: Mark, on: Boolean) {
        require(!mark.derived) { "$mark is derived and can't be set" }
        require(markEditable(wakeDate, timeProvider.today(), config)) { "marks for $wakeDate can no longer be changed" }
        if (on) {
            markDao.insert(mark.toEntity(wakeDate, timeProvider.now(), timeProvider.currentOffset()))
        } else {
            markDao.delete(wakeDate.toString(), mark.name)
        }
    }

    override fun feel(wakeDate: LocalDate): Flow<MorningFeelEntry?> = feelDao.observe(wakeDate.toString())
        .orFallback(TAG, "Morning feel query") { null }
        .map { it?.toDomainOrNull() }
        .distinctUntilChanged()

    override val feels: Flow<Map<LocalDate, MorningFeelEntry>> = feelDao.observeAll()
        .orFallback(TAG, "Morning feel query") { emptyList() }
        .map { rows -> rows.mapNotNull { it.toDomainOrNull() }.associateBy { it.wakeDate } }
        .distinctUntilChanged()

    override suspend fun setFeel(wakeDate: LocalDate, feel: MorningFeel?, source: FeelSource, at: Instant?, offset: ZoneOffset?) {
        if (feel == null) {
            feelDao.delete(wakeDate.toString())
            return
        }
        val entry = MorningFeelEntry(
            wakeDate = wakeDate,
            feel = feel,
            at = at ?: timeProvider.now(),
            offset = offset ?: timeProvider.currentOffset(),
            source = source,
        )
        feelDao.upsert(entry.toEntity())
    }

    override fun tomorrowList(nightOf: LocalDate): Flow<TomorrowList?> = listDao.observe(nightOf.toString())
        .orFallback(TAG, "Tomorrow list query") { null }
        .map { it?.toDomain() }
        .distinctUntilChanged()

    override suspend fun setTomorrowList(nightOf: LocalDate, text: String) {
        if (text.isBlank()) {
            listDao.delete(nightOf.toString())
            return
        }
        listDao.upsert(TomorrowList(nightOf, text, timeProvider.now(), timeProvider.currentOffset()).toEntity())
    }

    override fun promptShown(wakeDate: LocalDate, kind: PromptKind): Flow<Boolean> = promptDao.observe(wakeDate.toString(), kind.name)
        .orFallback(TAG, "Prompt log query") { null }
        .map { it != null }
        .distinctUntilChanged()

    override suspend fun markPromptShown(wakeDate: LocalDate, kind: PromptKind) =
        promptDao.insert(kind.toEntity(wakeDate, timeProvider.now(), timeProvider.currentOffset()))

    private companion object {
        const val TAG = "JournalRepository"
    }
}

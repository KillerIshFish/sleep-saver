package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.model.LastNightState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewRepositoryImpl @Inject constructor(
    database: SleepSaverDatabase,
    settingsRepository: SettingsRepository,
    insightsRepository: InsightsRepository,
) : ReviewRepository {

    private val dao = database.morningReviewDao()

    override fun review(wakeDate: LocalDate): Flow<MorningReview?> =
        dao.observe(wakeDate.toString())
            .orFallback(TAG, "Review query") { null }
            .map { it?.toDomainOrNull() }
            .distinctUntilChanged()

    override suspend fun record(review: MorningReview) = dao.upsert(review.toEntity())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val pendingReview: Flow<LocalDate?> = combine(
        settingsRepository.settings
            .orFallback(TAG, "Settings") { AppSettings() }
            .map { it.morningReviewEnabled }
            .distinctUntilChanged(),
        insightsRepository.state,
    ) { enabled, state ->
        val insights = (state as? InsightsState.Ready)?.insights
        insights?.today?.takeIf { enabled && insights.lastNightState == LastNightState.AVAILABLE }
    }
        .distinctUntilChanged()
        .flatMapLatest { today ->
            if (today == null) flowOf(null) else review(today).map { done -> today.takeIf { done == null } }
        }
        .distinctUntilChanged()

    private companion object {
        const val TAG = "ReviewRepository"
    }
}

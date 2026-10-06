package com.sleepsaver.feature.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Call [start] once from `Application.onCreate`. */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val insights: InsightsRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Redraws placed widgets whenever what they display changes (not on every minute's insights refresh). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            debtWidgetModels(insights.state, settings.settings)
                .debounce(1.seconds)
                .distinctUntilChanged()
                .catch { Log.w(TAG, "Widget model failed", it) }
                .collectLatest { refresh() }
        }
    }

    private suspend fun refresh() {
        try {
            DebtWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Updating widgets failed", e)
        }
    }

    private companion object {
        const val TAG = "SleepSaverWidget"
    }
}

package com.sleepsaver.feature.settings

import androidx.compose.runtime.Immutable
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.SourceSelection
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Everything the settings screen shows. Produced by [SettingsViewModel]; rendered by [SettingsContent]. */
@Immutable
data class SettingsUiState(
    /** True until the stored settings have been read once. */
    val loading: Boolean = true,

    // Sleep
    val sleepNeed: Duration = Duration.ofHours(8),
    /** The onboarding wake time, used until there are enough nights to learn the rhythm. */
    val typicalWake: LocalTime = LocalTime.of(7, 0),
    /** The wake time learned from recent nights, once there are enough of them. */
    val learnedWake: LocalTime? = null,
    val needSuggestion: NeedSuggestion? = null,

    // Goals
    val streakThreshold: Duration = Duration.ofHours(5),
    val lowDebtMax: Duration = Duration.ofHours(2),
    val highDebtMin: Duration = Duration.ofHours(5),

    // Data
    val source: SourceSelection = SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
    val sourceOptions: List<SourceOption> = SourceOption.defaults(),
    val demoMode: Boolean = false,
    /** Demo data is being loaded or cleared. */
    val demoSwitching: Boolean = false,
    val healthConnect: HealthConnectAccess = HealthConnectAccess.CHECKING,
    val sync: SyncUi = SyncUi(),
    /** The export being written, if any. */
    val exporting: ExportFormat? = null,

    // Notifications
    val morningReviewEnabled: Boolean = true,
    val notifications: NotificationAccess = NotificationAccess.ALLOWED,

    // Display
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,

    // About
    val appVersion: String = "",

    // Reset
    val resetting: Boolean = false,
    /** Set once "Reset all data" finished: the screen hands over to onboarding. */
    val resetDone: Boolean = false,

    /** A one-off snackbar message; cleared with [SettingsActions.onMessageShown]. */
    val message: SettingsMessage? = null,

    /** For "Last synced" and file names. */
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneOffset.UTC,
)

/** The engine's sleep-need estimate, offered when it differs from the current setting. */
@Immutable
data class NeedSuggestion(val need: Duration, val confidence: Confidence, val nightsUsed: Int)

enum class SourceKind { GARMIN, ALL, APP }

/**
 * One choice in the data-source list. [appLabel] is the installed app's name for [SourceKind.APP]
 * (null when it can't be resolved, e.g. the app was uninstalled; the package name is shown instead).
 */
@Immutable
data class SourceOption(
    val selection: SourceSelection,
    val kind: SourceKind,
    val appLabel: String? = null,
    /** Whether this source has written sleep to Health Connect (always true for "All sources"). */
    val hasData: Boolean = true,
) {
    val packageName: String? get() = (selection as? SourceSelection.Single)?.packageName

    companion object {
        fun defaults(garminHasData: Boolean = true) = listOf(
            SourceOption(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE), SourceKind.GARMIN, hasData = garminHasData),
            SourceOption(SourceSelection.All, SourceKind.ALL),
        )
    }
}

enum class HealthConnectAccess {
    CHECKING,
    CONNECTED,

    /** Installed and available, but sleep access hasn't been granted. */
    PERMISSION_NEEDED,
    NOT_INSTALLED,
    UPDATE_REQUIRED,
    NOT_SUPPORTED,
}

enum class NotificationAccess {
    ALLOWED,

    /** Android 13+: POST_NOTIFICATIONS hasn't been granted yet. It can be requested. */
    PERMISSION_NEEDED,

    /** Turned off for the app in system settings. Only the system screen can turn it back on. */
    BLOCKED,
}

@Immutable
data class SyncUi(
    val lastSyncedAt: Instant? = null,
    val inProgress: Boolean = false,
    /** The outcome of the last "Sync now" on this screen. */
    val feedback: SyncFeedback? = null,
    /** The error of the most recent sync from any trigger (shown when there's no fresher feedback). */
    val lastError: SyncError? = null,
)

sealed interface SyncFeedback {
    data class Success(val sessionsChanged: Int) : SyncFeedback
    data class Failed(val error: SyncError?) : SyncFeedback
}

enum class ExportFormat(val mimeType: String, val extension: String) {
    CSV("text/csv", "csv"),
    JSON("application/json", "json"),
}

enum class SettingsMessage {
    EXPORTED_CSV,
    EXPORTED_JSON,
    EXPORT_FAILED,

    /** No app on the phone can create a file (the system file picker is missing or disabled). */
    EXPORT_UNAVAILABLE,
    DEMO_FAILED,
    RESET_FAILED,
    SAVE_FAILED,
    SETTINGS_UNAVAILABLE,
}

/** The ranges the pickers allow. The ViewModel clamps to them as well, whatever the UI sends. */
object SettingsLimits {
    const val NEED_MIN_MINUTES = 6 * 60
    const val NEED_MAX_MINUTES = 11 * 60
    const val NEED_STEP_MINUTES = 5

    const val STREAK_MIN_MINUTES = 2 * 60
    const val STREAK_MAX_MINUTES = 10 * 60
    const val STREAK_STEP_MINUTES = 30

    const val DEBT_LEVEL_MIN_MINUTES = 30
    const val DEBT_LEVEL_MAX_MINUTES = 10 * 60
    const val DEBT_LEVEL_STEP_MINUTES = 30

    const val WAKE_STEP_MINUTES = 5
}

/**
 * Every user action on the settings screen. Defaults do nothing, so previews and screenshot tests can pass
 * `object : SettingsActions {}`.
 */
interface SettingsActions {
    fun onBack() {}
    fun onSleepNeedChange(minutes: Int) {}
    fun onUseSuggestedNeed() {}
    fun onTypicalWakeChange(time: LocalTime) {}
    fun onStreakThresholdChange(minutes: Int) {}
    fun onDebtLevelsChange(lowMaxMinutes: Int, highMinMinutes: Int) {}
    fun onSourceSelected(source: SourceSelection) {}
    fun onDemoModeChange(enabled: Boolean) {}
    fun onOpenHealthConnect() {}
    fun onSyncNow() {}
    fun onExport(format: ExportFormat) {}
    fun onOpenPrivacyPolicy() {}
    fun onMorningReviewChange(enabled: Boolean) {}
    fun onOpenHabits() {}
    fun onOpenNotificationSettings() {}
    fun onAllowNotifications() {}
    fun onClockFormatChange(format: ClockFormat) {}
    fun onResetAll() {}
    fun onMessageShown() {}
}

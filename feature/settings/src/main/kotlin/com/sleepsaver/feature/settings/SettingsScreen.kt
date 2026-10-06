package com.sleepsaver.feature.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.domain.model.SourceSelection
import java.time.LocalTime

/**
 * Settings with its ViewModel. Navigation callbacks come from the nav graph; the export file pickers, the
 * notification permission and the system settings page are launched here.
 */
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHabits: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onResetDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Back from Health Connect or the system notification page: show what changed there.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshSystemStatus()
        onPauseOrDispose { }
    }

    val currentOnResetDone by rememberUpdatedState(onResetDone)
    LaunchedEffect(state.resetDone) {
        if (state.resetDone) currentOnResetDone()
    }

    val exportLaunchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            if (uri != null) {
                val resolver = context.applicationContext.contentResolver
                viewModel.export(
                    format = format,
                    open = { resolver.openOutputStream(uri) },
                    discard = { DocumentsContract.deleteDocument(resolver, uri) },
                )
            }
        }
    }
    val exportDate = state.now.atZone(state.zone).toLocalDate().toString()
    val csvName = stringResource(R.string.settings_export_file_csv, exportDate)
    val jsonName = stringResource(R.string.settings_export_file_json, exportDate)

    // Taps that leave the app are dropped unless the screen is resumed, so a double tap opens one page.
    val lifecycleOwner = LocalLifecycleOwner.current
    val isResumed = { lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }

    val openNotificationSettings = {
        if (!context.openAppNotificationSettings()) viewModel.showMessage(SettingsMessage.SETTINGS_UNAVAILABLE)
    }
    // Whether Android would have explained the request before it was made. If it wouldn't before *and* after a
    // denial, the system never showed its dialog (denied for good earlier): only the system page can help now.
    // A fresh "no" from the user is respected and doesn't bounce them to settings.
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(true) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshSystemStatus()
        if (!granted && !rationaleBeforeRequest && !context.shouldExplainNotificationPermission()) {
            openNotificationSettings()
        }
    }

    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnOpenHabits by rememberUpdatedState(onOpenHabits)
    val currentOnOpenHealthConnect by rememberUpdatedState(onOpenHealthConnectSetup)
    val currentOnOpenPrivacyPolicy by rememberUpdatedState(onOpenPrivacyPolicy)
    val currentCsvName by rememberUpdatedState(csvName)
    val currentJsonName by rememberUpdatedState(jsonName)
    val currentOpenNotificationSettings by rememberUpdatedState(openNotificationSettings)
    val currentIsResumed by rememberUpdatedState(isResumed)

    val actions = remember(viewModel) {
        object : SettingsActions {
            override fun onBack() = currentOnBack()
            override fun onSleepNeedChange(minutes: Int) = viewModel.setSleepNeed(minutes)
            override fun onUseSuggestedNeed() = viewModel.useSuggestedNeed()
            override fun onTypicalWakeChange(time: LocalTime) = viewModel.setTypicalWake(time)
            override fun onStreakThresholdChange(minutes: Int) = viewModel.setStreakThreshold(minutes)
            override fun onDebtLevelsChange(lowMaxMinutes: Int, highMinMinutes: Int) {
                viewModel.setDebtLevels(lowMaxMinutes, highMinMinutes)
            }
            override fun onSourceSelected(source: SourceSelection) = viewModel.selectSource(source)
            override fun onDemoModeChange(enabled: Boolean) = viewModel.setDemoMode(enabled)
            override fun onOpenHealthConnect() = currentOnOpenHealthConnect()
            override fun onSyncNow() = viewModel.syncNow()
            override fun onExport(format: ExportFormat) {
                if (!currentIsResumed()) return
                val name = if (format == ExportFormat.CSV) currentCsvName else currentJsonName
                try {
                    exportLaunchers.getValue(format).launch(name)
                } catch (_: ActivityNotFoundException) {
                    viewModel.showMessage(SettingsMessage.EXPORT_UNAVAILABLE)
                }
            }
            override fun onOpenPrivacyPolicy() = currentOnOpenPrivacyPolicy()
            override fun onMorningReviewChange(enabled: Boolean) = viewModel.setMorningReviewEnabled(enabled)
            override fun onOpenHabits() = currentOnOpenHabits()
            override fun onOpenNotificationSettings() {
                if (currentIsResumed()) currentOpenNotificationSettings()
            }
            override fun onAllowNotifications() {
                if (!currentIsResumed()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    rationaleBeforeRequest = context.shouldExplainNotificationPermission()
                    try {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } catch (_: ActivityNotFoundException) {
                        currentOpenNotificationSettings()
                    }
                } else {
                    currentOpenNotificationSettings()
                }
            }
            override fun onClockFormatChange(format: ClockFormat) = viewModel.setClockFormat(format)
            override fun onResetAll() = viewModel.resetAll()
            override fun onMessageShown() = viewModel.messageShown()
        }
    }

    SettingsContent(state = state, actions = actions, modifier = modifier)
}

/** Opens the app's page in system notification settings (or its app-info page). False when neither exists. */
private fun Context.openAppNotificationSettings(): Boolean {
    val candidates = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
    for (intent in candidates) {
        if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next one.
        } catch (_: SecurityException) {
            // Try the next one.
        }
    }
    return false
}

/** Android 13+: whether the system would show its "why" hint for the notification permission right now. */
private fun Context.shouldExplainNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

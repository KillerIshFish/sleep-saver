package com.sleepsaver.core.data.healthconnect

import android.content.Intent
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection

/**
 * Garmin is the source (alone or among all) and demo mode is off: "Sync now" should open Garmin Connect first,
 * because new nights only reach Health Connect after the watch syncs to it. Callers sync again on resume.
 */
fun AppSettings.syncOpensGarmin(): Boolean =
    !demoMode && when (val source = sleep.source) {
        SourceSelection.All -> true
        is SourceSelection.Single -> source.packageName == SleepConfig.GARMIN_CONNECT_PACKAGE
    }

/** Garmin Connect, or its store page when it isn't installed. */
fun HealthConnectGateway.garminSyncIntent(): Intent =
    runCatching { garminConnectLaunchIntent() }.getOrNull() ?: garminConnectStoreIntent()

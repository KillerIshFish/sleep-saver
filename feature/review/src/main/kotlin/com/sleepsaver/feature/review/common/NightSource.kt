package com.sleepsaver.feature.review.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.ui.format.MINUS
import com.sleepsaver.feature.review.R
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

enum class NightSourceKind {
    /** Read from Health Connect. */
    HEALTH_CONNECT,

    /** Demo mode's synthetic nights. */
    DEMO,

    /** Added by the user (or about to be). */
    MANUAL,
}

/** Where a night's data came from, for the data-source card and the "How we measure" sheet. */
data class NightSource(
    val kind: NightSourceKind,
    /** Package that wrote the night, when it came from a data source. */
    val origin: String?,
    /** The offset the night was recorded in (or the device's, for a manual night). */
    val offset: ZoneOffset,
    val isEdited: Boolean,
) {
    companion object {
        /** [at] picks the device offset for a night that has none (one about to be added). */
        fun of(night: Night?, deviceZone: ZoneId, at: Instant): NightSource {
            val offset = night?.wokeUpOffset ?: night?.fellAsleepOffset ?: night?.override?.offset
                ?: deviceZone.rules.getOffset(night?.wokeUp ?: at)
            val origin = night?.origin ?: night?.naps?.firstNotNullOfOrNull { it.origin }
            val kind = when {
                night == null || !night.isPresent || night.isManual -> NightSourceKind.MANUAL
                origin == SleepConfig.DEMO_ORIGIN -> NightSourceKind.DEMO
                else -> NightSourceKind.HEALTH_CONNECT
            }
            return NightSource(kind, origin.takeIf { kind != NightSourceKind.MANUAL }, offset, night?.isEdited == true)
        }
    }
}

/** The app that wrote [origin], as people know it ("Garmin Connect"). */
@StringRes
internal fun originLabelRes(origin: String?): Int? = when (origin) {
    null -> null
    SleepConfig.GARMIN_CONNECT_PACKAGE -> R.string.review_origin_garmin
    SleepConfig.DEMO_ORIGIN -> R.string.review_origin_demo
    "com.sec.android.app.shealth" -> R.string.review_origin_samsung
    "com.fitbit.FitbitMobile" -> R.string.review_origin_fitbit
    "com.google.android.apps.fitness" -> R.string.review_origin_google_fit
    "com.ouraring.oura" -> R.string.review_origin_oura
    "com.withings.wiscale2" -> R.string.review_origin_withings
    "fi.polar.polarflow" -> R.string.review_origin_polar
    else -> null
}

/** "Garmin Connect"; an unknown package shows as itself, a missing one as "your data source". */
@Composable
@ReadOnlyComposable
internal fun originLabel(origin: String?): String =
    originLabelRes(origin)?.let { stringResource(it) } ?: origin ?: stringResource(R.string.review_origin_unknown)

/** "GMT−4", "GMT+5:30", "GMT". */
internal fun formatGmtOffset(offset: ZoneOffset): String {
    val total = offset.totalSeconds
    if (total == 0) return "GMT"
    val minutes = abs(total) / 60
    val hours = minutes / 60
    val rest = minutes % 60
    val sign = if (total < 0) MINUS else "+"
    return if (rest == 0) "GMT$sign$hours" else "GMT$sign$hours:${rest.toString().padStart(2, '0')}"
}

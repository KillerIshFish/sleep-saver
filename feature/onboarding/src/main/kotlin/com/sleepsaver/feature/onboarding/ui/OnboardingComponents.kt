package com.sleepsaver.feature.onboarding.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.R

// ---------------------------------------------------------------- top bar

/**
 * Back arrow plus a slim segmented progress bar: one segment per step, filled up to [current] (0-based).
 * [onBack] null hides the arrow but keeps its space, so the bar doesn't jump between steps.
 */
@Composable
internal fun StepTopBar(current: Int, total: Int, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.onboarding_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        val description = stringResource(R.string.onboarding_step_of, current + 1, total)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.m)
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(total) { index ->
                val color by animateColorAsState(
                    targetValue = if (index <= current) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.cardRaised,
                    animationSpec = tween(400),
                    label = "stepSegment",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
        Spacer(Modifier.width(48.dp))
    }
}

// ---------------------------------------------------------------- headings

/** A step's title and one explanatory paragraph. */
@Composable
internal fun StepHeading(title: String, body: String?, modifier: Modifier = Modifier, centered: Boolean = false) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = align,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = align,
            )
        }
    }
}

// ---------------------------------------------------------------- status rows

internal enum class RowStatus {
    /** Done / allowed: tide check. */
    OK,

    /** Required and missing: needs the user. */
    NEEDS_ACTION,

    /** Optional and off. */
    OFF,

    /** Not possible on this phone. */
    UNAVAILABLE,

    CHECKING,
}

/**
 * One line of the connection checklist: a status icon, [title], a [detail] sentence and an optional fix
 * ([actionLabel]) at the end.
 */
@Composable
internal fun StatusRow(
    title: String,
    detail: String?,
    status: RowStatus,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusIcon(status, Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (status == RowStatus.UNAVAILABLE) SleepSaverTheme.colors.textSecondary else MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            // Shifted by the button's inner padding so the label ends at the content edge, like SectionHeader.
            TextLinkButton(
                actionLabel,
                onClick = onAction,
                modifier = Modifier
                    .padding(start = Spacing.s)
                    .offset(x = 12.dp),
            )
        }
    }
}

@Composable
internal fun StatusIcon(status: RowStatus, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        RowStatus.OK -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        RowStatus.NEEDS_ACTION -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.onSurface
        RowStatus.OFF -> Icons.Rounded.RadioButtonUnchecked to SleepSaverTheme.colors.textSecondary
        RowStatus.UNAVAILABLE -> Icons.Rounded.RemoveCircleOutline to SleepSaverTheme.colors.textTertiary
        RowStatus.CHECKING -> null to MaterialTheme.colorScheme.primary
    }
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = tint,
                trackColor = SleepSaverTheme.colors.cardRaised,
                strokeWidth = 2.dp,
                strokeCap = StrokeCap.Round,
            )
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

/** "1  Open Garmin Connect and tap More." */
@Composable
internal fun NumberedStep(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = Spacing.m, top = 2.dp)
                .weight(1f),
        )
    }
}

// ---------------------------------------------------------------- watch → Health Connect → app

/** Three nodes joined by dotted lines: where the sleep comes from and how it gets here. */
@Composable
internal fun ConnectFlowGraphic(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        FlowNode(stringResource(R.string.onboarding_flow_watch)) { NodeIcon(Icons.Rounded.Watch) }
        FlowLink(Modifier.weight(1f))
        FlowNode(stringResource(R.string.onboarding_flow_health_connect)) { NodeIcon(Icons.Rounded.HealthAndSafety) }
        FlowLink(Modifier.weight(1f))
        FlowNode(stringResource(R.string.onboarding_flow_app)) { CrescentMark(size = 44.dp, glow = false) }
    }
}

private val NodeSize = 64.dp

@Composable
private fun FlowNode(label: String, content: @Composable () -> Unit) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(NodeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

@Composable
private fun NodeIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
}

@Composable
private fun FlowLink(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    // The nodes are wider than their circles: reach a little into that margin.
    Canvas(modifier.height(NodeSize)) {
        val y = size.height / 2
        val dot = 3.dp.toPx()
        drawLine(
            color = color,
            start = Offset(-8.dp.toPx(), y),
            end = Offset(size.width + 8.dp.toPx(), y),
            strokeWidth = dot,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx())),
        )
    }
}

// ---------------------------------------------------------------- platform helpers

/**
 * Starts [intent], returning false (instead of crashing) when nothing can open it. From an Activity it opens in
 * our task, so back returns here; otherwise it needs a task of its own.
 */
internal fun Context.startSafely(intent: Intent?): Boolean {
    if (intent == null) return false
    val launch = if (findActivity() != null) intent else Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        (findActivity() ?: this).startActivity(launch)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        // Some OEM builds throw this for malformed market:// links.
        false
    }
}

/**
 * Lets one external launch (permission dialog, store page, another app) through per [windowMillis]. Our screen
 * stays resumed until the other activity is actually up, so a quick double tap would otherwise open it twice.
 */
internal class LaunchGuard(private val windowMillis: Long = 1_000, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var last = Long.MIN_VALUE

    fun tryAcquire(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < windowMillis) return false
        last = now
        return true
    }
}

/** Starts the first of [intents] that something can open; false when none can. */
internal fun Context.startFirst(intents: List<Intent>): Boolean = intents.any { startSafely(it) }

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}

/** A readable name for the app that wrote sleep: "Garmin Connect", "Fitbit", or the installed app's label. */
@Composable
internal fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) { appLabel(packageName, context.packageManager) }
}

internal fun appLabel(packageName: String, packageManager: PackageManager?): String {
    KNOWN_APPS[packageName]?.let { return it }
    val installed = try {
        packageManager?.let { it.getApplicationLabel(it.getApplicationInfo(packageName, 0)).toString() }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }
    return installed?.takeIf { it.isNotBlank() && it != packageName } ?: fallbackLabel(packageName)
}

/** "com.example.sleepcoach" → "Sleepcoach". */
internal fun fallbackLabel(packageName: String): String {
    val generic = setOf("com", "org", "net", "io", "app", "apps", "android", "mobile", "co", "www")
    val segment = packageName.split('.').lastOrNull { it.isNotBlank() && it.lowercase() !in generic } ?: packageName
    return segment.replaceFirstChar { it.uppercase() }
}

/** Apps commonly writing sleep to Health Connect; their labels need no package visibility. */
private val KNOWN_APPS = mapOf(
    SleepConfig.GARMIN_CONNECT_PACKAGE to "Garmin Connect",
    "com.fitbit.FitbitMobile" to "Fitbit",
    "com.sec.android.app.shealth" to "Samsung Health",
    "com.google.android.apps.fitness" to "Google Fit",
    "com.ouraring.oura" to "Oura",
    "com.withings.wiscale2" to "Withings",
    "fi.polar.polarflow" to "Polar Flow",
    "com.whoop.android" to "WHOOP",
    "com.xiaomi.wearable" to "Mi Fitness",
    "com.huami.watch.hmwatchmanager" to "Zepp",
    "com.urbandroid.sleep" to "Sleep as Android",
    "com.google.android.apps.healthdata" to "Health Connect",
)

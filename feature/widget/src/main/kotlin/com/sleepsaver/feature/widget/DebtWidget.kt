package com.sleepsaver.feature.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.theme.DarkExtendedColors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebtWidgetEntryPoint {
    fun insightsRepository(): InsightsRepository
    fun settingsRepository(): SettingsRepository
}

/** Home-screen widget: sleep debt (big), its status colour, and the current or next energy phase. Tap opens the app. */
class DebtWidget : GlanceAppWidget() {

    /** A narrow or short 2x2 (dense grid, landscape) gets the compact layout instead of clipped lines. */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(CompactSize, RoomySize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, DebtWidgetEntryPoint::class.java)
        val models = debtWidgetModels(entry.insightsRepository().state, entry.settingsRepository().settings)
        // A cold process starts with insights still loading; wait briefly rather than flash the "connect" message.
        val initial = withTimeoutOrNull(5.seconds) { models.first() } ?: DebtWidgetModel.Disconnected
        provideContent {
            val model by models.collectAsState(initial)
            DebtWidgetContent(model)
        }
    }
}

class DebtWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DebtWidget()
}

private val TextPrimary = ColorProvider(R.color.widget_text_primary)
private val TextSecondary = ColorProvider(R.color.widget_text_secondary)

/** The smallest the widget gets (the 2x2 minimum; it can't be resized below that): status, debt and its label. */
private val CompactSize = DpSize(110.dp, 110.dp)

/** From here on the debt is full size and the phase line fits below it (with some room for larger fonts). */
private val RoomySize = DpSize(130.dp, 160.dp)

@Composable
internal fun DebtWidgetContent(model: DebtWidgetModel) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.debt_widget_background))
            .padding(14.dp)
            .clickable(actionStartActivity(openAppIntent(context))),
    ) {
        when (model) {
            DebtWidgetModel.Disconnected -> Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = context.getString(R.string.widget_connect),
                    style = TextStyle(color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
            is DebtWidgetModel.Debt -> DebtContent(model)
        }
    }
}

@Composable
private fun ColumnScope.DebtContent(model: DebtWidgetModel.Debt) {
    val context = LocalContext.current
    val roomy = LocalSize.current.let { it.width >= RoomySize.width && it.height >= RoomySize.height }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.widget_status_dot),
            contentDescription = null,
            modifier = GlanceModifier.size(8.dp),
            colorFilter = ColorFilter.tint(ColorProvider(model.status.color())),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = context.getString(model.status.label()),
            style = TextStyle(color = TextSecondary, fontSize = 12.sp),
        )
    }
    Text(
        text = formatDebt(model.debtTenths),
        style = TextStyle(color = TextPrimary, fontSize = if (roomy) 44.sp else 36.sp),
        maxLines = 1,
    )
    Text(
        text = context.getString(R.string.widget_debt_unit),
        style = TextStyle(color = TextSecondary, fontSize = 12.sp),
        maxLines = 1,
    )
    Spacer(GlanceModifier.defaultWeight())
    model.phase?.takeIf { roomy }?.let { phase ->
        val name = context.getString(phase.type.label())
        val time = formatTime(context, phase.time, model.clockFormat)
        val format = if (phase.isCurrent) R.string.widget_phase_until else R.string.widget_phase_from
        Text(
            text = context.getString(format, name, time),
            style = TextStyle(color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
    }
}

private fun openAppIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("sleepsaver://home")).setPackage(context.packageName)

private fun formatDebt(tenths: Int): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(tenths / 10.0)

private fun formatTime(context: Context, time: LocalTime, clockFormat: ClockFormat): String {
    val is24Hour = when (clockFormat) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    return time.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a"))
}

private fun DebtStatus.color(): Color = when (this) {
    DebtStatus.LOW -> DarkExtendedColors.debtLow
    DebtStatus.MODERATE -> DarkExtendedColors.debtModerate
    DebtStatus.HIGH -> DarkExtendedColors.debtHigh
}

@StringRes
private fun DebtStatus.label(): Int = when (this) {
    DebtStatus.LOW -> R.string.widget_status_low
    DebtStatus.MODERATE -> R.string.widget_status_moderate
    DebtStatus.HIGH -> R.string.widget_status_high
}

@StringRes
private fun PhaseType.label(): Int = when (this) {
    PhaseType.GROGGINESS -> R.string.widget_phase_grogginess
    PhaseType.MORNING_PEAK -> R.string.widget_phase_morning_peak
    PhaseType.AFTERNOON_DIP -> R.string.widget_phase_afternoon_dip
    PhaseType.EVENING_PEAK -> R.string.widget_phase_evening_peak
    PhaseType.WIND_DOWN -> R.string.widget_phase_wind_down
    PhaseType.MELATONIN_WINDOW -> R.string.widget_phase_melatonin_window
}

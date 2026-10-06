package com.sleepsaver.feature.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.PhaseType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime
import kotlin.math.roundToInt

/** Lays the widget out at each of its responsive breakpoints (the smallest size each layout is shown at), with real text metrics. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class DebtWidgetLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val padding = 14.dp

    /** The longest content the widget shows. */
    private val longest = DebtWidgetModel.Debt(
        debtTenths = 128,
        status = DebtStatus.MODERATE,
        phase = PhaseLine(PhaseType.MELATONIN_WINDOW, isCurrent = true, time = LocalTime.of(23, 15)),
        clockFormat = ClockFormat.H12,
    )

    @Test
    fun `no line is clipped or cut short at either breakpoint`() {
        for ((size, roomy) in listOf(DpSize(110.dp, 110.dp) to false, DpSize(130.dp, 160.dp) to true)) {
            val root = render(longest, size)
            val texts = root.visibleTexts()
            texts.forEach { text ->
                val bounds = text.boundsIn(root)
                assertWithMessage("'${text.text}' at $size: $bounds in ${root.width}x${root.height}")
                    .that(bounds.bottom).isAtMost(root.height - px(padding))
                assertWithMessage("'${text.text}' at $size is squeezed").that(text.isSqueezed()).isFalse()
                assertWithMessage("'${text.text}' at $size is ellipsized").that(text.isEllipsized()).isFalse()
            }
            assertThat(texts.map { it.text.toString() }).containsAtLeast(
                context.getString(R.string.widget_status_moderate), "12.8", context.getString(R.string.widget_debt_unit),
            )
            val phaseShown = texts.any { it.text.startsWith(context.getString(R.string.widget_phase_melatonin_window)) }
            assertWithMessage("phase line at $size").that(phaseShown).isEqualTo(roomy)
        }
    }

    @Test
    fun `the connect message fits the smallest size`() {
        val root = render(DebtWidgetModel.Disconnected, DpSize(110.dp, 110.dp))

        val text = root.visibleTexts().single()
        assertThat(text.text.toString()).isEqualTo(context.getString(R.string.widget_connect))
        assertThat(text.boundsIn(root).bottom).isAtMost(root.height - px(padding))
    }

    private fun render(model: DebtWidgetModel, size: DpSize): View {
        val result = runBlocking { GlanceRemoteViews().compose(context, size) { DebtWidgetContent(model) } }
        val view = result.remoteViews.apply(context, FrameLayout(context))
        val width = px(size.width)
        val height = px(size.height)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun px(dp: Dp): Int = (dp.value * context.resources.displayMetrics.density).roundToInt()

    private fun View.visibleTexts(): List<TextView> = when {
        visibility != View.VISIBLE -> emptyList()
        this is TextView -> if (text.isNullOrEmpty()) emptyList() else listOf(this)
        this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).visibleTexts() }
        else -> emptyList()
    }

    /** Laid out shorter than its text (a column that ran out of room squeezes its last children). */
    private fun TextView.isSqueezed(): Boolean = layout.height > height - compoundPaddingTop - compoundPaddingBottom

    private fun TextView.isEllipsized(): Boolean = layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } ?: false

    private fun View.boundsIn(root: View): Rect {
        var x = left
        var y = top
        var parent = parent
        while (parent is View && parent !== root) {
            x += parent.left
            y += parent.top
            parent = parent.parent
        }
        return Rect(x, y, x + width, y + height)
    }
}

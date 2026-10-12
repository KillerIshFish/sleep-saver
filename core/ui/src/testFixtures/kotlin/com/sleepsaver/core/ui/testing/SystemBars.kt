package com.sleepsaver.core.ui.testing

import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.fail
import kotlin.math.roundToInt

/**
 * System bars for content under test. Robolectric's window has none, so a screen that runs content under the
 * navigation bar looks fine in every other test. The app draws edge to edge, so on a phone each screen must keep its
 * content and buttons out of these bars itself: [ScreenshotTest.captureScreen] with `systemBars = true` applies them,
 * then [assertClearOfSystemBars] checks the screen. [ScreenshotTest.captureClearOfSystemBars] does both:
 *
 * ```
 * @Test fun nightResetBars() = captureClearOfSystemBars("bars_night_reset") { NightResetContent(state, actions) }
 * ```
 */
object SystemBars {
    val STATUS = 24.dp

    /** Three-button navigation, the tallest navigation bar (gesture navigation's is about 24 dp). */
    val NAVIGATION = 48.dp
}

/**
 * Hands this composition's view ([androidx.compose.ui.platform.LocalView]) a [status] bar at the top and a
 * [navigation] bar at the bottom, as Android does for an edge-to-edge activity. Call it once the content is shown;
 * the bars stay for the rest of the test.
 */
fun View.simulateSystemBars(status: Dp = SystemBars.STATUS, navigation: Dp = SystemBars.NAVIGATION) {
    val density = resources.displayMetrics.density
    fun px(dp: Dp) = (dp.value * density).roundToInt()
    val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(status), 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(navigation)))
        .setVisible(bars, true)
        .build()
    ViewCompat.dispatchApplyWindowInsets(this, insets)
}

/**
 * A scroll state that stays at the end, also when the end moves: the simulated bars arrive after the first layout
 * and shrink the viewport, which leaves a plain `rememberScrollState(Int.MAX_VALUE)` short of the end.
 */
@Composable
fun rememberScrolledToEnd(): ScrollState {
    val state = rememberScrollState()
    LaunchedEffect(state) { snapshotFlow { state.maxValue }.collect { state.scrollTo(it) } }
    return state
}

/** Translucent bands where the bars are, drawn over the content so a screenshot shows what would sit under them. */
@Composable
internal fun SystemBarBands(status: Dp = SystemBars.STATUS, navigation: Dp = SystemBars.NAVIGATION) {
    val band = Color.White.copy(alpha = 0.25f)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(status).background(band))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(navigation).background(band))
    }
}

private val shownToUser = SemanticsMatcher("shows text or takes a tap") { node ->
    val config = node.config
    SemanticsProperties.Text in config || SemanticsProperties.ContentDescription in config || SemanticsActions.OnClick in config
}

/**
 * Nothing that shows text or takes a tap is drawn under the [status] bar or the [navigation] bar. Bounds are clipped
 * by the scroll areas, so content scrolled out of sight doesn't count, but content scrolling under a bar does.
 */
fun SemanticsNodeInteractionsProvider.assertClearOfSystemBars(status: Dp = SystemBars.STATUS, navigation: Dp = SystemBars.NAVIGATION) {
    val root = onAllNodes(isRoot()).fetchSemanticsNodes().first()
    val density = root.layoutInfo.density.density
    val top = status.value * density
    val bottom = root.size.height - navigation.value * density
    // Half a pixel of slack for rounding.
    val under = onAllNodes(shownToUser, useUnmergedTree = true).fetchSemanticsNodes()
        .filter { node -> node.root == root.root }
        .filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0f && bounds.height > 0f && (bounds.top < top - 0.5f || bounds.bottom > bottom + 0.5f)
        }
    if (under.isNotEmpty()) {
        fail(
            "Under a system bar (top ${top}px, bottom ${bottom}px of ${root.size.height}px):\n" +
                under.joinToString("\n") { node ->
                    val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: "a tappable node"
                    "  \"$label\" at ${node.boundsInRoot}"
                },
        )
    }
}

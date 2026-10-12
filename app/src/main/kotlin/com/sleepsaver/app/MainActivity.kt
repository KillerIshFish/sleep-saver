package com.sleepsaver.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sleepsaver.app.navigation.SleepSaverApp
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's one window. Two night-time redirects (SPEC_V2 §5.6):
 *  - while an alarm is sounding, being in the app means seeing the ringing screen: whenever this activity is resumed
 *    and a ring sounds (also when one starts while it is open: Android shows a full-screen intent only as a heads-up
 *    while the phone is in use, and none at all with notifications blocked), it starts `RingingActivity` by explicit
 *    component. Only ever from a resumed window, so the platform allows it as an ordinary foreground start
 *    (BAL_ALLOW_VISIBLE_WINDOW). When the full-screen intent got there first (the screen woke onto this activity), the
 *    single-instance screen just gets the intent again (START_DELIVERED_TO_TOP);
 *  - a cold launch from the launcher during the night state (after Drop anchor) opens the no-clock Night screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var nightController: NightController

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        // Dark-only app: always light status/navigation bar icons, whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Decided once, on a cold launch: not after rotation, and not when a link says where to go.
        val startInNight = savedInstanceState == null && !intent.isDeepLink() && nightController.state.value.night != null
        showRingingWhileResumed()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            SleepSaverTheme {
                when (val s = state) {
                    MainUiState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    is MainUiState.Ready -> {
                        val use24h = when (s.clockFormat) {
                            ClockFormat.SYSTEM -> DateFormat.is24HourFormat(this)
                            ClockFormat.H12 -> false
                            ClockFormat.H24 -> true
                        }
                        CompositionLocalProvider(LocalUse24HourClock provides use24h) {
                            SleepSaverApp(
                                startWithOnboarding = !s.onboardingDone,
                                pendingReview = s.pendingReview,
                                onReviewShown = viewModel::onReviewShown,
                                startInNight = startInNight,
                            )
                        }
                    }
                }
            }
        }
    }

    /** While resumed: each time a ring is sounding (null while snoozed), the ringing screen comes to the front. */
    private fun showRingingWhileResumed() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                nightController.state
                    .map { it.ringing != null }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { showRinging() }
            }
        }
    }

    private fun showRinging() {
        val ringing = Intent()
            .setClassName(packageName, AlarmIntents.RINGING_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(ringing)
        } catch (e: RuntimeException) { // ActivityNotFoundException, SecurityException: never crash the app over it
            Log.w(TAG, "Couldn't open the ringing screen", e)
        }
    }

    private fun Intent?.isDeepLink(): Boolean = this?.data?.scheme == DEEP_LINK_SCHEME

    private companion object {
        const val TAG = "SleepSaver"
        const val DEEP_LINK_SCHEME = "sleepsaver"
    }
}

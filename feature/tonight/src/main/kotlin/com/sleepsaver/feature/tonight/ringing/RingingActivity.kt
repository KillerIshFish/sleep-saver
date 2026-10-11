package com.sleepsaver.feature.tonight.ringing

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.platform.tryStartActivity
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate

/**
 * The full-screen alarm (AlarmIntents.RINGING_ACTIVITY). core:alarm starts it with an explicit component from the
 * ring's full-screen notification; it shows over the lock screen, turns the screen on and works before the first
 * unlock (see the manifest). Its state lives in the alarm service (NightController), so it survives rotation and a
 * re-launch; it closes itself when nothing rings any more. It never dismisses the keyguard by itself: only "Open
 * morning review" asks to.
 */
@AndroidEntryPoint
class RingingActivity : ComponentActivity() {

    private val viewModel: RingingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only, like the rest of the app.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state) { if (state == RingingUiState.Gone) finish() }
            val use24h = (state as? RingingUiState.Sounding)?.ringing?.use24h ?: false
            val actions = remember(viewModel) {
                RingingActions(
                    onSnooze = viewModel::onSnooze,
                    onStop = viewModel::onStop,
                    onFeel = viewModel::onFeel,
                    onDone = ::finish,
                    onOpenReview = ::openMorningReview,
                )
            }
            SleepSaverTheme {
                CompositionLocalProvider(LocalUse24HourClock provides use24h) {
                    RingingContent(state, actions)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    /** Opens the morning review, asking the user to unlock first when the keyguard is up. */
    private fun openMorningReview(wakeDate: LocalDate) {
        val review = Intent(Intent.ACTION_VIEW, Uri.parse(AlarmIntents.reviewLink(wakeDate)))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open(review)
                },
            )
        } else {
            open(review)
        }
    }

    private fun open(intent: Intent) {
        if (tryStartActivity(intent)) finish()
    }
}

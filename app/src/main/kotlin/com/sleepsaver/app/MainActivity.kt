package com.sleepsaver.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.app.navigation.SleepSaverApp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            SleepSaverTheme {
                when (val s = state) {
                    MainUiState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    is MainUiState.Ready -> SleepSaverApp(
                        startWithOnboarding = !s.onboardingDone,
                        pendingReview = s.pendingReview,
                        onReviewShown = viewModel::onReviewShown,
                    )
                }
            }
        }
    }
}

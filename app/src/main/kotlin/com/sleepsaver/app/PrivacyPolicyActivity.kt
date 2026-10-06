package com.sleepsaver.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * Privacy policy. Health Connect opens it from the permission screen
 * (ACTION_SHOW_PERMISSIONS_RATIONALE, and VIEW_PERMISSION_USAGE on Android 14+ via the activity-alias);
 * without it the permission request fails silently. Also reachable from Settings and onboarding.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepSaverTheme {
                PrivacyPolicy(onBack = ::finish)
            }
        }
    }
}

private val SECTIONS = listOf(
    R.string.privacy_what_title to R.string.privacy_what_body,
    R.string.privacy_where_title to R.string.privacy_where_body,
    R.string.privacy_write_title to R.string.privacy_write_body,
    R.string.privacy_control_title to R.string.privacy_control_body,
    R.string.privacy_medical_title to R.string.privacy_medical_body,
)

@Composable
private fun PrivacyPolicy(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.privacy_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SECTIONS.forEach { (title, body) -> PolicySection(title, body) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PolicySection(@StringRes title: Int, @StringRes body: Int) {
    Spacer(Modifier.height(16.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

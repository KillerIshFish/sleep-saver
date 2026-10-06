package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * A screen's big title with an optional grey subtitle line ("My progress" / "Sep 22 – Oct 5") and trailing
 * [actions] (icon buttons) aligned with the title. Adds no outer padding.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The one-line "wellness tool, not medical advice" note. Put it at the bottom of screens that show advice. */
@Composable
fun WellnessDisclaimer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.ss_wellness_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun ScreenHeaderPreview() = SleepSaverTheme {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenHeader("My progress", subtitle = "Sep 22 – Oct 5") {
            IconButton(onClick = {}) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = SleepSaverTheme.colors.textSecondary)
            }
        }
        WellnessDisclaimer()
    }
}

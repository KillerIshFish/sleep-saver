package com.sleepsaver.feature.winddown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.theme.Spacing

/**
 * The frame of every Night steps screen: the app background, a back (or close) button above [content], and the
 * safe area on every side. The background runs edge to edge, but the content (scrolling bodies, a running tool's End,
 * Night reset's "Back to night") stays clear of the status bar, the navigation bar (gesture or three-button) and the
 * keyboard, also while it scrolls.
 */
@Composable
internal fun WinddownSurface(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    backDescription: String = stringResource(R.string.winddown_back),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopIconButton(
                icon = backIcon,
                description = backDescription,
                onClick = onBack,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            content()
        }
    }
}

/** A 48 dp icon button for the top-left corner (back, close). */
@Composable
internal fun TopIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** The breathing orb while a screen's data loads. */
@Composable
internal fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingOrb(label = stringResource(R.string.winddown_loading))
    }
}

/** "Night steps aren't available" with a way back, when the data couldn't be read. */
@Composable
internal fun UnavailableBody(onBack: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.winddown_unavailable_title),
        body = stringResource(R.string.winddown_unavailable_body),
        primaryActionLabel = stringResource(R.string.winddown_go_back),
        onPrimaryAction = onBack,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
    )
}

package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

private val ButtonHeight = 56.dp
private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** The one main action on a screen: a tide pill. Fill the width with `Modifier.fillMaxWidth()`. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = SleepSaverTheme.colors.cardRaised,
            disabledContentColor = SleepSaverTheme.colors.textTertiary,
        ),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, icon)
    }
}

/** A quieter alternative next to or below a [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = SleepSaverTheme.colors.cardRaised,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = SleepSaverTheme.colors.card,
            disabledContentColor = SleepSaverTheme.colors.textTertiary,
        ),
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text, icon)
    }
}

/** An inline text action ("See all", "Edit", "Not now"). Accent-coloured by default. */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = SleepSaverTheme.colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun ButtonsPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Sync now", onClick = {}, icon = Icons.Rounded.Sync, modifier = Modifier.fillMaxWidth())
        SecondaryButton("Add it by hand", onClick = {}, modifier = Modifier.fillMaxWidth())
        TextLinkButton("Not now", onClick = {})
    }
}

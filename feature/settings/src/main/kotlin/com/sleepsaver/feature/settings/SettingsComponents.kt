package com.sleepsaver.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionHeader
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** Horizontal padding of a row inside a settings card (the card itself has none, so ripples reach its edges). */
private val RowPadding = 20.dp

/** Where a row's text starts: row padding + icon circle + gap. Dividers start here. */
private val RowTextStart = RowPadding + 40.dp + 14.dp

/** A titled group: a [SectionHeader] above a card whose rows run edge to edge. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.padding(start = Spacing.xs, bottom = Spacing.s))
        SleepSaverCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = Spacing.s),
            content = content,
        )
    }
}

/** The hairline between two rows, aligned with the rows' text. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = RowTextStart, end = RowPadding),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One settings row built on [IconListItem]. Tappable edge to edge when [onClick] is set; dimmed and inert
 * when not [enabled]. A [value] ("8h", "7:00 AM") sits at the end with a chevron; with very large text it
 * moves under the title instead, so the title and description keep the row's width.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    value: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val stackValue = value != null && LocalDensity.current.fontScale >= StackedValueFontScale
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier,
            )
            .padding(horizontal = RowPadding, vertical = Spacing.xs)
            .alpha(if (enabled) 1f else DisabledAlpha),
    ) {
        if (stackValue) {
            StackedValueItem(icon = icon, title = title, value = value.orEmpty(), supporting = supporting, tint = tint)
        } else {
            IconListItem(
                icon = icon,
                title = title,
                supporting = supporting,
                tint = tint,
                trailing = trailing ?: value?.let { { ValueChevron(it) } },
            )
        }
    }
}

/** [IconListItem]'s layout with the value as a second line under the title (for large text). */
@Composable
private fun StackedValueItem(icon: ImageVector, title: String, value: String, supporting: String?, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        ValueChevron()
    }
}

/** From this font scale on, a row's value moves under its title. */
private const val StackedValueFontScale = 1.5f

/** A row with a switch; the whole row toggles it. */
@Composable
internal fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = RowPadding, vertical = Spacing.xs)
            .alpha(if (enabled) 1f else DisabledAlpha),
    ) {
        IconListItem(
            icon = icon,
            title = title,
            supporting = supporting,
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = SleepSaverTheme.colors.textSecondary,
                        uncheckedTrackColor = SleepSaverTheme.colors.cardRaised,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        disabledCheckedTrackColor = MaterialTheme.colorScheme.primary,
                        disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
        )
    }
}

/** "8h ›": a row's current value and a chevron. */
@Composable
internal fun ValueChevron(value: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A small spinner for rows with work in flight. */
@Composable
internal fun RowProgress() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
        trackColor = SleepSaverTheme.colors.cardRaised,
    )
}

/**
 * A soft tinted note inside a card (the sleep-need suggestion, "notifications are off"), with an icon,
 * a title, a body and an action: beside the text when [inlineAction] (short labels like "Use"), else below it.
 */
@Composable
internal fun SettingsNote(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    inlineAction: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    // With very large text even a short action takes too much width beside the text.
    val inline = inlineAction && LocalDensity.current.fontScale < StackedValueFontScale
    val actionBelow = action != null && !inline
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = if (actionBelow) Spacing.xs else Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Top)
                    .padding(top = 1.dp)
                    .size(20.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = Spacing.m, end = Spacing.s),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (action != null && inline) action()
        }
        if (actionBelow) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action!!.invoke() }
        }
    }
}

// ---------------------------------------------------------------- Dialogs

/** Hosts a [SettingsDialogCard] in a window, at the card's own width (capped for tablets). */
@Composable
internal fun SettingsDialogWindow(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * The surface of every settings dialog: title, optional body, [content], then Cancel / confirm buttons side
 * by side. [destructive] paints the confirm button coral.
 */
@Composable
internal fun SettingsDialogCard(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    destructive: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(SleepSaverTheme.colors.card)
            // Very large text or a landscape phone can make a dialog taller than the window.
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = SleepSaverTheme.colors.textSecondary)
        }
        if (content != null) {
            Spacer(Modifier.height(Spacing.xl))
            content()
        }
        Spacer(Modifier.height(Spacing.xl))
        val confirm = @Composable { buttonModifier: Modifier ->
            if (destructive) {
                DestructiveButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled, modifier = buttonModifier)
            } else {
                PrimaryButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled, modifier = buttonModifier)
            }
        }
        if (LocalDensity.current.fontScale >= StackedValueFontScale) {
            // Side by side, very large labels would wrap: stack them, the main action on top.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                confirm(Modifier.fillMaxWidth())
                SecondaryButton(dismissLabel, onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                SecondaryButton(dismissLabel, onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.weight(1f))
                confirm(Modifier.weight(1f))
            }
        }
    }
}

/** The coral pill for "erase everything". */
@Composable
private fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = SleepSaverTheme.colors.cardRaised,
            disabledContentColor = SleepSaverTheme.colors.textTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

private const val DisabledAlpha = 0.45f

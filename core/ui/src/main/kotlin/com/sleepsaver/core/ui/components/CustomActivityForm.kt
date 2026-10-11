package com.sleepsaver.core.ui.components

import android.icu.text.BreakIterator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** Quick picks for a custom activity's emoji; any emoji can also be typed. */
internal val SuggestedEmoji = listOf("✨", "🍵", "🛁", "🕯️", "🎵", "🧩", "🎨", "🧶", "🐈", "🌿", "🙏", "🌙")

/** Labels longer than this are cut while typing (the routine list and the Night steps checklist show them whole). */
private const val DEFAULT_MAX_LABEL_LENGTH = 24

/**
 * The "Add your own" dialog body for a Night step: an emoji (typed or picked) and a label ("🍵 Herbal tea").
 * [onAdd] gets the trimmed label; Add stays disabled until there is one. Labels are capped at [maxLabelLength]
 * characters while typing. Show it inside a `Dialog`.
 */
@Composable
fun CustomActivityForm(
    onCancel: () -> Unit,
    onAdd: (label: String, emoji: String) -> Unit,
    modifier: Modifier = Modifier,
    initialLabel: String = "",
    initialEmoji: String = SuggestedEmoji.first(),
    autoFocus: Boolean = true,
    maxLabelLength: Int = DEFAULT_MAX_LABEL_LENGTH,
) {
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    val canAdd = label.isNotBlank()
    val submit = { if (canAdd) onAdd(label.trim(), emoji) }
    // The name is what people come here to type: focus it so the keyboard is ready.
    val labelFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) labelFocus.requestFocus() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = SleepSaverTheme.colors.card,
    ) {
        Column(Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl, bottom = Spacing.m)) {
            Text(
                stringResource(R.string.ss_custom_activity_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.ss_custom_activity_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                Modifier.padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = lastGrapheme(it) },
                    label = { Text(stringResource(R.string.ss_custom_activity_emoji)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.width(84.dp),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(maxLabelLength) },
                    label = { Text(stringResource(R.string.ss_custom_activity_label)) },
                    placeholder = { Text(stringResource(R.string.ss_custom_activity_label_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(labelFocus),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SuggestedEmoji.forEach { option ->
                    EmojiOption(option, selected = option == emoji, onClick = { emoji = option })
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.m),
                horizontalArrangement = Arrangement.End,
            ) {
                TextLinkButton(
                    stringResource(R.string.ss_custom_activity_cancel),
                    onClick = onCancel,
                    color = SleepSaverTheme.colors.textSecondary,
                )
                TextLinkButton(stringResource(R.string.ss_custom_activity_add), onClick = submit, enabled = canAdd)
            }
        }
    }
}

@Composable
private fun EmojiOption(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.cardRaised)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 20.sp)
    }
}

/**
 * The last user-perceived character of [text] (a whole emoji, even a multi-code-point one), so typing
 * into the emoji field replaces what was there instead of appending.
 */
internal fun lastGrapheme(text: String): String {
    if (text.isEmpty()) return ""
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(text)
    val end = boundaries.last()
    val start = boundaries.previous()
    return if (start == BreakIterator.DONE) text else text.substring(start, end)
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun CustomActivityFormPreview() = SleepSaverTheme {
    CustomActivityForm(
        onCancel = {},
        onAdd = { _, _ -> },
        modifier = Modifier.padding(16.dp),
        initialLabel = "Herbal tea",
        initialEmoji = "🍵",
        autoFocus = false,
    )
}

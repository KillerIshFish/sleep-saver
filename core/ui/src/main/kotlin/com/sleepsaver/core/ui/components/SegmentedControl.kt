package com.sleepsaver.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * A pill switch between 2–3 equal-width [options] ("Sleep times / Sleep debt / Quality").
 * The selection slides between segments.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.isNotEmpty()) { "SegmentedControl needs at least one option" }
    val inner = 4.dp
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.card)
            .padding(inner),
    ) {
        val segment = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segment * selectedIndex.coerceIn(0, options.lastIndex),
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
            label = "segmentIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(SleepSaverTheme.colors.cardRaised)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val textColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textSecondary,
                    label = "segmentText",
                )
                Box(
                    modifier = Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelectedChange(index) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun SegmentedControlPreview() = SleepSaverTheme {
    SegmentedControl(
        options = listOf("Sleep times", "Sleep debt", "Quality"),
        selectedIndex = 1,
        onSelectedChange = {},
        modifier = Modifier.padding(16.dp),
    )
}

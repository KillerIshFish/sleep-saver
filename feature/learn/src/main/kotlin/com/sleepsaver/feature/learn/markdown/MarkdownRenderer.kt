package com.sleepsaver.feature.learn.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/** Long-form reading styles derived from the app typography. */
@Immutable
internal data class MarkdownStyle(
    val body: TextStyle,
    val heading1: TextStyle,
    val heading2: TextStyle,
    val heading3: TextStyle,
    /** Colour of bold runs: brighter than [body] so they stand out on the dark background. */
    val emphasis: Color,
    /** Bullets, list numbers and the quote bar. */
    val accent: Color,
    val quote: TextStyle,
) {
    fun heading(level: Int): TextStyle = when (level) {
        1 -> heading1
        2 -> heading2
        else -> heading3
    }
}

@Composable
@ReadOnlyComposable
internal fun markdownStyle(): MarkdownStyle {
    val type = MaterialTheme.typography
    val onSurface = MaterialTheme.colorScheme.onSurface
    val body = type.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp, color = onSurface.copy(alpha = 0.88f))
    return MarkdownStyle(
        body = body,
        heading1 = type.headlineSmall.copy(color = onSurface),
        heading2 = type.titleLarge.copy(color = onSurface),
        heading3 = type.titleMedium.copy(color = onSurface),
        emphasis = onSurface,
        accent = MaterialTheme.colorScheme.primary,
        quote = body.copy(color = SleepSaverTheme.colors.textSecondary, fontStyle = FontStyle.Italic),
    )
}

/** Bold runs become semi-bold in [emphasis]; italic runs become italic. */
internal fun List<TextRun>.toAnnotatedString(emphasis: Color = Color.Unspecified): AnnotatedString =
    buildAnnotatedString {
        for (run in this@toAnnotatedString) {
            if (!run.bold && !run.italic) {
                append(run.text)
                continue
            }
            val span = SpanStyle(
                color = if (run.bold) emphasis else Color.Unspecified,
                fontWeight = if (run.bold) FontWeight.SemiBold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
            )
            withStyle(span) { append(run.text) }
        }
    }

/** Vertical gap before [block] when it follows [previous]: headings get room above and sit close to their text. */
internal fun markdownSpacingBefore(previous: MarkdownBlock?, block: MarkdownBlock): Dp = when {
    previous == null -> 0.dp
    block is MarkdownBlock.Heading -> 28.dp
    previous is MarkdownBlock.Heading -> 10.dp
    else -> 16.dp
}

/** One block, without outer spacing (see [markdownSpacingBefore]). */
@Composable
internal fun MarkdownBlockView(
    block: MarkdownBlock,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = markdownStyle(),
) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = block.text.toAnnotatedString(),
            style = style.heading(block.level),
            modifier = modifier.semantics { heading() },
        )
        is MarkdownBlock.Paragraph -> Text(block.text.toAnnotatedString(style.emphasis), modifier, style = style.body)
        is MarkdownBlock.BulletList -> MarkdownList(block.items, style, modifier) { BULLET }
        is MarkdownBlock.OrderedList -> MarkdownList(block.items, style, modifier) { index -> "${block.start + index}." }
        is MarkdownBlock.Quote -> MarkdownQuote(block, style, modifier)
    }
}

@Composable
private fun MarkdownList(
    items: List<List<TextRun>>,
    style: MarkdownStyle,
    modifier: Modifier,
    marker: (Int) -> String,
) {
    Column(modifier) {
        items.forEachIndexed { index, item ->
            Row(Modifier.padding(top = if (index == 0) 0.dp else 8.dp)) {
                Text(
                    text = marker(index),
                    style = style.body.copy(color = style.accent, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
                )
                Text(item.toAnnotatedString(style.emphasis), Modifier.weight(1f), style = style.body)
            }
        }
    }
}

@Composable
private fun MarkdownQuote(block: MarkdownBlock.Quote, style: MarkdownStyle, modifier: Modifier) {
    val inner = style.copy(body = style.quote)
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(style.accent, RoundedCornerShape(percent = 50)),
        )
        Column(Modifier.padding(start = 16.dp)) {
            block.blocks.forEachIndexed { index, child ->
                val gap = markdownSpacingBefore(block.blocks.getOrNull(index - 1), child)
                MarkdownBlockView(child, Modifier.padding(top = gap), inner)
            }
        }
    }
}

private const val BULLET = "•"

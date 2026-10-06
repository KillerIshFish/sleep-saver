package com.sleepsaver.feature.learn.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnnotatedStringTest {

    @Test
    fun `runs become spans over the right ranges`() {
        val runs = MarkdownParser.parseInline("Plain **bold** and *soft* text")

        val text = runs.toAnnotatedString(emphasis = Color.White)

        assertThat(text.text).isEqualTo("Plain bold and soft text")
        val spans = text.spanStyles.map { Triple(it.start, it.end, it.item) }
        assertThat(spans).containsExactly(
            Triple(6, 10, SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)),
            Triple(15, 19, SpanStyle(fontStyle = FontStyle.Italic)),
        ).inOrder()
    }

    @Test
    fun `plain text has no spans`() {
        assertThat(listOf(TextRun("Nothing special")).toAnnotatedString().spanStyles).isEmpty()
    }
}

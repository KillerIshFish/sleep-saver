package com.sleepsaver.feature.learn.markdown

/** A parsed markdown file: its `key: value` front matter and its body blocks. */
data class MarkdownDocument(
    val frontMatter: Map<String, String>,
    val blocks: List<MarkdownBlock>,
)

sealed interface MarkdownBlock {
    /** [level] is 1..3; deeper `####` headings are clamped to 3. */
    data class Heading(val level: Int, val text: List<TextRun>) : MarkdownBlock

    data class Paragraph(val text: List<TextRun>) : MarkdownBlock

    data class BulletList(val items: List<List<TextRun>>) : MarkdownBlock

    /** Item `i` is numbered `start + i`. */
    data class OrderedList(val start: Int, val items: List<List<TextRun>>) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
}

/** A stretch of inline text in one style. The parser never emits two adjacent runs with the same style. */
data class TextRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)

fun List<TextRun>.plainText(): String = joinToString("") { it.text }

/** The visible text of [MarkdownBlock]s, one line per paragraph, heading or list item. */
@JvmName("blocksPlainText")
fun List<MarkdownBlock>.plainText(): String = joinToString("\n") { block ->
    when (block) {
        is MarkdownBlock.Heading -> block.text.plainText()
        is MarkdownBlock.Paragraph -> block.text.plainText()
        is MarkdownBlock.BulletList -> block.items.joinToString("\n") { it.plainText() }
        is MarkdownBlock.OrderedList -> block.items.joinToString("\n") { it.plainText() }
        is MarkdownBlock.Quote -> block.blocks.plainText()
    }
}

/** Whitespace-separated tokens that contain at least one letter or digit. */
fun List<MarkdownBlock>.wordCount(): Int =
    plainText().split(WHITESPACE).count { token -> token.any(Char::isLetterOrDigit) }

private val WHITESPACE = Regex("""\s+""")

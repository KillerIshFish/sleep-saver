package com.sleepsaver.feature.learn.markdown

import kotlin.math.min

/**
 * Parses the small markdown subset the Learn articles use. It never throws: anything it doesn't recognise
 * is kept as literal text.
 *
 * - Front matter: a first line `---`, then `key: value` lines (values may be quoted), then a closing `---`.
 * - `#`, `##`, `###` headings; deeper ones are clamped to level 3.
 * - Paragraphs: consecutive lines joined with a space. A blank line or the start of another block ends them.
 * - `-` / `*` / `+` bullet lists and `1.` / `1)` ordered lists. A following line that doesn't start another
 *   block continues the current item; a blank line between two items of the same kind keeps the list going.
 * - `>` quotes: consecutive quoted lines, parsed recursively.
 * - Inline `**bold**`, `*italic*` and `***both***` (nestable), and backslash escapes such as `\*`.
 */
object MarkdownParser {

    fun parse(source: String): MarkdownDocument {
        val lines = source.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val (frontMatter, bodyStart) = frontMatter(lines)
        return MarkdownDocument(frontMatter, blocks(lines.subList(bodyStart, lines.size)))
    }

    fun parseInline(text: String): List<TextRun> = InlineParser(text).parse()

    /** Returns the front matter and the index of the first body line. */
    private fun frontMatter(lines: List<String>): Pair<Map<String, String>, Int> {
        if (lines.first().trim() != FENCE) return emptyMap<String, String>() to 0
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == FENCE }
            ?: return emptyMap<String, String>() to 0
        val values = LinkedHashMap<String, String>()
        for (line in lines.subList(1, end)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            if (key.isEmpty() || key.startsWith('#')) continue
            values[key] = line.substring(colon + 1).trim().unquoted()
        }
        return values to end + 1
    }

    private fun blocks(lines: List<String>): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val heading = HEADING.matchEntire(line)
            when {
                line.isBlank() -> i++
                heading != null -> {
                    val text = parseInline(heading.groupValues[2])
                    if (text.isNotEmpty()) out += MarkdownBlock.Heading(min(heading.groupValues[1].length, 3), text)
                    i++
                }
                QUOTE.matches(line) -> {
                    val quoted = mutableListOf<String>()
                    while (i < lines.size) {
                        quoted += QUOTE.matchEntire(lines[i])?.groupValues?.get(1) ?: break
                        i++
                    }
                    val inner = blocks(quoted)
                    if (inner.isNotEmpty()) out += MarkdownBlock.Quote(inner)
                }
                listKind(line) != null -> i = list(lines, i, out)
                else -> {
                    val parts = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) {
                        parts += lines[i].trim()
                        i++
                    }
                    out += MarkdownBlock.Paragraph(parseInline(parts.joinToString(" ")))
                }
            }
        }
        return out
    }

    /** Parses the list that starts at [start] into [out] and returns the index of the first line after it. */
    private fun list(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val kind = listKind(lines[start])!!
        val itemRegex = kind.regex
        val items = mutableListOf<StringBuilder>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val item = itemRegex.matchEntire(line)
            when {
                item != null -> {
                    items += StringBuilder(item.groupValues.last().trim())
                    i++
                }
                line.isBlank() -> {
                    val next = (i until lines.size).firstOrNull { lines[it].isNotBlank() }
                    if (next != null && itemRegex.matches(lines[next])) i = next else break
                }
                startsBlock(line) -> break
                else -> {
                    items.last().append(' ').append(line.trim())
                    i++
                }
            }
        }
        val texts = items.map { parseInline(it.toString().trim()) }
        out += when (kind) {
            ListKind.Bullet -> MarkdownBlock.BulletList(texts)
            ListKind.Ordered -> MarkdownBlock.OrderedList(ORDERED.matchEntire(lines[start])!!.groupValues[1].toInt(), texts)
        }
        return i
    }

    private fun startsBlock(line: String): Boolean =
        HEADING.matches(line) || QUOTE.matches(line) || listKind(line) != null

    private fun listKind(line: String): ListKind? = ListKind.entries.firstOrNull { it.regex.matches(line) }

    private fun String.unquoted(): String =
        if (length >= 2 && first() == last() && (first() == '"' || first() == '\'')) substring(1, length - 1) else this

    private enum class ListKind(val regex: Regex) { Bullet(BULLET), Ordered(ORDERED) }

    private const val BOM = "﻿"
    private const val FENCE = "---"
}

private val HEADING = Regex("""^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
private val BULLET = Regex("""^ {0,3}[-*+][ \t]+(.*)$""")
private val ORDERED = Regex("""^ {0,3}(\d{1,9})[.)][ \t]+(.*)$""")
private val QUOTE = Regex("""^ {0,3}>[ \t]?(.*)$""")

/**
 * Emphasis with `*` delimiters. A run of stars opens when followed by non-whitespace and closes when preceded
 * by non-whitespace; 1 star is italic, 2 bold, 3 both. While looking for a closer, nested spans are skipped
 * whole, so `*a **b** c*` and `**a *b***` resolve the way they read. Unmatched stars stay literal.
 */
private class InlineParser(private val text: String) {
    private val runs = mutableListOf<TextRun>()
    private val closers = HashMap<Triple<Int, Int, Int>, Int>()

    fun parse(): List<TextRun> {
        parseRange(0, text.length, bold = false, italic = false)
        return runs
    }

    private fun parseRange(from: Int, to: Int, bold: Boolean, italic: Boolean) {
        val buffer = StringBuilder()
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to && text[i + 1] in ESCAPABLE) {
                buffer.append(text[i + 1])
                i += 2
                continue
            }
            if (c != '*') {
                buffer.append(c)
                i++
                continue
            }
            val run = starRun(i, to)
            val width = min(run, 3)
            val contentStart = i + run
            val close = if (canOpen(i, run, to)) findClose(contentStart, to, width) else -1
            if (close < 0) {
                repeat(run) { buffer.append('*') }
                i += run
                continue
            }
            repeat(run - width) { buffer.append('*') }
            emit(buffer, bold, italic)
            parseRange(contentStart, close, bold || width >= 2, italic || width != 2)
            i = close + width
        }
        emit(buffer, bold, italic)
    }

    /**
     * Index of the closing delimiter of [width] stars for content starting at [from], or -1. Memoized: a line of
     * unmatched openers would otherwise re-scan the same nested spans exponentially often.
     */
    private fun findClose(from: Int, to: Int, width: Int): Int =
        closers.getOrPut(Triple(from, to, width)) { scanForClose(from, to, width) }

    private fun scanForClose(from: Int, to: Int, width: Int): Int {
        var j = from
        while (j < to) {
            when (text[j]) {
                '\\' -> j += 2
                '*' -> {
                    val run = starRun(j, to)
                    val canClose = !text[j - 1].isWhitespace()
                    val opens = canOpen(j, run, to)
                    if (canClose && run >= width && (run == width || !opens)) return j
                    if (opens) {
                        val nestedWidth = min(run, 3)
                        val nested = findClose(j + run, to, nestedWidth)
                        if (nested >= 0) {
                            j = nested + nestedWidth
                            continue
                        }
                    }
                    j += run
                }
                else -> j++
            }
        }
        return -1
    }

    private fun starRun(from: Int, to: Int): Int {
        var end = from
        while (end < to && text[end] == '*') end++
        return end - from
    }

    private fun canOpen(at: Int, run: Int, to: Int): Boolean = at + run < to && !text[at + run].isWhitespace()

    private fun emit(buffer: StringBuilder, bold: Boolean, italic: Boolean) {
        if (buffer.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.bold == bold && last.italic == italic) {
            runs[runs.lastIndex] = last.copy(text = last.text + buffer)
        } else {
            runs += TextRun(buffer.toString(), bold, italic)
        }
        buffer.clear()
    }

    private companion object {
        const val ESCAPABLE = "\\`*_{}[]()#+-.!>"
    }
}

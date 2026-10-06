package com.sleepsaver.feature.learn.markdown

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.feature.learn.markdown.MarkdownBlock.BulletList
import com.sleepsaver.feature.learn.markdown.MarkdownBlock.Heading
import com.sleepsaver.feature.learn.markdown.MarkdownBlock.OrderedList
import com.sleepsaver.feature.learn.markdown.MarkdownBlock.Paragraph
import com.sleepsaver.feature.learn.markdown.MarkdownBlock.Quote
import org.junit.Test

class MarkdownParserTest {

    private fun plain(text: String) = listOf(TextRun(text))
    private fun bold(text: String) = TextRun(text, bold = true)
    private fun italic(text: String) = TextRun(text, italic = true)
    private fun inline(text: String) = MarkdownParser.parseInline(text)

    // Front matter

    @Test
    fun `front matter is read and removed from the body`() {
        val doc = MarkdownParser.parse(
            """
            ---
            title: Sleep debt
            summary: "Quoted: with a colon"
            minutes: 3
            order: 1
            ---
            Body text.
            """.trimIndent(),
        )

        assertThat(doc.frontMatter).containsExactly(
            "title", "Sleep debt",
            "summary", "Quoted: with a colon",
            "minutes", "3",
            "order", "1",
        ).inOrder()
        assertThat(doc.blocks).containsExactly(Paragraph(plain("Body text.")))
    }

    @Test
    fun `no front matter leaves everything in the body`() {
        val doc = MarkdownParser.parse("Just text.")

        assertThat(doc.frontMatter).isEmpty()
        assertThat(doc.blocks).containsExactly(Paragraph(plain("Just text.")))
    }

    @Test
    fun `unclosed front matter fence is treated as body`() {
        val doc = MarkdownParser.parse("---\ntitle: x")

        assertThat(doc.frontMatter).isEmpty()
        assertThat(doc.blocks).containsExactly(Paragraph(plain("--- title: x")))
    }

    @Test
    fun `windows line endings and a byte order mark are handled`() {
        val doc = MarkdownParser.parse("﻿---\r\ntitle: Naps\r\n---\r\n## Short\r\n\r\nOne\r\ntwo\r\n")

        assertThat(doc.frontMatter).containsExactly("title", "Naps")
        assertThat(doc.blocks).containsExactly(Heading(2, plain("Short")), Paragraph(plain("One two"))).inOrder()
    }

    // Blocks

    @Test
    fun `headings of three levels, deeper ones clamped, closing hashes dropped`() {
        val doc = MarkdownParser.parse("# One\n## Two ##\n### Three\n#### Four\n#NoSpace")

        assertThat(doc.blocks).containsExactly(
            Heading(1, plain("One")),
            Heading(2, plain("Two")),
            Heading(3, plain("Three")),
            Heading(3, plain("Four")),
            Paragraph(plain("#NoSpace")),
        ).inOrder()
    }

    @Test
    fun `paragraph lines are joined and blank lines separate paragraphs`() {
        val doc = MarkdownParser.parse("First line\n  second line\n\n\nNext paragraph")

        assertThat(doc.blocks).containsExactly(
            Paragraph(plain("First line second line")),
            Paragraph(plain("Next paragraph")),
        ).inOrder()
    }

    @Test
    fun `a heading interrupts a paragraph`() {
        val doc = MarkdownParser.parse("Some text\n## Section\nMore text")

        assertThat(doc.blocks).containsExactly(
            Paragraph(plain("Some text")),
            Heading(2, plain("Section")),
            Paragraph(plain("More text")),
        ).inOrder()
    }

    @Test
    fun `bullet list with continuation lines`() {
        val doc = MarkdownParser.parse("Intro:\n- one\n- two\n  continued\n* three\n\nAfter")

        assertThat(doc.blocks).containsExactly(
            Paragraph(plain("Intro:")),
            BulletList(listOf(plain("one"), plain("two continued"), plain("three"))),
            Paragraph(plain("After")),
        ).inOrder()
    }

    @Test
    fun `blank line between items keeps one list`() {
        val doc = MarkdownParser.parse("- a\n\n- b")

        assertThat(doc.blocks).containsExactly(BulletList(listOf(plain("a"), plain("b"))))
    }

    @Test
    fun `ordered list keeps its start number`() {
        val doc = MarkdownParser.parse("3. three\n4) four\n5. five")

        assertThat(doc.blocks).containsExactly(OrderedList(3, listOf(plain("three"), plain("four"), plain("five"))))
    }

    @Test
    fun `switching list kind starts a new list`() {
        val doc = MarkdownParser.parse("- bullet\n1. number")

        assertThat(doc.blocks).containsExactly(
            BulletList(listOf(plain("bullet"))),
            OrderedList(1, listOf(plain("number"))),
        ).inOrder()
    }

    @Test
    fun `quote lines are grouped and parsed recursively`() {
        val doc = MarkdownParser.parse("> A calm\n> thought.\n>\n> - with a list\n\nOutside")

        assertThat(doc.blocks).containsExactly(
            Quote(listOf(Paragraph(plain("A calm thought.")), BulletList(listOf(plain("with a list"))))),
            Paragraph(plain("Outside")),
        ).inOrder()
    }

    @Test
    fun `escaped markers are literal text`() {
        val doc = MarkdownParser.parse("\\- not a list\n\n\\# not a heading")

        assertThat(doc.blocks).containsExactly(
            Paragraph(plain("- not a list")),
            Paragraph(plain("# not a heading")),
        ).inOrder()
    }

    @Test
    fun `empty input has no blocks`() {
        assertThat(MarkdownParser.parse("").blocks).isEmpty()
        assertThat(MarkdownParser.parse("\n\n  \n").blocks).isEmpty()
    }

    // Inline

    @Test
    fun `bold and italic`() {
        assertThat(inline("a **b** c *d* e")).containsExactly(
            TextRun("a "),
            bold("b"),
            TextRun(" c "),
            italic("d"),
            TextRun(" e"),
        ).inOrder()
    }

    @Test
    fun `triple stars are bold italic`() {
        assertThat(inline("***both***")).containsExactly(TextRun("both", bold = true, italic = true))
    }

    @Test
    fun `nested emphasis`() {
        assertThat(inline("*a **b** c*")).containsExactly(
            italic("a "),
            TextRun("b", bold = true, italic = true),
            italic(" c"),
        ).inOrder()
        assertThat(inline("**bold *italic***")).containsExactly(
            bold("bold "),
            TextRun("italic", bold = true, italic = true),
        ).inOrder()
    }

    @Test
    fun `unmatched or spaced stars stay literal`() {
        assertThat(inline("2 * 3 * 4")).containsExactly(TextRun("2 * 3 * 4"))
        assertThat(inline("*open")).containsExactly(TextRun("*open"))
        assertThat(inline("**open *it*")).containsExactly(TextRun("**open "), italic("it")).inOrder()
        assertThat(inline("** not bold **")).containsExactly(TextRun("** not bold **"))
    }

    @Test(timeout = 2_000)
    fun `a long line of unmatched openers parses quickly`() {
        val text = "**a ".repeat(60) + "*b ".repeat(60)

        assertThat(inline(text)).containsExactly(TextRun(text))
    }

    @Test
    fun `escaped stars are literal`() {
        assertThat(inline("\\*not italic\\*")).containsExactly(TextRun("*not italic*"))
    }

    @Test
    fun `adjacent runs with the same style are merged`() {
        assertThat(inline("*a *b* c*")).containsExactly(italic("a b c"))
    }

    @Test
    fun `emphasis inside headings and list items`() {
        val doc = MarkdownParser.parse("## The **dip**\n- *gently*")

        assertThat(doc.blocks).containsExactly(
            Heading(2, listOf(TextRun("The "), bold("dip"))),
            BulletList(listOf(listOf(italic("gently")))),
        ).inOrder()
    }

    @Test
    fun `word count ignores markup`() {
        val doc = MarkdownParser.parse("## Two words\n\n- three more words\n\nAnd **four** words, here.")

        assertThat(doc.blocks.wordCount()).isEqualTo(2 + 3 + 4)
    }
}

package com.sleepsaver.feature.learn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.feature.learn.markdown.MarkdownBlock
import com.sleepsaver.feature.learn.markdown.MarkdownDocument
import com.sleepsaver.feature.learn.markdown.MarkdownParser
import com.sleepsaver.feature.learn.markdown.plainText
import com.sleepsaver.feature.learn.markdown.wordCount
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the real bundled articles, as packaged into the module's assets. */
@RunWith(AndroidJUnit4::class)
class ArticleAssetsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val slugs = listOf(
        "sleep-debt", "sleep-need", "circadian-rhythm", "sleep-inertia", "afternoon-dip",
        "melatonin-window", "light-exposure", "caffeine", "wind-down", "naps",
        "waking-gently", "night-steps", "keel",
    )

    private val documents: Map<String, MarkdownDocument> by lazy {
        slugs.associateWith { slug ->
            MarkdownParser.parse(context.assets.open("learn/$slug.md").bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun `exactly the thirteen articles are bundled`() {
        assertThat(context.assets.list("learn").orEmpty().toList()).containsExactlyElementsIn(slugs.map { "$it.md" })
    }

    @Test
    fun `every article has complete front matter and a unique order`() {
        documents.forEach { (slug, doc) ->
            val fm = doc.frontMatter
            assertWithMessage("$slug title").that(fm["title"].orEmpty()).isNotEmpty()
            assertWithMessage("$slug summary").that(fm["summary"].orEmpty()).isNotEmpty()
            assertWithMessage("$slug minutes").that(fm["minutes"]?.toIntOrNull()).isIn(Range.closed(1, 5))
            assertWithMessage("$slug order").that(fm["order"]?.toIntOrNull()).isNotNull()
        }
        val orders = documents.values.map { it.frontMatter.getValue("order").toInt() }
        assertThat(orders).containsNoDuplicates()
    }

    @Test
    fun `every article has the house shape`() {
        documents.forEach { (slug, doc) ->
            val headings = doc.blocks.filterIsInstance<MarkdownBlock.Heading>()
            assertWithMessage("$slug: only ## sections").that(headings.map { it.level }.distinct()).containsExactly(2)
            assertWithMessage("$slug: section count").that(headings.size).isIn(Range.closed(2, 4))
            assertWithMessage("$slug: last section").that(headings.last().text.plainText()).isEqualTo("In Sleep Saver")
            assertWithMessage("$slug: opens with text").that(doc.blocks.first()).isInstanceOf(MarkdownBlock.Paragraph::class.java)
            assertWithMessage("$slug: ends with text").that(doc.blocks.last()).isNotInstanceOf(MarkdownBlock.Heading::class.java)
            assertWithMessage("$slug: has a list").that(doc.blocks.any { it is MarkdownBlock.BulletList }).isTrue()
            assertWithMessage("$slug: word count").that(doc.blocks.wordCount()).isIn(Range.closed(250, 450))
        }
    }

    @Test
    fun `stated reading time matches the length`() {
        documents.forEach { (slug, doc) ->
            val estimate = estimatedMinutes(doc.blocks.wordCount())
            assertWithMessage(slug).that(doc.frontMatter.getValue("minutes").toInt()).isIn(Range.closed(estimate - 1, estimate + 1))
        }
    }

    @Test
    fun `no markup is left over after parsing`() {
        documents.forEach { (slug, doc) ->
            val text = doc.blocks.plainText()
            assertWithMessage(slug).that(text).doesNotContain("*")
            assertWithMessage(slug).that(text).doesNotContainMatch("(?m)^\\s*(#|>|- )")
        }
    }

    @Test
    fun `the new articles come last, in order`() {
        val orders = listOf("waking-gently", "night-steps", "keel").map { documents.getValue(it).frontMatter.getValue("order").toInt() }

        assertThat(orders).containsExactly(11, 12, 13).inOrder()
    }

    @Test
    fun `articles keep to honest alarm copy and leave out other apps' terms`() {
        // SPEC_V2 §0.2 and §0.3: no stage claims for the alarm, no competitors' feature names.
        val banned = listOf(
            "smart alarm", "smart wake", "wake-up phase", "wake up window", "smart period", "sunrise alarm", "dawn glow",
            "unwind", "wind down routine", "brain dump", "sleep planner", "bedtime guidance", "tonight's bedtime",
            "super streak", "readiness", "restore", "soundscape", "sleep stories", "lullaby", "sleep notes", "check-in",
            "sleep score", "sleep consistency", "sleep rhythm", "lights out", "lights off", "word drift",
            "light phase", "lighter phase", "light sleep detected",
        )
        documents.forEach { (slug, doc) ->
            val text = doc.blocks.plainText().lowercase()
            banned.forEach { term -> assertWithMessage("$slug says \"$term\"").that(text).doesNotContain(term) }
        }
    }

    @Test
    fun `articles stay brand neutral`() {
        documents.forEach { (slug, doc) ->
            val text = doc.blocks.plainText().lowercase()
            listOf("garmin", "health connect", "fitbit", "oura", "whoop").forEach { brand ->
                assertWithMessage("$slug mentions $brand").that(text).doesNotContainMatch("\\b$brand\\b")
            }
        }
    }
}

package com.sleepsaver.feature.learn.data

import com.sleepsaver.feature.learn.markdown.MarkdownBlock
import com.sleepsaver.feature.learn.markdown.MarkdownDocument
import com.sleepsaver.feature.learn.markdown.wordCount
import kotlin.math.ceil

/** What the Learn list shows for an article. [slug] is the asset file name without `.md`. */
data class ArticleMeta(
    val slug: String,
    val title: String,
    val summary: String,
    val minutes: Int,
    val order: Int,
)

data class Article(val meta: ArticleMeta, val blocks: List<MarkdownBlock>)

/**
 * Reads the `title`, `summary`, `minutes` and `order` front matter. Missing values fall back to the slug,
 * an empty summary, a [WORDS_PER_MINUTE] estimate and last place.
 */
fun MarkdownDocument.toArticle(slug: String): Article = Article(
    meta = ArticleMeta(
        slug = slug,
        title = frontMatter["title"]?.takeIf { it.isNotBlank() } ?: slug,
        summary = frontMatter["summary"].orEmpty(),
        minutes = frontMatter["minutes"]?.toIntOrNull()?.takeIf { it > 0 } ?: estimatedMinutes(blocks.wordCount()),
        order = frontMatter["order"]?.toIntOrNull() ?: Int.MAX_VALUE,
    ),
    blocks = blocks,
)

fun estimatedMinutes(words: Int): Int = ceil(words / WORDS_PER_MINUTE).toInt().coerceAtLeast(1)

private const val WORDS_PER_MINUTE = 200.0

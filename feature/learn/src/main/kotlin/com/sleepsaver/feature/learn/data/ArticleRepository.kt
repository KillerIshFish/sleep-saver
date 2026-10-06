package com.sleepsaver.feature.learn.data

import android.content.Context
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.feature.learn.markdown.MarkdownParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The bundled articles in `assets/learn/<slug>.md`. Parsed once on first use, then served from memory. */
@Singleton
class ArticleRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var cache: List<Article>? = null

    /** All articles, sorted by their front-matter `order`, then title. */
    suspend fun articles(): List<Article> = mutex.withLock {
        cache ?: withContext(ioDispatcher) { load() }.also { cache = it }
    }

    suspend fun article(slug: String): Article? = articles().firstOrNull { it.meta.slug == slug }

    private fun load(): List<Article> {
        val assets = context.assets
        return assets.list(ASSET_DIR).orEmpty()
            .filter { it.endsWith(EXTENSION) }
            .map { file ->
                val source = assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
                MarkdownParser.parse(source).toArticle(slug = file.removeSuffix(EXTENSION))
            }
            .sortedWith(compareBy<Article>({ it.meta.order }, { it.meta.title }))
    }

    private companion object {
        const val ASSET_DIR = "learn"
        const val EXTENSION = ".md"
    }
}

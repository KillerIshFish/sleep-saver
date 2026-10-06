package com.sleepsaver.feature.learn.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sleepsaver.feature.learn.ArticleScreen
import com.sleepsaver.feature.learn.LearnScreen
import kotlinx.serialization.Serializable

/** The Learn tab: the article list. */
@Serializable
data object LearnRoute

/** One article; [slug] is its asset file name without `.md`, e.g. `sleep-debt`. */
@Serializable
data class ArticleRoute(val slug: String)

/**
 * Adds the article list ([LearnRoute]) and the article reader ([ArticleRoute]).
 * Wire [onOpenArticle] to [navigateToArticle] and [onBack] to `popBackStack`. Taps on a screen that isn't
 * resumed (e.g. still animating in or out) are dropped, so a double tap can't open two articles or pop twice.
 */
fun NavGraphBuilder.learnGraph(onOpenArticle: (String) -> Unit, onBack: () -> Unit) = learnGraph(
    onOpenArticle = onOpenArticle,
    onBack = onBack,
    list = { open -> LearnScreen(onOpenArticle = open) },
    article = { back -> ArticleScreen(onBack = back) },
)

/** [learnGraph] with the screens passed in, so tests can drive the real graph without Hilt. */
internal fun NavGraphBuilder.learnGraph(
    onOpenArticle: (String) -> Unit,
    onBack: () -> Unit,
    list: @Composable (onOpenArticle: (String) -> Unit) -> Unit,
    article: @Composable (onBack: () -> Unit) -> Unit,
) {
    composable<LearnRoute> { entry ->
        list { slug -> if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onOpenArticle(slug) }
    }
    composable<ArticleRoute> { article(dropUnlessResumed(block = onBack)) }
}

/**
 * Opens [slug] unless that article is already on top, so a double tap doesn't stack it twice. Not
 * `launchSingleTop`: that would reuse the top article's entry, and its ViewModel would keep showing the old slug.
 */
fun NavController.navigateToArticle(slug: String) {
    val top = currentBackStackEntry
    if (top != null && top.destination.hasRoute<ArticleRoute>() && top.toRoute<ArticleRoute>().slug == slug) return
    navigate(ArticleRoute(slug))
}

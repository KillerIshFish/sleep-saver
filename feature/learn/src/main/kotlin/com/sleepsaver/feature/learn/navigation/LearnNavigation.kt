package com.sleepsaver.feature.learn.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
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
 * Wire [onOpenArticle] to [navigateToArticle] and [onBack] to `popBackStack`.
 */
fun NavGraphBuilder.learnGraph(onOpenArticle: (String) -> Unit, onBack: () -> Unit) {
    composable<LearnRoute> { LearnScreen(onOpenArticle = onOpenArticle) }
    composable<ArticleRoute> { ArticleScreen(onBack = onBack) }
}

fun NavController.navigateToArticle(slug: String) {
    navigate(ArticleRoute(slug)) { launchSingleTop = true }
}

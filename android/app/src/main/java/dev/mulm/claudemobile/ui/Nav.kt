package dev.mulm.claudemobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

sealed interface Route {
    data object Sessions : Route
    data class Chat(val sid: String) : Route
    data class Settings(val url: String? = null, val token: String? = null) : Route
    data object NewSession : Route
    data object GitHub : Route
    data class Repo(val repo: String, val tab: String = "prs") : Route
    data class Pr(val repo: String, val number: Int) : Route
    data class Issue(val repo: String, val number: Int) : Route
}

object Nav {
    val route = mutableStateOf<Route>(Route.Sessions)
    fun go(r: Route) {
        route.value = r
    }

    /** One level up: a PR or issue goes back to its repository, a repository to the list. */
    fun back() {
        route.value = when (val r = route.value) {
            is Route.Pr -> Route.Repo(r.repo, "prs")
            is Route.Issue -> Route.Repo(r.repo, "issues")
            is Route.Repo -> Route.GitHub
            else -> Route.Sessions
        }
    }
}

@Composable
fun AppRoot() {
    val route by Nav.route
    BackHandler(enabled = route !is Route.Sessions) { Nav.back() }
    when (val r = route) {
        Route.Sessions -> SessionsScreen()
        is Route.Chat -> ChatScreen(r.sid)
        is Route.Settings -> SettingsScreen(r)
        Route.NewSession -> NewSessionScreen()
        Route.GitHub -> GitHubScreen()
        is Route.Repo -> RepoScreen(r.repo, r.tab)
        is Route.Pr -> PrScreen(r.repo, r.number)
        is Route.Issue -> IssueScreen(r.repo, r.number)
    }
}

fun ago(epochSecs: Double, nowMs: Long): String {
    if (epochSecs <= 0) return ""
    val s = (nowMs / 1000.0 - epochSecs).toLong().coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

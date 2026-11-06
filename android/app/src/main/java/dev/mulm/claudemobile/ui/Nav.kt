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
}

object Nav {
    val route = mutableStateOf<Route>(Route.Sessions)
    fun go(r: Route) {
        route.value = r
    }
}

@Composable
fun AppRoot() {
    val route by Nav.route
    BackHandler(enabled = route !is Route.Sessions) { Nav.go(Route.Sessions) }
    when (val r = route) {
        Route.Sessions -> SessionsScreen()
        is Route.Chat -> ChatScreen(r.sid)
        is Route.Settings -> SettingsScreen(r)
        Route.NewSession -> NewSessionScreen()
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

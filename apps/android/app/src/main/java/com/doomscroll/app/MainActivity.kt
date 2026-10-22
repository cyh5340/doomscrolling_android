package com.doomscroll.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.doomscroll.data.Settings
import com.doomscroll.groups.GroupScreen
import com.doomscroll.groups.JoinScreen
import com.doomscroll.groups.NewGroupScreen
import com.doomscroll.home.DebugScreen
import com.doomscroll.home.HomeScreen
import com.doomscroll.network.EXTRA_GROUP_ID
import com.doomscroll.onboarding.OnboardingScreen
import com.doomscroll.ui.DoomscrollTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: Settings

    /** Screen to show next: "group/{id}" from a notification tap, or "join/{code}" from an invite link. */
    private val openRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            DoomscrollTheme {
                val onboarded by settings.onboarded.collectAsState(initial = null)
                val start = when (onboarded) {
                    null -> return@DoomscrollTheme
                    true -> "home"
                    false -> "onboarding"
                }
                val nav = rememberNavController()
                val pendingRoute by openRoute.collectAsState()
                LaunchedEffect(pendingRoute, onboarded) {
                    val route = pendingRoute ?: return@LaunchedEffect
                    if (onboarded == true) {
                        nav.navigate(route)
                        openRoute.value = null
                    }
                }

                NavHost(nav, startDestination = start) {
                    composable("onboarding") {
                        OnboardingScreen(onFinished = { result ->
                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                            result.inviteCode?.let { nav.navigate(joinRoute(it)) }
                        })
                    }
                    composable("home") {
                        HomeScreen(
                            onOpenGroup = { nav.navigate("group/$it") },
                            onNewGroup = { nav.navigate("groups/new") },
                            onDebug = { nav.navigate("debug") },
                            onAccountDeleted = {
                                nav.navigate("onboarding") { popUpTo(0) { inclusive = true } }
                            },
                        )
                    }
                    composable("groups/new") {
                        NewGroupScreen(onBack = { nav.popBackStack() }, onDone = { id ->
                            nav.navigate("group/$id") { popUpTo("home") }
                        }, onJoin = { nav.navigate(joinRoute(it)) })
                    }
                    composable("group/{id}") {
                        GroupScreen(onBack = { nav.popBackStack() }, onLeft = { nav.popBackStack("home", inclusive = false) })
                    }
                    composable("join/{code}") {
                        JoinScreen(onBack = { nav.popBackStack() }, onJoined = { id ->
                            nav.navigate("group/$id") { popUpTo("home") }
                        })
                    }
                    composable("debug") { DebugScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_GROUP_ID)?.let { openRoute.value = "group/$it" }

        val code = intent.data?.pathSegments?.takeIf { it.firstOrNull() == "join" }?.getOrNull(1)
            ?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            // Onboarding opens the join screen at the end.
            if (!settings.onboarded.first()) settings.setPendingInvite(code) else openRoute.value = joinRoute(code)
        }
    }
}

/** Codes come from links people paste, so they're encoded to stay one route segment. */
private fun joinRoute(code: String) = "join/${Uri.encode(code)}"

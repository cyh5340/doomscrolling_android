package com.doomscroll.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.network.Account
import com.doomscroll.network.SyncWorker
import com.doomscroll.ui.EmojiBadge
import com.doomscroll.ui.InfoRow
import com.doomscroll.ui.MaxContentWidth
import com.doomscroll.usage.UsageEventReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.doomscroll.data.Settings as AppSettings

enum class Step { DISCLOSURE, USAGE_ACCESS, NOTIFICATIONS, FINISHING }

/** Result of onboarding: the invite code the user arrived with, if any. The join screen comes next, for stake consent. */
data class Finished(val inviteCode: String?)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: UsageEventReader,
    private val account: Account,
    private val settings: AppSettings,
) : ViewModel() {
    val step = MutableStateFlow(Step.DISCLOSURE)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val finished = MutableStateFlow<Finished?>(null)

    fun agree(displayName: String) = viewModelScope.launch {
        busy.value = true
        error.value = null
        try {
            account.signIn(displayName.trim().ifEmpty { "Anonymous" })
            step.value = Step.USAGE_ACCESS
            onResume()
        } catch (e: Exception) {
            error.value = "Couldn't sign in. Check your connection and try again."
        } finally {
            busy.value = false
        }
    }

    /** Usage access is granted in system settings, so re-check every time we come back. */
    fun onResume() {
        if (step.value == Step.USAGE_ACCESS && reader.hasAccess()) {
            if (needsNotificationPermission()) step.value = Step.NOTIFICATIONS else finish()
        }
    }

    private fun needsNotificationPermission() = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun finish() = viewModelScope.launch {
        step.value = Step.FINISHING
        SyncWorker.schedule(context)
        SyncWorker.runNow(context)
        runCatching { account.syncPushToken() }
        val code = settings.pendingInvite.first()
        settings.setPendingInvite(null)
        settings.setOnboarded()
        finished.value = Finished(code)
    }
}

@Composable
fun OnboardingScreen(onFinished: (Finished) -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose {}
    }
    LaunchedEffect(finished) { finished?.let(onFinished) }

    Surface(Modifier.fillMaxSize()) {
        when (step) {
            Step.DISCLOSURE -> Disclosure(vm)
            Step.USAGE_ACCESS -> UsageAccess()
            Step.NOTIFICATIONS -> Notifications(vm)
            Step.FINISHING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Setting things up…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One onboarding step: progress bars on top, scrolling content, actions pinned to the bottom. */
@Composable
private fun Page(index: Int, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().wrapContentWidth().widthIn(max = MaxContentWidth).padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).background(
                        if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

// Google Play's prominent disclosure: shown before any data is collected, with an explicit agree button.
@Composable
private fun Disclosure(vm: OnboardingViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }

    Page(
        index = 0,
        bottom = {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BigButton(if (busy) "Signing in…" else "I agree", enabled = !busy) { vm.agree(name) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("doom") }
                    append("scroll")
                },
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                "Keep each other honest about time spent scrolling.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Your name, as friends will see it") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InfoRow(
            "📱", "What this app tracks",
            "How long you spend in social apps such as TikTok, Instagram, YouTube, Reddit, X, Snapchat and Facebook. " +
                "It reads this from Android's usage access, which you turn on in the next step.",
        )
        InfoRow(
            "👀", "What your friends see",
            "Only your time per category in 15-minute steps (for example \"social: 45 min\"), and an alert when you pass " +
                "your group's limits. We'll ask to send you notifications when a friend passes theirs.",
        )
        InfoRow("🔒", "What never leaves your phone", "Which apps you used, your exact minutes, and anything you do inside those apps.")
        InfoRow("🗑️", "Your control", "You can delete your account and all of its data at any time from the app.")
    }
}

@Composable
private fun UsageAccess() {
    val context = LocalContext.current
    Page(index = 1, bottom = { BigButton("Open settings") { openUsageAccessSettings(context) } }) {
        EmojiBadge("📊", size = 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Turn on usage access", style = MaterialTheme.typography.headlineMedium)
            Text(
                "This is how Doomscroll measures time in social apps. It stays on your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Tap Open settings", "Find Doomscroll in the list", "Switch on \"Permit usage access\"").forEachIndexed { i, text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge) }
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            "Come back when you're done. We'll continue automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notifications(vm: OnboardingViewModel) {
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.finish() }
    Page(
        index = 2,
        bottom = {
            BigButton("Allow notifications") { request.launch(Manifest.permission.POST_NOTIFICATIONS) }
            TextButton(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        },
    ) {
        EmojiBadge("🔔", size = 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Friend alerts", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Get a nudge when someone in your group passes their limit, so you can call them out.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A sample of what an alert looks like.
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmojiBadge("🔥", size = 36.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Doomscroll · now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Roommates", style = MaterialTheme.typography.titleSmall)
                    Text("Sam passed 1h of doomscrolling today.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Opens our own entry in usage access settings where supported, else the full list. */
fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

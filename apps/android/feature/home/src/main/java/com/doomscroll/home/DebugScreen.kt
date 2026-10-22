package com.doomscroll.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.data.OutboxDao
import com.doomscroll.data.UsageTracker
import com.doomscroll.network.SyncWorker
import com.doomscroll.ui.AppScaffold
import com.doomscroll.ui.Notice
import com.doomscroll.ui.SectionHeader
import com.doomscroll.usage.UsageEventReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: UsageEventReader,
    tracker: UsageTracker,
    outbox: OutboxDao,
) : ViewModel() {
    val hasAccess = MutableStateFlow(false)
    val snapshot = tracker.snapshot
    val outbox = outbox.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onResume() {
        hasAccess.value = reader.hasAccess()
        if (hasAccess.value) runNow()
    }

    fun runNow() = SyncWorker.runNow(context)
}

@Composable
fun DebugScreen(onBack: () -> Unit, vm: DebugViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val hasAccess by vm.hasAccess.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose {}
    }

    // ponytail: plain Column, not lazy; two days of sessions is small. Switch to LazyColumn if it gets slow.
    AppScaffold("Debug", onBack = onBack) {
        if (!hasAccess) {
            Notice("Usage access is off.", "Open usage access settings") {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            return@AppScaffold
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::runNow) { Text("Run now") }
            Text("Last run ${snapshot?.let { time.format(Date(it.ranAtMs)) } ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionHeader("Today")
        snapshot?.todayTotalsMs.orEmpty().forEach { (label, ms) -> Mono("$label: ${ms / 60_000} min ${ms / 1000 % 60} s") }
        SectionHeader("Sessions since yesterday")
        snapshot?.sessions.orEmpty().reversed().forEach { s ->
            Mono(
                "${s.label}  ${time.format(Date(s.startMs))} – ${time.format(Date(s.endMs))}  " +
                    "${s.durationMs / 60_000}m${if (s.isOngoing) "  (ongoing)" else ""}",
            )
        }
        SectionHeader("Outbox (${outbox.size})")
        outbox.forEach { e -> Mono("${e.localDate} ${e.type} ${e.label} ${e.bucketMinutes}m  [${e.status}]") }
    }
}

@Composable
private fun Mono(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

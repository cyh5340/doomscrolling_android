package com.doomscroll.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.data.OutboxDao
import com.doomscroll.data.UsageTracker
import com.doomscroll.network.Account
import com.doomscroll.network.Group
import com.doomscroll.network.Groups
import com.doomscroll.network.SyncWorker
import com.doomscroll.ui.AppIcons
import com.doomscroll.ui.AppScaffold
import com.doomscroll.ui.Avatar
import com.doomscroll.ui.EmojiBadge
import com.doomscroll.ui.Notice
import com.doomscroll.ui.SectionHeader
import com.doomscroll.ui.formatMinutes
import com.doomscroll.ui.formatMoney
import com.doomscroll.usage.UsageEventReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: UsageEventReader,
    private val groups: Groups,
    private val account: Account,
    tracker: UsageTracker,
    outbox: OutboxDao,
) : ViewModel() {
    val hasAccess = MutableStateFlow(true)
    val myGroups = MutableStateFlow<List<Group>?>(null)
    val groupsFailed = MutableStateFlow(false)
    val snapshot = tracker.snapshot
    val deleted = MutableStateFlow(false)
    val deleteFailed = MutableStateFlow(false)

    /** True when events have waited over 2h to upload: background work is probably being killed. */
    val uploadsStalled = outbox.oldestPending()
        .map { oldest -> oldest != null && Duration.between(Instant.parse(oldest), Instant.now()).toHours() >= 2 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onResume() {
        hasAccess.value = reader.hasAccess()
        if (hasAccess.value) SyncWorker.runNow(context)
        viewModelScope.launch {
            runCatching { groups.mine() }
                .onSuccess { myGroups.value = it; groupsFailed.value = false }
                .onFailure { groupsFailed.value = true }
        }
    }

    fun deleteAccount() = viewModelScope.launch {
        runCatching { account.deleteAccount() }
            .onSuccess { deleted.value = true }
            .onFailure { deleteFailed.value = true }
    }
}

@Composable
fun HomeScreen(
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onDebug: () -> Unit,
    onAccountDeleted: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasAccess by vm.hasAccess.collectAsStateWithLifecycle()
    val stalled by vm.uploadsStalled.collectAsStateWithLifecycle()
    val groups by vm.myGroups.collectAsStateWithLifecycle()
    val groupsFailed by vm.groupsFailed.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    val deleteFailed by vm.deleteFailed.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose {}
    }
    LaunchedEffect(deleted) { if (deleted) onAccountDeleted() }

    AppScaffold(
        title = "Today",
        actions = {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.more, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Debug") }, onClick = { menu = false; onDebug() })
                DropdownMenuItem(
                    text = { Text("Delete account", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewGroup,
                icon = { Icon(AppIcons.add, contentDescription = null) },
                text = { Text("Create or join") },
            )
        },
    ) {
        Text(
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasAccess) {
            Notice("Tracking is paused: usage access was turned off.", "Turn it back on") {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (stalled) BatteryHelp()
        if (deleteFailed) Notice("Couldn't delete your account. Check your connection and try again.")

        val totalMinutes = (snapshot?.todayTotalsMs.orEmpty().values.sum() / 60_000).toInt()
        TodayCard(totalMinutes, limit = groups?.minOfOrNull { it.dailyAlertMinutes })

        SectionHeader("Your groups")
        when {
            groupsFailed && groups == null -> Notice("Couldn't load your groups. Check your connection.")
            groups == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            groups!!.isEmpty() -> EmptyGroups(onNewGroup)
            else -> groups!!.forEach { GroupCard(it) { onOpenGroup(it.id) } }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This deletes your account and all your data on our server and on this phone. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) {
                    Text("Delete everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Ring of today's minutes against the strictest group's daily limit. */
@Composable
private fun TodayCard(minutes: Int, limit: Int?) {
    val colors = MaterialTheme.colorScheme
    val progress = if (limit == null) 0f else (minutes.toFloat() / limit).coerceAtMost(1f)
    val ringColor = when {
        limit == null -> colors.secondary
        minutes >= limit -> colors.error
        progress >= 0.75f -> colors.primary
        else -> colors.tertiary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(212.dp),
                    color = ringColor,
                    strokeWidth = 14.dp,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatMinutes(minutes), style = MaterialTheme.typography.displayMedium)
                    Text("in social apps", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Text(
                when {
                    limit == null -> "Join a group to get a daily limit."
                    minutes >= limit -> "Over your group's ${formatMinutes(limit)} limit. They've been told."
                    else -> "${formatMinutes(limit - minutes)} left before your group hears about it."
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Friends see ${formatMinutes(minutes / 15 * 15)}: time is shared in 15-minute steps.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GroupCard(group: Group, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(group.name, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${formatMinutes(group.dailyAlertMinutes)} a day")
                        group.sessionAlertMinutes?.let { append(" · ${formatMinutes(it)} session") }
                        if (group.stakeCents > 0) append(" · ${formatMoney(group.stakeCents)}/week")
                        append(if (group.ranking == "least") " · hall of fame" else " · leaderboard")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyGroups(onNewGroup: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmojiBadge("👯", size = 56.dp)
            Text("Scrolling is easier to quit together", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "Start a group, invite friends, and you'll hear about it when someone overdoes it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onNewGroup) { Text("Start a group") }
        }
    }
}

/** Shown only when uploads stall. Samsung, Xiaomi and others often stop background work. */
@Composable
private fun BatteryHelp() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Notice(
        "Your phone seems to be stopping Doomscroll in the background, so friends aren't seeing updates.",
        if (expanded) "Open battery settings" else "How to fix",
        onAction = {
            if (expanded) context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else expanded = true
        },
    ) {
        if (expanded) {
            Text(
                "1. In battery settings, set Doomscroll to \"Unrestricted\" or \"Don't optimize\".\n" +
                    "2. Samsung: Settings › Battery › Background usage limits, and remove Doomscroll from sleeping apps.\n" +
                    "3. Xiaomi: Settings › Apps › Doomscroll › Battery saver › No restrictions, and turn on Autostart.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

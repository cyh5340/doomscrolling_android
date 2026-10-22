package com.doomscroll.groups

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.leaderboard.Leaderboard
import com.doomscroll.network.Account
import com.doomscroll.network.Group
import com.doomscroll.network.Groups
import com.doomscroll.network.Pot
import com.doomscroll.network.Preset
import com.doomscroll.network.Settlement
import com.doomscroll.ui.AppIcons
import com.doomscroll.ui.AppScaffold
import com.doomscroll.ui.EmojiBadge
import com.doomscroll.ui.Notice
import com.doomscroll.ui.Pill
import com.doomscroll.ui.SectionHeader
import com.doomscroll.ui.formatMinutes
import com.doomscroll.ui.formatMoney
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

// ---------------------------------------------------------------- create / join

@HiltViewModel
class NewGroupViewModel @Inject constructor(private val groups: Groups) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** Id of the group created or joined. */
    val done = MutableStateFlow<String?>(null)

    fun create(name: String, preset: Preset, ranking: String, stakeCents: Int) = run("Couldn't create the group.") {
        groups.create(name.trim(), preset, ranking, stakeCents).id
    }

    private fun run(failure: String, block: suspend () -> String) = viewModelScope.launch {
        busy.value = true
        error.value = null
        try {
            done.value = block()
        } catch (e: Exception) {
            error.value = failure
        } finally {
            busy.value = false
        }
    }
}

private fun rules(daily: Int, session: Int?, streak: Int) = buildList {
    add("⏱ ${formatMinutes(daily)} a day")
    session?.let { add("⚡ ${formatMinutes(it)} in one go") }
    add("🔥 streak at $streak days")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onDone: (groupId: String) -> Unit,
    onJoin: (inviteCode: String) -> Unit,
    vm: NewGroupViewModel = hiltViewModel(),
) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var presetIndex by rememberSaveable { mutableIntStateOf(0) }
    var leastWins by rememberSaveable { mutableStateOf(false) }
    var stakeCents by rememberSaveable { mutableIntStateOf(2000) }
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(done) { done?.let(onDone) }

    AppScaffold("New group", onBack = onBack) {
        error?.let { Notice(it) }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(60) }, label = { Text("Group name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("Alert the group when someone passes")
        Preset.all.forEachIndexed { i, preset ->
            val selected = i == presetIndex
            OutlinedCard(
                onClick = { presetIndex = i },
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(preset.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            rules(preset.dailyAlertMinutes, preset.sessionAlertMinutes, preset.streakDays).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionHeader("Leaderboard")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Most scrolled first", "Least wins").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = leastWins == (i == 1),
                    onClick = { leastWins = i == 1 },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }

        SectionHeader("Weekly stake")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 500, 1000, 2000, 5000).forEach { cents ->
                FilterChip(
                    selected = stakeCents == cents,
                    onClick = { stakeCents = cents },
                    label = { Text(if (cents == 0) "No stake" else formatMoney(cents)) },
                )
            }
        }
        Text(
            if (stakeCents == 0) "Just bragging rights. You can't add a stake later."
            else "Whoever scrolls the most each week pays ${formatMoney(stakeCents)} into the pot. After a processing fee, " +
                "it's split evenly among everyone else. Friends agree to it when they join, and it can't be changed later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { vm.create(name, Preset.all[presetIndex], if (leastWins) "least" else "most", stakeCents) },
            enabled = !busy && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Create group") }

        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("or", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }

        SectionHeader("Join a friend's group")
        OutlinedTextField(
            value = code, onValueChange = { code = it }, label = { Text("Invite link or code") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onJoin(inviteCode(code)) },
            enabled = inviteCode(code).isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Join") }
    }
}

// ---------------------------------------------------------------- group detail

@HiltViewModel
class GroupViewModel @Inject constructor(
    state: SavedStateHandle,
    private val groups: Groups,
    account: Account,
) : ViewModel() {
    private val groupId: String = checkNotNull(state["id"])
    val group = MutableStateFlow<Group?>(null)
    val muted = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    val left = MutableStateFlow(false)
    val pot = MutableStateFlow<Pot?>(null)
    val history = MutableStateFlow<List<Pair<Settlement, String>>>(emptyList())
    val me = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            try {
                me.value = account.awaitUserId()
                val g = groups.get(groupId)
                group.value = g
                muted.value = groups.isMuted(groupId)
                if (g.stakeCents > 0) {
                    // ponytail: loaded once per visit; the leaderboard below is the live view.
                    pot.value = groups.pot(groupId, LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                    history.value = groups.settlements(groupId)
                }
            } catch (e: Exception) {
                error.value = true
            }
        }
    }

    fun setMuted(value: Boolean) = viewModelScope.launch {
        muted.value = value
        runCatching { groups.setMuted(groupId, value) }.onFailure { muted.value = !value }
    }

    fun leave() = viewModelScope.launch {
        runCatching { groups.leave(groupId) }.onSuccess { left.value = true }.onFailure { error.value = true }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupScreen(onBack: () -> Unit, onLeft: () -> Unit, vm: GroupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val group by vm.group.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val left by vm.left.collectAsStateWithLifecycle()
    val pot by vm.pot.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(left) { if (left) onLeft() }

    val g = group
    val invite = {
        if (g != null) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Join my Doomscroll group \"${g.name}\": ${g.inviteLink}")
            context.startActivity(Intent.createChooser(send, "Invite friends"))
        }
    }

    AppScaffold(
        title = g?.name.orEmpty(),
        onBack = onBack,
        actions = { if (g != null) IconButton(onClick = invite) { Icon(AppIcons.share, contentDescription = "Invite friends") } },
    ) {
        if (error) Notice("Something went wrong. Check your connection.")
        if (g == null) {
            if (!error) Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rules(g.dailyAlertMinutes, g.sessionAlertMinutes, g.streakDays).forEach { Pill(it) }
            if (g.stakeCents > 0) Pill("💸 ${formatMoney(g.stakeCents)} a week")
        }

        pot?.let { PotCard(it, history, me) }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) { Leaderboard(ranking = g.ranking) }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Invite friends", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (g.stakeCents > 0) "Friends see these rules and the ${formatMoney(g.stakeCents)} stake before they join."
                    else "Anyone with the link joins with these rules. Nothing to set up.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", style = MaterialTheme.typography.labelLarge)
                    Text(g.inviteCode, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                }
                Button(onClick = invite, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Share invite link")
                }
            }
        }

        ListItem(
            headlineContent = { Text("Mute alerts") },
            supportingContent = { Text("Stop notifications when someone here passes a limit.") },
            trailingContent = { Switch(checked = muted, onCheckedChange = vm::setMuted) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )

        TextButton(
            onClick = { confirmLeave = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Leave group") }
    }

    if (confirmLeave && g != null) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${g.name}?") },
            text = {
                Text(
                    "You'll need a new invite to come back." +
                        if (g.stakeCents > 0) " You're still in this week's pot, and past weeks still count." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave() }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PotCard(pot: Pot, history: List<Pair<Settlement, String>>, me: String?) {
    val colors = MaterialTheme.colorScheme
    val stake = formatMoney(pot.stakeCents)
    val others = pot.members - 1
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmojiBadge("💸")
                Column(Modifier.weight(1f)) {
                    Text("This week's pot", style = MaterialTheme.typography.titleLarge)
                    Text("Settles Monday", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text(stake, style = MaterialTheme.typography.headlineMedium, color = colors.primary)
            }

            // Who's on the hook if the week ended now.
            val (text, tone) = when {
                others < 1 -> "The pot starts once two people have been in the group since Monday." to
                    colors.onSurfaceVariant
                !pot.youIn -> "You're in the pot from next Monday. This week is between the others." to
                    colors.onSurfaceVariant
                pot.leaderId == null -> "Nobody's on the hook yet. Ties don't pay." to colors.tertiary
                pot.leaderId == me -> "You're on the hook. Put the phone down." to colors.error
                else -> "${pot.leaderName} is on the hook right now." to colors.primary
            }
            Text(text, style = MaterialTheme.typography.titleSmall, color = tone)

            if (others >= 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PotFigure("Paid in", stake, Modifier.weight(1f))
                    PotFigure("Processing fee", formatMoney(pot.feeCents), Modifier.weight(1f))
                    PotFigure(if (others == 1) "The other gets" else "Each of $others gets", formatMoney(pot.shareCents), Modifier.weight(1f))
                }
            }

            if (history.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                Text("Past weeks", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                history.forEach { (s, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${if (s.loserId == me) "You" else name} owed ${formatMoney(s.stakeCents)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Week of ${LocalDate.parse(s.weekStart).format(DateTimeFormatter.ofPattern("MMM d"))} · " +
                                    "${formatMoney(s.shareCents)} each",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (s.paidAt != null) Pill("Paid", colors.tertiaryContainer, colors.onTertiaryContainer)
                        else Pill("Due", colors.errorContainer, colors.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun PotFigure(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The code from a pasted invite link ("https://host/join/abc123/?x=1") or a bare code. */
internal fun inviteCode(text: String): String =
    text.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').trim()

package com.doomscroll.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.network.GroupPreview
import com.doomscroll.network.Groups
import com.doomscroll.ui.AppScaffold
import com.doomscroll.ui.Avatar
import com.doomscroll.ui.InfoRow
import com.doomscroll.ui.Notice
import com.doomscroll.ui.formatMoney
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JoinViewModel @Inject constructor(state: SavedStateHandle, private val groups: Groups) : ViewModel() {
    private val code: String = checkNotNull(state["code"])
    val preview = MutableStateFlow<GroupPreview?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    /** Id of the joined group. */
    val joined = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            runCatching { groups.preview(code) }
                .onSuccess { preview.value = it; if (it == null) error.value = "This invite doesn't work anymore." }
                .onFailure { error.value = "Couldn't load the invite. Check your connection." }
        }
    }

    /** Called only from the button that states the stake, so tapping it is the consent. */
    fun join() = viewModelScope.launch {
        val p = preview.value ?: return@launch
        busy.value = true
        runCatching { groups.join(code, acceptStake = p.stakeCents > 0) }
            .onSuccess { joined.value = it }
            .onFailure { error.value = "Couldn't join. Check your connection and try again." }
        busy.value = false
    }
}

/** Invite landing page for the code in the nav argument "code". */
@Composable
fun JoinScreen(onBack: () -> Unit, onJoined: (groupId: String) -> Unit, vm: JoinViewModel = hiltViewModel()) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val joined by vm.joined.collectAsStateWithLifecycle()

    LaunchedEffect(joined) { joined?.let(onJoined) }

    AppScaffold("Join group", onBack = onBack) {
        error?.let { Notice(it) }
        val p = preview ?: run {
            if (error == null) Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(p.name, 56.dp)
            Column {
                Text(p.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (p.members == 1) "1 member" else "${p.members} members",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (p.stakeCents > 0) {
            val stake = formatMoney(p.stakeCents)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("$stake weekly stake", style = MaterialTheme.typography.titleLarge)
                    InfoRow("💸", "If you scroll the most", "You pay $stake into the pot for that week (Monday to Sunday).")
                    InfoRow("🎁", "If someone else does", "Their $stake is split evenly among everyone else, after a processing fee.")
                    InfoRow("🤝", "Ties", "Nobody pays on a tie for the top spot.")
                }
            }
        }

        Button(onClick = vm::join, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (p.stakeCents > 0) "Join and accept the ${formatMoney(p.stakeCents)} stake" else "Join group")
        }
    }
}

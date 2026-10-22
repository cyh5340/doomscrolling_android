package com.doomscroll.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.doomscroll.network.Account
import com.doomscroll.network.Groups
import com.doomscroll.network.LeaderboardRow
import com.doomscroll.ui.Avatar
import com.doomscroll.ui.formatMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class Period(val title: String) {
    DAY("Today"), WEEK("This week");

    fun range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        DAY -> today to today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
    }
}

/** Null rows = loading. */
data class LeaderboardState(val period: Period, val rows: List<LeaderboardRow>?, val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(state: SavedStateHandle, groups: Groups, account: Account) : ViewModel() {
    private val groupId: String = checkNotNull(state["id"])
    val period = MutableStateFlow(Period.DAY)
    val me = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { me.value = account.awaitUserId() }
    }

    val state = period.flatMapLatest { p ->
        val (from, to) = p.range(LocalDate.now())
        groups.leaderboard(groupId, from, to)
            .map { LeaderboardState(p, it) }
            .catch { emit(LeaderboardState(p, emptyList(), error = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeaderboardState(Period.DAY, null))
}

/**
 * Live leaderboard for the group in the nav argument "id".
 * [ranking] is the group setting: "most" = most doomscrolled first, "least" = hall of fame.
 */
@Composable
fun Leaderboard(ranking: String, vm: LeaderboardViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val least = ranking == "least"
    val rows = state.rows?.let { rows -> if (least) rows.sortedBy { it.minutes } else rows }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(if (least) "Hall of fame" else "Most doomscrolled", style = MaterialTheme.typography.titleLarge)
            Text(
                if (least) "Least time in social apps wins." else "Top spot is not the one you want.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == state.period,
                    onClick = { vm.period.value = p },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                ) { Text(p.title) }
            }
        }
        when {
            rows == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            state.error -> Text("Couldn't load the leaderboard.", color = MaterialTheme.colorScheme.error)
            rows.isEmpty() -> Text("Nobody here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val max = rows.maxOf { it.minutes }.coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEachIndexed { i, row ->
                        MemberRow(i + 1, row, isMe = row.userId == me, share = row.minutes.toFloat() / max, least = least)
                    }
                }
            }
        }
    }
}

private val medals = listOf(Color(0xFFF5B83D), Color(0xFFC3CAD6), Color(0xFFD08A5A))

@Composable
private fun MemberRow(rank: Int, row: LeaderboardRow, isMe: Boolean, share: Float, least: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (isMe) colors.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(26.dp).background(medals.getOrNull(rank - 1) ?: Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$rank",
                style = MaterialTheme.typography.labelLarge,
                color = if (rank <= medals.size) Color(0xFF14151A) else colors.onSurfaceVariant,
            )
        }
        // ponytail: initials avatar; load photoUrl with an image library once Google sign-in exists.
        Avatar(row.displayName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.displayName,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isMe) {
                    Text(
                        "YOU",
                        Modifier.background(colors.secondary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSecondary,
                    )
                }
            }
            // Bar relative to the group's top scroller.
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.surfaceContainerHighest, CircleShape)) {
                Box(
                    Modifier.fillMaxWidth(share).height(6.dp)
                        .background(if (least) colors.tertiary else colors.primary, CircleShape),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatMinutes(row.minutes), style = MaterialTheme.typography.titleSmall)
            if (row.streak > 0) {
                Text("🔥 ${row.streak}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
        }
    }
}

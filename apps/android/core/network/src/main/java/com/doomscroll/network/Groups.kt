package com.doomscroll.network

import com.doomscroll.data.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Group(
    val id: String,
    val name: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("invite_code") val inviteCode: String,
    val label: String,
    @SerialName("daily_alert_minutes") val dailyAlertMinutes: Int,
    @SerialName("session_alert_minutes") val sessionAlertMinutes: Int? = null,
    @SerialName("streak_days") val streakDays: Int,
    val ranking: String,
    /** Weekly stake, paid by the group's top doomscroller. 0 = no stake. */
    @SerialName("stake_cents") val stakeCents: Int = 0,
) {
    val inviteLink: String get() = "https://${BuildConfig.INVITE_HOST}/join/$inviteCode"
}

@Serializable
data class LeaderboardRow(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    val minutes: Int,
    val streak: Int,
)

/** What an invite leads to, shown before joining so people agree to the stake. */
@Serializable
data class GroupPreview(
    val id: String,
    val name: String,
    @SerialName("stake_cents") val stakeCents: Int,
    val members: Int,
)

/**
 * This week's pot. The server decides the fee, the split and who's on the hook (null: nobody, or a tie).
 * [members] counts who's in this week's pot: people who joined mid-week start the next Monday ([youIn] false).
 */
@Serializable
data class Pot(
    @SerialName("stake_cents") val stakeCents: Int,
    @SerialName("fee_cents") val feeCents: Int,
    @SerialName("share_cents") val shareCents: Int,
    val members: Int,
    @SerialName("leader_id") val leaderId: String? = null,
    @SerialName("leader_name") val leaderName: String? = null,
    @SerialName("you_in") val youIn: Boolean = true,
)

/** A settled week. Ledger only until payments exist: paidAt is set once the money is collected. */
@Serializable
data class Settlement(
    @SerialName("week_start") val weekStart: String,
    @SerialName("loser_id") val loserId: String? = null,
    @SerialName("stake_cents") val stakeCents: Int,
    @SerialName("share_cents") val shareCents: Int,
    @SerialName("paid_at") val paidAt: String? = null,
)

@Serializable
private data class ProfileName(val id: String, @SerialName("display_name") val displayName: String)

data class Preset(val name: String, val dailyAlertMinutes: Int, val sessionAlertMinutes: Int?, val streakDays: Int) {
    companion object {
        val all = listOf(
            Preset("Balanced", 60, 45, 3),
            Preset("Strict", 30, 15, 3),
            Preset("Relaxed", 120, null, 5),
        )
    }
}

@Serializable
private data class Membership(val muted: Boolean)

@Singleton
class Groups @Inject constructor(
    private val supabase: SupabaseClient,
    private val account: Account,
    private val settings: Settings,
) {
    /** The user's groups. Also caches their session limits for the usage worker. */
    suspend fun mine(): List<Group> =
        supabase.from("groups").select().decodeList<Group>().also { groups ->
            settings.setSessionLimits(groups.mapNotNull { it.sessionAlertMinutes }.toSet())
        }

    suspend fun get(id: String): Group =
        supabase.from("groups").select { filter { eq("id", id) } }.decodeSingle()

    suspend fun create(name: String, preset: Preset, ranking: String, stakeCents: Int): Group =
        supabase.postgrest.rpc(
            "create_group",
            buildJsonObject {
                put("name", name)
                put("daily_alert_minutes", preset.dailyAlertMinutes)
                put("session_alert_minutes", preset.sessionAlertMinutes)
                put("streak_days", preset.streakDays)
                put("ranking", ranking)
                put("stake_cents", stakeCents)
            },
        ).decodeAs<Group>().also { mine() }

    /** Null when the code doesn't match a group. */
    suspend fun preview(code: String): GroupPreview? =
        supabase.postgrest.rpc("group_preview", buildJsonObject { put("code", code) }).decodeList<GroupPreview>().firstOrNull()

    /** Joins by invite code and returns the group id. [acceptStake] is the user's consent to the group's stake. */
    suspend fun join(code: String, acceptStake: Boolean): String =
        supabase.postgrest.rpc(
            "join_group",
            buildJsonObject {
                put("code", code)
                put("accept_stake", acceptStake)
            },
        ).decodeAs<String>().also { mine() }

    suspend fun pot(groupId: String, weekStart: LocalDate): Pot? =
        supabase.postgrest.rpc(
            "pot",
            buildJsonObject {
                put("gid", groupId)
                put("week_start", weekStart.toString())
            },
        ).decodeList<Pot>().firstOrNull()

    /** Recent settled weeks, newest first, with the name of who owed. */
    suspend fun settlements(groupId: String): List<Pair<Settlement, String>> {
        val rows = supabase.from("settlements").select {
            filter { eq("group_id", groupId) }
            order("week_start", Order.DESCENDING)
            limit(8)
        }.decodeList<Settlement>()
        val ids = rows.mapNotNull { it.loserId }.distinct()
        val names = if (ids.isEmpty()) emptyMap() else supabase.from("profiles").select {
            filter { isIn("id", ids) }
        }.decodeList<ProfileName>().associate { it.id to it.displayName }
        return rows.map { it to (it.loserId?.let(names::get) ?: "A former member") }
    }

    suspend fun leave(groupId: String) {
        val uid = account.awaitUserId() ?: return
        supabase.from("group_members").delete { filter { eq("group_id", groupId); eq("user_id", uid) } }
        mine()
    }

    suspend fun isMuted(groupId: String): Boolean {
        val uid = account.awaitUserId() ?: return false
        return supabase.from("group_members").select(Columns.list("muted")) {
            filter { eq("group_id", groupId); eq("user_id", uid) }
        }.decodeSingle<Membership>().muted
    }

    suspend fun setMuted(groupId: String, muted: Boolean) {
        val uid = account.awaitUserId() ?: return
        supabase.from("group_members").update({ set("muted", muted) }) {
            filter { eq("group_id", groupId); eq("user_id", uid) }
        }
    }

    private suspend fun fetchLeaderboard(groupId: String, from: LocalDate, to: LocalDate): List<LeaderboardRow> =
        supabase.postgrest.rpc(
            "leaderboard",
            buildJsonObject {
                put("gid", groupId)
                put("from_date", from.toString())
                put("to_date", to.toString())
            },
        ).decodeList()

    /** Leaderboard rows, re-fetched live whenever a group-mate's daily usage changes. */
    fun leaderboard(groupId: String, from: LocalDate, to: LocalDate): Flow<List<LeaderboardRow>> = channelFlow {
        val channel = supabase.channel("usage-${UUID.randomUUID()}")
        // RLS limits these changes to the user and their group-mates.
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "daily_usage" }
        launch { changes.collect { send(fetchLeaderboard(groupId, from, to)) } }
        channel.subscribe()
        send(fetchLeaderboard(groupId, from, to))
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { supabase.realtime.removeChannel(channel) }
        }
    }
}

package com.doomscroll.network

import android.content.Context
import androidx.work.WorkManager
import com.doomscroll.data.AppDb
import com.doomscroll.data.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun supabase(): SupabaseClient = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
        install(Auth)
        install(Postgrest)
        install(Realtime)
    }
}

@Serializable
private data class PushToken(@SerialName("user_id") val userId: String, val token: String)

@Singleton
class Account @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    private val db: AppDb,
    private val settings: Settings,
) {
    val userId: String? get() = supabase.auth.currentUserOrNull()?.id

    /** Waits for the stored session to load, then returns the user id, if signed in. */
    suspend fun awaitUserId(): String? {
        supabase.auth.awaitInitialization()
        return userId
    }

    // ponytail: anonymous accounts only; Google sign-in is a TODO (see TODO.md).
    suspend fun signIn(displayName: String) {
        val uid = awaitUserId()
        if (uid == null) {
            supabase.auth.signInAnonymously(data = buildJsonObject { put("display_name", displayName) })
        } else {
            supabase.from("profiles").update({ set("display_name", displayName) }) { filter { eq("id", uid) } }
        }
    }

    /** No-op until a google-services.json is added (see TODO.md). */
    suspend fun syncPushToken() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        savePushToken(FirebaseMessaging.getInstance().token.await())
    }

    suspend fun savePushToken(token: String) {
        val uid = awaitUserId() ?: return
        supabase.from("push_tokens").upsert(PushToken(uid, token))
    }

    /** Deletes the account and all its data on the server, then everything local. */
    suspend fun deleteAccount() {
        supabase.postgrest.rpc("delete_account")
        supabase.auth.clearSession()
        WorkManager.getInstance(context).cancelAllWork()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        settings.clear()
    }
}

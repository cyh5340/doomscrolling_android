package com.doomscroll.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class Settings @Inject constructor(@ApplicationContext private val context: Context) {
    private val cursorKey = longPreferencesKey("lastProcessedTimestamp")
    private val sessionLimitsKey = stringSetPreferencesKey("sessionLimits")
    private val pendingInviteKey = stringPreferencesKey("pendingInvite")
    private val onboardedKey = booleanPreferencesKey("onboarded")

    /** 0 = never processed. */
    suspend fun cursor(): Long = context.dataStore.data.first()[cursorKey] ?: 0L

    suspend fun setCursor(ms: Long) {
        context.dataStore.edit { it[cursorKey] = ms }
    }

    /** Distinct session limits of the user's groups, cached so tracking works offline. */
    suspend fun sessionLimits(): Set<Int> =
        context.dataStore.data.first()[sessionLimitsKey].orEmpty().map(String::toInt).toSet()

    suspend fun setSessionLimits(limits: Set<Int>) {
        context.dataStore.edit { it[sessionLimitsKey] = limits.map(Int::toString).toSet() }
    }

    /** Invite code from a link, kept until onboarding finishes and the group is joined. */
    val pendingInvite: Flow<String?> = context.dataStore.data.map { it[pendingInviteKey] }

    suspend fun setPendingInvite(code: String?) {
        context.dataStore.edit { if (code == null) it.remove(pendingInviteKey) else it[pendingInviteKey] = code }
    }

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[onboardedKey] ?: false }

    suspend fun setOnboarded() {
        context.dataStore.edit { it[onboardedKey] = true }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

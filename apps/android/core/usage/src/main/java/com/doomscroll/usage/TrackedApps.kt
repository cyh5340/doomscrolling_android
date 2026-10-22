package com.doomscroll.usage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Package name -> label. Package names stay on the device; only labels are reported. */
@Singleton
class TrackedApps @Inject constructor(@ApplicationContext private val context: Context) {
    // ponytail: bundled asset; swap for Firestore remote config later.
    private val labels: Map<String, String> by lazy {
        val json = JSONObject(context.assets.open("tracked_apps.json").bufferedReader().use { it.readText() })
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    fun labelFor(packageName: String): String? = labels[packageName]

    fun installed(): Map<String, String> = labels.filterKeys { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
}

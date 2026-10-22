package com.doomscroll.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class UsageEventReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun read(startMs: Long, endMs: Long): List<UsageEvent> {
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(startMs, endMs)
        val event = UsageEvents.Event()
        val out = mutableListOf<UsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventType.STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventType.SHUTDOWN
                else -> continue
            }
            out += UsageEvent(event.timeStamp, type, event.packageName)
        }
        return out
    }
}

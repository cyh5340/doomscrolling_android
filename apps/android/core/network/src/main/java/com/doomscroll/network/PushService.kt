package com.doomscroll.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Intent extra carrying the group to open. FCM puts message data into the launch intent under the same key. */
const val EXTRA_GROUP_ID = "groupId"
private const val CHANNEL_ID = "alerts"

fun createAlertChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Friend alerts", NotificationManager.IMPORTANCE_HIGH),
    )
}

@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var account: Account
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { account.savePushToken(token) } }
    }

    // Only called while the app is in the foreground; otherwise the system shows the notification.
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val groupId = message.data[EXTRA_GROUP_ID]
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.putExtra(EXTRA_GROUP_ID, groupId)
        val tap = PendingIntent.getActivity(
            this, groupId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(NotificationManager::class.java).notify(
            message.messageId.hashCode(),
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

package com.example.opendash.media

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class OpenDashNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (notification.category != Notification.CATEGORY_CALL) return

        val type = if (android.os.Build.VERSION.SDK_INT >= 31) {
            notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        } else {
            1
        }
        val incoming = type == 1
        val active = type == 2 || type == 3
        if (!incoming && !active) {
            CallInfoProvider.update(null)
            return
        }

        val (answer, decline) = extractActions(notification, incoming)
        val caller = callerName(notification).ifBlank { if (incoming) "Call" else "On call" }
        CallInfoProvider.update(IncomingCall(caller, incoming, answer, decline))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.notification?.category == Notification.CATEGORY_CALL) {
            CallInfoProvider.update(null)
        }
    }

    private fun extractActions(
        notification: Notification,
        incoming: Boolean,
    ): Pair<PendingIntent?, PendingIntent?> {
        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        val actions = notification.actions
        actions?.forEach { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (title.contains("answer") || title.contains("accept")) ->
                    answer = action.actionIntent
                decline == null && (
                    title.contains("decline") || title.contains("reject") ||
                        title.contains("dismiss") || title.contains("hang") ||
                        title.contains("end") || title.contains("ignore")
                    ) -> decline = action.actionIntent
            }
        }
        if (incoming && answer == null && decline == null && actions?.size == 2) {
            decline = actions[0].actionIntent
            answer = actions[1].actionIntent
        }
        return answer to decline
    }

    private fun callerName(notification: Notification): String {
        val extras = notification.extras
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }
}

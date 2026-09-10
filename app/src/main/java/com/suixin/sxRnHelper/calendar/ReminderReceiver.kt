package com.suixin.sxRnHelper.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suixin.sx2libra.R

internal class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REMINDER -> showReminder(context, intent)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> reschedulePendingReminders(context)
        }
    }

    private fun showReminder(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, INVALID_EVENT_ID)
        if (eventId == INVALID_EVENT_ID) return

        val appContext = context.applicationContext
        ensureNotificationChannel(appContext)
        try {
            val notifications = NotificationManagerCompat.from(appContext)
            if (notifications.areNotificationsEnabled()) {
                val details = formatEventDetails(
                    title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE),
                    description = intent.getStringExtra(ReminderScheduler.EXTRA_DESCRIPTION),
                    fallback = appContext.getString(R.string.calendar_event_no_title),
                )
                notifications.notify(
                    ReminderScheduler.REMINDER_TAG,
                    eventId.hashCode(),
                    NotificationCompat.Builder(
                        appContext,
                        ReminderScheduler.REMINDER_NOTIFICATION_CHANNEL_ID,
                    )
                        .setSmallIcon(R.drawable.ic_message)
                        .setContentTitle(appContext.getString(R.string.calendar_reminder_title))
                        .setContentText(details)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        } catch (_: SecurityException) {
            // Notification permission may be revoked after the alarm was scheduled.
        } finally {
            CalendarEventStore(appContext).removePendingReminder(eventId)
        }
    }

    private fun reschedulePendingReminders(context: Context) {
        val appContext = context.applicationContext
        val eventStore = CalendarEventStore(appContext)
        ReminderScheduler(appContext).reschedule(eventStore.loadPendingReminders())
        if (eventStore.monitoringEnabled) {
            try {
                CalendarMonitorService.start(appContext)
            } catch (_: RuntimeException) {
                // The system may reject a background foreground-service start.
            }
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                ReminderScheduler.REMINDER_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.calendar_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.calendar_notification_channel_description)
            },
        )
    }

    private companion object {
        const val INVALID_EVENT_ID = Long.MIN_VALUE
        const val ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

package com.suixin.sxRnHelper.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

internal class ReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(
        appContext.getSystemService(AlarmManager::class.java),
    )

    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        alarmManager.canScheduleExactAlarms()

    fun schedule(record: CalendarEventRecord) {
        check(shouldScheduleReminder(record)) { "Event cannot be scheduled as a reminder" }
        check(canScheduleExactAlarms()) { "Exact alarm permission is not granted" }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            requireNotNull(record.startMillis),
            pendingIntent(record),
        )
    }

    fun reschedule(records: List<CalendarEventRecord>): Boolean {
        if (!canScheduleExactAlarms()) return false
        records.filter(::shouldScheduleReminder).forEach { record ->
            try {
                schedule(record)
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalStateException) {
                return false
            }
        }
        return true
    }

    fun cancel(eventId: Long) {
        alarmManager.cancel(pendingIntent(eventId))
    }

    private fun pendingIntent(record: CalendarEventRecord): PendingIntent =
        pendingIntent(reminderIntent(record))

    private fun pendingIntent(eventId: Long): PendingIntent = pendingIntent(reminderIntent(eventId))

    private fun pendingIntent(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderIntent(record: CalendarEventRecord): Intent = reminderIntent(record.eventId)
        .putExtra(EXTRA_TITLE, record.title)
        .putExtra(EXTRA_DESCRIPTION, record.description)

    private fun reminderIntent(eventId: Long): Intent = Intent(appContext, ReminderReceiver::class.java)
        .setAction(ACTION_REMINDER)
        .setData(Uri.parse("$REMINDER_URI_PREFIX$eventId"))
        .putExtra(EXTRA_EVENT_ID, eventId)

    internal companion object {
        const val ACTION_REMINDER = "com.suixin.sxRnHelper.action.REMINDER"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESCRIPTION = "description"
        const val REMINDER_NOTIFICATION_CHANNEL_ID = "calendar_reminders"
        const val REMINDER_ADDED_TAG = "calendar_reminder_added"
        const val REMINDER_TAG = "calendar_reminder"

        private const val REMINDER_URI_PREFIX = "sxRnHelper://reminder/"
    }
}

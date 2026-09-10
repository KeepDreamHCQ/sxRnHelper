package com.suixin.sxRnHelper.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.suixin.sx2libra.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class CalendarMonitorService : JobService() {
    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope
        scope.launch {
            try {
                runMonitorJob()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                CalendarEventStore(this@CalendarMonitorService).monitoringEnabled = false
            } finally {
                if (jobScope === scope) {
                    if (CalendarEventStore(this@CalendarMonitorService).monitoringEnabled) {
                        scheduleContentJob(this@CalendarMonitorService)
                    }
                    jobScope = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        jobScope = null
        return true
    }

    override fun onDestroy() {
        jobScope?.cancel()
        jobScope = null
        super.onDestroy()
    }

    private fun runMonitorJob() {
        val eventStore = CalendarEventStore(this)
        if (!eventStore.monitoringEnabled) return

        val reminderScheduler = ReminderScheduler(this)
        if (!canMonitor(reminderScheduler)) {
            eventStore.monitoringEnabled = false
            return
        }

        val eventMonitor = CalendarEventMonitor(contentResolver)
        if (!eventStore.hasMonitoringBaseline) {
            eventStore.saveMonitoringBaseline(eventMonitor.currentEventIds())
            return
        }

        val result = eventMonitor.scan(eventStore.loadMonitoringBaseline())
        result.newRecords.forEach { record ->
            onNewCalendarEvent(record, eventStore, reminderScheduler)
        }
        eventStore.saveMonitoringBaseline(result.eventIds)
    }

    private fun canMonitor(reminderScheduler: ReminderScheduler): Boolean =
        hasCalendarPermission() &&
            hasNotificationPermission() &&
            reminderScheduler.canScheduleExactAlarms()

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun onNewCalendarEvent(
        record: CalendarEventRecord,
        eventStore: CalendarEventStore,
        reminderScheduler: ReminderScheduler,
    ) {
        eventStore.addRecord(record)
        if (!shouldScheduleReminder(record)) return

        try {
            reminderScheduler.schedule(record)
        } catch (_: SecurityException) {
            eventStore.monitoringEnabled = false
            return
        } catch (_: IllegalStateException) {
            eventStore.monitoringEnabled = false
            return
        }
        if (!eventStore.addPendingReminder(record)) {
            reminderScheduler.cancel(record.eventId)
            return
        }
        if (!sendReminderAddedNotification(record, eventStore)) return
        deleteCalendarEvent(record.eventId, eventStore)
    }

    private fun sendReminderAddedNotification(
        record: CalendarEventRecord,
        eventStore: CalendarEventStore,
    ): Boolean {
        ensureReminderNotificationChannel()
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) {
            eventStore.monitoringEnabled = false
            return false
        }
        val content = getString(
            R.string.calendar_reminder_added_content,
            formatNotificationTime(record),
            formatEventDetails(
                title = record.title,
                description = record.description,
                fallback = getString(R.string.calendar_event_no_title),
            ),
        )
        return try {
            notifications.notify(
                ReminderScheduler.REMINDER_ADDED_TAG,
                record.eventId.hashCode(),
                NotificationCompat.Builder(this, ReminderScheduler.REMINDER_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_message)
                    .setContentTitle(getString(R.string.calendar_reminder_added_title))
                    .setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .build(),
            )
            true
        } catch (_: SecurityException) {
            eventStore.monitoringEnabled = false
            false
        }
    }

    private fun deleteCalendarEvent(eventId: Long, eventStore: CalendarEventStore) {
        try {
            contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            )
        } catch (_: SecurityException) {
            eventStore.monitoringEnabled = false
        } catch (_: RuntimeException) {
            // Keep the scheduled reminder and source event if the provider rejects deletion.
        }
    }

    private fun ensureReminderNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                ReminderScheduler.REMINDER_NOTIFICATION_CHANNEL_ID,
                getString(R.string.calendar_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.calendar_notification_channel_description)
            },
        )
    }

    private fun formatNotificationTime(record: CalendarEventRecord): String {
        val startMillis = record.startMillis
            ?: return getString(R.string.calendar_event_time_unavailable)
        return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).apply {
            timeZone = timeZoneFor(record.eventTimeZone)
        }.format(Date(startMillis))
    }

    private fun timeZoneFor(id: String?): TimeZone = id
        ?.takeIf { it.isNotBlank() }
        ?.let(TimeZone::getTimeZone)
        ?: TimeZone.getDefault()

    internal companion object {
        private const val MONITOR_JOB_ID = 1001
        private const val SCAN_DEBOUNCE_MILLIS = 250L
        private const val SCAN_MAX_DELAY_MILLIS = 1_000L

        fun start(context: Context) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(MONITOR_JOB_ID) == null) {
                scheduler.schedule(
                    JobInfo.Builder(
                        MONITOR_JOB_ID,
                        ComponentName(appContext, CalendarMonitorService::class.java),
                    )
                        .setMinimumLatency(0)
                        .build(),
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.getSystemService(JobScheduler::class.java).cancel(MONITOR_JOB_ID)
            CalendarEventStore(appContext).clearMonitoringBaseline()
        }

        private fun scheduleContentJob(context: Context) {
            val appContext = context.applicationContext
            appContext.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(
                    MONITOR_JOB_ID,
                    ComponentName(appContext, CalendarMonitorService::class.java),
                )
                    .addTriggerContentUri(
                        JobInfo.TriggerContentUri(
                            CalendarContract.Events.CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                        ),
                    )
                    .addTriggerContentUri(
                        JobInfo.TriggerContentUri(
                            CalendarContract.Reminders.CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                        ),
                    )
                    .setTriggerContentUpdateDelay(SCAN_DEBOUNCE_MILLIS)
                    .setTriggerContentMaxDelay(SCAN_MAX_DELAY_MILLIS)
                    .build(),
            )
        }
    }
}

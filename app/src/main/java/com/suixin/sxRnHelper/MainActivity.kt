package com.suixin.sxRnHelper

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suixin.sx2libra.R
import com.suixin.sxRnHelper.calendar.CalendarEventAdapter
import com.suixin.sxRnHelper.calendar.CalendarEventRecord
import com.suixin.sxRnHelper.calendar.CalendarEventStore
import com.suixin.sxRnHelper.calendar.CalendarMonitorService
import com.suixin.sxRnHelper.calendar.ReminderScheduler

/** Root shell: displays and monitors newly-created calendar reminders. */
class MainActivity : AppCompatActivity() {
    private lateinit var monitorCheckbox: MaterialCheckBox
    private lateinit var monitorStatus: TextView
    private lateinit var eventsEmpty: TextView
    private lateinit var eventsList: RecyclerView
    private lateinit var eventAdapter: CalendarEventAdapter
    private lateinit var eventStore: CalendarEventStore
    private lateinit var reminderScheduler: ReminderScheduler
    private var updatingCheckbox = false
    private var requestingExactAlarmPermission = false

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
        ) {
            requestOrEnableCalendarMonitoring()
        } else {
            disableCalendarMonitoring(R.string.calendar_permission_denied)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableCalendarMonitoring()
        } else {
            disableCalendarMonitoring(R.string.notification_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        eventStore = CalendarEventStore(this)
        reminderScheduler = ReminderScheduler(this)
        monitorCheckbox = findViewById(R.id.calendar_monitor_checkbox)
        monitorStatus = findViewById(R.id.calendar_monitor_status)
        eventsEmpty = findViewById(R.id.calendar_events_empty)
        eventsList = findViewById(R.id.calendar_events_list)
        eventAdapter = CalendarEventAdapter(::confirmDeleteRecord)
        eventsList.layoutManager = LinearLayoutManager(this)
        eventsList.adapter = eventAdapter
        renderRecords(eventStore.loadRecords())

        monitorCheckbox.isChecked = eventStore.monitoringEnabled &&
            hasCalendarPermission() &&
            hasNotificationPermission() &&
            reminderScheduler.canScheduleExactAlarms()
        monitorCheckbox.setOnCheckedChangeListener { _, checked ->
            if (updatingCheckbox) return@setOnCheckedChangeListener
            if (checked) requestOrEnableCalendarMonitoring() else disableCalendarMonitoring()
        }

        updateMonitorStatus()
    }

    override fun onStart() {
        super.onStart()
        if (eventStore.monitoringEnabled) {
            when {
                !hasCalendarPermission() ->
                    disableCalendarMonitoring(R.string.calendar_permission_required)
                !hasNotificationPermission() ->
                    disableCalendarMonitoring(R.string.notification_permission_required)
                !reminderScheduler.canScheduleExactAlarms() ->
                    disableCalendarMonitoring(R.string.exact_alarm_permission_required)
                else -> {
                    setMonitorCheckbox(true)
                    if (!reminderScheduler.reschedule(eventStore.loadPendingReminders())) {
                        disableCalendarMonitoring(R.string.exact_alarm_permission_required)
                        return
                    }
                    CalendarMonitorService.start(this)
                    monitorStatus.setText(R.string.calendar_monitor_status_listening)
                }
            }
        } else {
            updateMonitorStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecords(eventStore.loadRecords())
        if (!requestingExactAlarmPermission) return
        requestingExactAlarmPermission = false
        if (hasCalendarPermission() && hasNotificationPermission() &&
            reminderScheduler.canScheduleExactAlarms()
        ) {
            enableCalendarMonitoring()
        } else {
            disableCalendarMonitoring(R.string.exact_alarm_permission_required)
        }
    }

    private fun requestOrEnableCalendarMonitoring() {
        if (!hasCalendarPermission()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) ||
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALENDAR)
            ) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.calendar_permission_rationale_title)
                    .setMessage(R.string.calendar_permission_rationale_message)
                    .setNegativeButton(R.string.calendar_permission_cancel) { _, _ ->
                        disableCalendarMonitoring(R.string.calendar_permission_required)
                    }
                    .setPositiveButton(R.string.calendar_permission_allow) { _, _ ->
                        calendarPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    }
                    .show()
            } else {
                calendarPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    ),
                )
            }
            return
        }
        if (!hasNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.notification_permission_rationale_title)
                    .setMessage(R.string.notification_permission_rationale_message)
                    .setNegativeButton(R.string.calendar_permission_cancel) { _, _ ->
                        disableCalendarMonitoring(R.string.notification_permission_required)
                    }
                    .setPositiveButton(R.string.calendar_permission_allow) { _, _ ->
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .show()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        if (!reminderScheduler.canScheduleExactAlarms()) {
            requestExactAlarmPermission()
            return
        }
        enableCalendarMonitoring()
    }

    private fun enableCalendarMonitoring() {
        if (!hasCalendarPermission()) {
            disableCalendarMonitoring(R.string.calendar_permission_required)
            return
        }
        if (!hasNotificationPermission()) {
            disableCalendarMonitoring(R.string.notification_permission_required)
            return
        }
        if (!reminderScheduler.canScheduleExactAlarms()) {
            requestExactAlarmPermission()
            return
        }
        eventStore.monitoringEnabled = true
        setMonitorCheckbox(true)
        if (!reminderScheduler.reschedule(eventStore.loadPendingReminders())) {
            disableCalendarMonitoring(R.string.exact_alarm_permission_required)
            return
        }
        CalendarMonitorService.start(this)
        monitorStatus.setText(R.string.calendar_monitor_status_listening)
    }

    private fun requestExactAlarmPermission() {
        eventStore.monitoringEnabled = false
        CalendarMonitorService.stop(this)
        setMonitorCheckbox(false)
        monitorStatus.setText(R.string.exact_alarm_permission_required)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enableCalendarMonitoring()
            return
        }
        requestingExactAlarmPermission = true
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            requestingExactAlarmPermission = false
            disableCalendarMonitoring(R.string.exact_alarm_permission_unavailable)
        }
    }

    private fun disableCalendarMonitoring(statusResId: Int? = null) {
        eventStore.monitoringEnabled = false
        CalendarMonitorService.stop(this)
        setMonitorCheckbox(false)
        if (statusResId != null) monitorStatus.setText(statusResId) else updateMonitorStatus()
    }

    private fun renderRecords(records: List<CalendarEventRecord>) {
        eventAdapter.submitList(records)
        eventsEmpty.isVisible = records.isEmpty()
        eventsList.isVisible = records.isNotEmpty()
    }

    private fun confirmDeleteRecord(record: CalendarEventRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calendar_event_delete_title)
            .setMessage(R.string.calendar_event_delete_message)
            .setNegativeButton(R.string.calendar_permission_cancel, null)
            .setPositiveButton(R.string.calendar_event_delete) { _, _ ->
                val records = eventStore.removeRecord(record.eventId)
                reminderScheduler.cancel(record.eventId)
                renderRecords(records)
            }
            .show()
    }

    private fun updateMonitorStatus() {
        monitorStatus.setText(
            when {
                !hasCalendarPermission() -> R.string.calendar_permission_required
                !hasNotificationPermission() -> R.string.notification_permission_required
                !reminderScheduler.canScheduleExactAlarms() ->
                    R.string.exact_alarm_permission_required
                eventStore.monitoringEnabled -> R.string.calendar_monitor_status_listening
                else -> R.string.calendar_monitor_status_disabled
            },
        )
    }

    private fun setMonitorCheckbox(checked: Boolean) {
        if (monitorCheckbox.isChecked == checked) return
        updatingCheckbox = true
        monitorCheckbox.isChecked = checked
        updatingCheckbox = false
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

}

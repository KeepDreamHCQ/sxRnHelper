package com.suixin.sxRnHelper.calendar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.suixin.sx2libra.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class CalendarEventAdapter(
    private val onDelete: (CalendarEventRecord) -> Unit,
) : ListAdapter<CalendarEventRecord, CalendarEventAdapter.ViewHolder>(
    DIFF_CALLBACK,
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.item_calendar_event,
            parent,
            false,
        ),
        onDelete,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    internal class ViewHolder(
        itemView: View,
        private val onDelete: (CalendarEventRecord) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.calendar_event_title)
        private val time: TextView = itemView.findViewById(R.id.calendar_event_time)
        private val description: TextView = itemView.findViewById(R.id.calendar_event_description)
        private val reminders: TextView = itemView.findViewById(R.id.calendar_event_reminders)
        private val delete: View = itemView.findViewById(R.id.calendar_event_delete)

        fun bind(record: CalendarEventRecord) {
            val context = itemView.context
            delete.setOnClickListener { onDelete(record) }
            title.text = record.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.calendar_event_no_title)
            time.text = context.getString(
                R.string.calendar_event_time,
                formatStartTime(record),
            )
            record.description?.takeIf { it.isNotBlank() }?.let {
                description.text = context.getString(R.string.calendar_event_description, it)
                description.isVisible = true
            } ?: run {
                description.text = null
                description.isVisible = false
            }
            reminders.text = if (record.reminders.isEmpty()) {
                context.getString(R.string.calendar_event_no_reminder)
            } else {
                context.getString(
                    R.string.calendar_event_reminders,
                    record.reminders.joinToString("\n") { formatReminder(context, it) },
                )
            }
        }

        private fun formatStartTime(record: CalendarEventRecord): String {
            val context = itemView.context
            val startMillis = record.startMillis
                ?: return context.getString(R.string.calendar_event_time_unavailable)
            val formatter = if (record.allDay) {
                DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            } else {
                DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                    Locale.getDefault(),
                )
            }.apply {
                timeZone = timeZoneFor(record.eventTimeZone)
            }
            return formatter.format(Date(startMillis))
        }

        private fun formatReminder(
            context: android.content.Context,
            reminder: CalendarReminder,
        ): String = context.getString(
            R.string.calendar_reminder_format,
            reminder.minutes,
            when (reminder.method) {
                android.provider.CalendarContract.Reminders.METHOD_ALERT ->
                    context.getString(R.string.calendar_reminder_method_alert)
                android.provider.CalendarContract.Reminders.METHOD_DEFAULT ->
                    context.getString(R.string.calendar_reminder_method_default)
                android.provider.CalendarContract.Reminders.METHOD_EMAIL ->
                    context.getString(R.string.calendar_reminder_method_email)
                android.provider.CalendarContract.Reminders.METHOD_SMS ->
                    context.getString(R.string.calendar_reminder_method_sms)
                android.provider.CalendarContract.Reminders.METHOD_ALARM ->
                    context.getString(R.string.calendar_reminder_method_alarm)
                else -> context.getString(
                    R.string.calendar_reminder_method_unknown,
                    reminder.method,
                )
            },
        )

        private fun timeZoneFor(id: String?): TimeZone = id
            ?.takeIf { it.isNotBlank() }
            ?.let(TimeZone::getTimeZone)
            ?: TimeZone.getDefault()
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CalendarEventRecord>() {
            override fun areItemsTheSame(
                oldItem: CalendarEventRecord,
                newItem: CalendarEventRecord,
            ): Boolean = oldItem.eventId == newItem.eventId

            override fun areContentsTheSame(
                oldItem: CalendarEventRecord,
                newItem: CalendarEventRecord,
            ): Boolean = oldItem == newItem
        }
    }
}

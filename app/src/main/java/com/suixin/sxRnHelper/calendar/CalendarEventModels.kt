package com.suixin.sxRnHelper.calendar

internal data class CalendarReminder(
    val minutes: Int,
    val method: Int,
)

internal data class CalendarEventRecord(
    val eventId: Long,
    val startMillis: Long?,
    val eventTimeZone: String?,
    val allDay: Boolean,
    val title: String?,
    val description: String?,
    val reminders: List<CalendarReminder>,
    val recurring: Boolean = false,
)

internal fun shouldScheduleReminder(record: CalendarEventRecord): Boolean =
    record.startMillis != null && !record.allDay && !record.recurring

internal fun formatEventDetails(
    title: String?,
    description: String?,
    fallback: String,
): String = listOfNotNull(
    title?.takeIf { it.isNotBlank() },
    description?.takeIf { it.isNotBlank() },
).joinToString("：").ifBlank { fallback }

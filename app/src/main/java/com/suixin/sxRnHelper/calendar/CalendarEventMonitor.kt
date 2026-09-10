package com.suixin.sxRnHelper.calendar

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders

internal class CalendarEventMonitor(
    private val contentResolver: ContentResolver,
) {
    fun currentEventIds(): Set<Long> = queryEvents().mapTo(mutableSetOf()) { it.id }

    fun scan(baselineEventIds: Set<Long>): CalendarEventScan {
        val events = queryEvents()
        val newRecords = events.asSequence()
            .filter { shouldCaptureNewEvent(it.id, baselineEventIds, emptySet()) }
            .map { event -> event.toRecord(queryReminders(event.id)) }
            .toList()
        return CalendarEventScan(
            eventIds = events.mapTo(mutableSetOf()) { it.id },
            newRecords = newRecords,
        )
    }

    private fun queryEvents(): List<EventRow> {
        val projection = arrayOf(
            Events._ID,
            Events.TITLE,
            Events.DESCRIPTION,
            Events.DTSTART,
            Events.EVENT_TIMEZONE,
            Events.ALL_DAY,
            Events.RRULE,
            Events.RDATE,
            Events.ORIGINAL_ID,
        )
        val cursor = contentResolver.query(
            Events.CONTENT_URI,
            projection,
            "${Events.DELETED} = 0",
            null,
            null,
        ) ?: return emptyList()

        return cursor.use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(
                        EventRow(
                            id = result.getLong(0),
                            title = result.getString(1),
                            description = result.getString(2),
                            startMillis = result.getLongOrNull(3),
                            eventTimeZone = result.getString(4),
                            allDay = result.getInt(5) != 0,
                            recurring = result.hasText(6) || result.hasText(7) || result.hasText(8),
                        ),
                    )
                }
            }
        }
    }

    private fun queryReminders(eventId: Long): List<CalendarReminder> {
        val projection = arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD)
        val cursor = CalendarContract.Reminders.query(contentResolver, eventId, projection)
            ?: return emptyList()
        return cursor.use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(
                        CalendarReminder(
                            minutes = result.getInt(1),
                            method = result.getInt(2),
                        ),
                    )
                }
            }.sortedBy { it.minutes }
        }
    }

    private fun EventRow.toRecord(reminders: List<CalendarReminder>) = CalendarEventRecord(
        eventId = id,
        startMillis = startMillis,
        eventTimeZone = eventTimeZone,
        allDay = allDay,
        title = title,
        description = description,
        reminders = reminders,
        recurring = recurring,
    )

    private data class EventRow(
        val id: Long,
        val title: String?,
        val description: String?,
        val startMillis: Long?,
        val eventTimeZone: String?,
        val allDay: Boolean,
        val recurring: Boolean,
    )

    private companion object {
        fun Cursor.getLongOrNull(index: Int): Long? =
            if (isNull(index)) null else getLong(index)

        fun Cursor.hasText(index: Int): Boolean = !isNull(index) && getString(index).isNotBlank()
    }
}

internal data class CalendarEventScan(
    val eventIds: Set<Long>,
    val newRecords: List<CalendarEventRecord>,
)

internal fun shouldCaptureNewEvent(
    eventId: Long,
    baselineEventIds: Set<Long>,
    processedEventIds: Set<Long>,
): Boolean = eventId !in baselineEventIds && eventId !in processedEventIds

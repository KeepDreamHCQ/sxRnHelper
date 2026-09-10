package com.suixin.sxRnHelper.calendar

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.json.JSONObject

internal class CalendarEventStore(context: Context) {
    private val storage: MMKV

    init {
        MMKV.initialize(context.applicationContext)
        storage = MMKV.mmkvWithID(PREFERENCES_NAME)
    }

    var monitoringEnabled: Boolean
        get() = storage.decodeBool(KEY_MONITORING_ENABLED, false)
        set(value) {
            storage.encode(KEY_MONITORING_ENABLED, value)
        }

    fun loadRecords(): List<CalendarEventRecord> {
        return loadRecords(KEY_RECORDS)
    }

    fun loadPendingReminders(): List<CalendarEventRecord> = loadRecords(KEY_PENDING_REMINDERS)

    val hasMonitoringBaseline: Boolean
        get() = storage.decodeBool(KEY_MONITORING_BASELINE_INITIALIZED, false)

    fun loadMonitoringBaseline(): Set<Long> {
        val raw = storage.decodeString(KEY_MONITORING_BASELINE) ?: return emptySet()
        return runCatching {
            val ids = JSONArray(raw)
            buildSet {
                for (index in 0 until ids.length()) add(ids.optLong(index))
            }
        }.getOrDefault(emptySet())
    }

    fun saveMonitoringBaseline(eventIds: Set<Long>) {
        storage.encode(
            KEY_MONITORING_BASELINE,
            JSONArray().apply { eventIds.forEach(::put) }.toString(),
        )
        storage.encode(KEY_MONITORING_BASELINE_INITIALIZED, true)
    }

    fun clearMonitoringBaseline() {
        storage.encode(KEY_MONITORING_BASELINE, "[]")
        storage.encode(KEY_MONITORING_BASELINE_INITIALIZED, false)
    }

    fun addRecord(record: CalendarEventRecord): List<CalendarEventRecord> {
        val records = buildList {
            add(record)
            addAll(loadRecords().filterNot { it.eventId == record.eventId })
        }.take(MAX_RECORDS)
        storage.encode(KEY_RECORDS, encode(records).toString())
        return records
    }

    fun addPendingReminder(record: CalendarEventRecord): Boolean {
        val records = buildList {
            add(record)
            addAll(loadPendingReminders().filterNot { it.eventId == record.eventId })
        }
        return storage.encode(KEY_PENDING_REMINDERS, encode(records).toString())
    }

    fun removePendingReminder(eventId: Long) {
        val records = loadPendingReminders()
        if (records.none { it.eventId == eventId }) return
        storage.encode(
            KEY_PENDING_REMINDERS,
            encode(records.filterNot { it.eventId == eventId }).toString(),
        )
    }

    fun removeRecord(eventId: Long): List<CalendarEventRecord> {
        val records = loadRecords().filterNot { it.eventId == eventId }
        storage.encode(KEY_RECORDS, encode(records).toString())
        removePendingReminder(eventId)
        return records
    }

    private fun loadRecords(key: String): List<CalendarEventRecord> {
        val raw = storage.decodeString(key) ?: return emptyList()
        return runCatching { decode(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun encode(records: List<CalendarEventRecord>): JSONArray = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject().apply {
                put(KEY_EVENT_ID, record.eventId)
                put(KEY_START_MILLIS, record.startMillis ?: JSONObject.NULL)
                put(KEY_EVENT_TIME_ZONE, record.eventTimeZone ?: JSONObject.NULL)
                put(KEY_ALL_DAY, record.allDay)
                put(KEY_TITLE, record.title ?: JSONObject.NULL)
                put(KEY_DESCRIPTION, record.description ?: JSONObject.NULL)
                put(KEY_RECURRING, record.recurring)
                put(KEY_REMINDERS, JSONArray().apply {
                    record.reminders.forEach { reminder ->
                        put(JSONObject().apply {
                            put(KEY_MINUTES, reminder.minutes)
                            put(KEY_METHOD, reminder.method)
                        })
                    }
                })
            })
        }
    }

    private fun decode(records: JSONArray): List<CalendarEventRecord> = buildList {
        for (index in 0 until records.length()) {
            val event = records.optJSONObject(index) ?: continue
            val reminders = event.optJSONArray(KEY_REMINDERS)?.let { reminderArray ->
                buildList {
                    for (reminderIndex in 0 until reminderArray.length()) {
                        val reminder = reminderArray.optJSONObject(reminderIndex) ?: continue
                        add(
                            CalendarReminder(
                                minutes = reminder.optInt(KEY_MINUTES),
                                method = reminder.optInt(KEY_METHOD),
                            ),
                        )
                    }
                }
            }.orEmpty()
            add(
                CalendarEventRecord(
                    eventId = event.optLong(KEY_EVENT_ID),
                    startMillis = event.optionalLong(KEY_START_MILLIS),
                    eventTimeZone = event.optionalString(KEY_EVENT_TIME_ZONE),
                    allDay = event.optBoolean(KEY_ALL_DAY),
                    title = event.optionalString(KEY_TITLE),
                    description = event.optionalString(KEY_DESCRIPTION),
                    reminders = reminders,
                    recurring = event.optBoolean(KEY_RECURRING, false),
                ),
            )
        }
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private companion object {
        const val PREFERENCES_NAME = "calendar_monitor"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_RECORDS = "records"
        const val KEY_PENDING_REMINDERS = "pending_reminders"
        const val KEY_MONITORING_BASELINE = "monitoring_baseline"
        const val KEY_MONITORING_BASELINE_INITIALIZED = "monitoring_baseline_initialized"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_START_MILLIS = "start_millis"
        const val KEY_EVENT_TIME_ZONE = "event_time_zone"
        const val KEY_ALL_DAY = "all_day"
        const val KEY_TITLE = "title"
        const val KEY_DESCRIPTION = "description"
        const val KEY_RECURRING = "recurring"
        const val KEY_REMINDERS = "reminders"
        const val KEY_MINUTES = "minutes"
        const val KEY_METHOD = "method"
        const val MAX_RECORDS = 100
    }
}

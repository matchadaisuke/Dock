package com.ambient.tvclock

import android.content.Context

object CalendarDisplayHelper {

    fun formatTime(context: Context, millis: Long): String =
        LocalizedDateTime.formatTime(context, millis)

    fun formatEventTime(context: Context, event: CalendarEvent): String {
        if (event.isAllDay) {
            return context.getString(R.string.calendar_all_day)
        }
        return "${formatTime(context, event.startMillis)} – ${formatTime(context, event.endMillis)}"
    }

    fun formatUpdated(context: Context, millis: Long): String =
        formatTime(context, millis)

    fun sourceLabel(context: Context, source: CalendarSource): String =
        when (source) {
            CalendarSource.PERSONAL -> context.getString(R.string.calendar_source_personal)
            CalendarSource.WORK -> context.getString(R.string.calendar_source_work)
        }

    fun nextUpcoming(events: List<CalendarEvent>, now: Long): CalendarEvent? {
        val active = events.filter { !it.isPast(now) }
        val happening = active.firstOrNull { it.isHappeningNow(now) }
        return happening ?: active.firstOrNull()
    }

    /** Count of upcoming events excluding [excluding] (typically the one we already feature). */
    fun remainingCount(events: List<CalendarEvent>, now: Long, excluding: CalendarEvent?): Int {
        return events.count { event -> !event.isPast(now) && event !== excluding }
    }
}

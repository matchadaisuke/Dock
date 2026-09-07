package com.ambient.tvclock

data class CalendarSnapshot(
    val events: List<CalendarEvent>,
    val lastUpdatedMillis: Long,
    val errorMessage: String? = null,
    /**
     * First event starting after today per source, looking ahead up to a
     * week. Feeds the "NEXT · MON 9:30 AM · …" preview an empty deck shows.
     */
    val nextAfterToday: Map<CalendarSource, CalendarEvent> = emptyMap(),
    /**
     * Sources that were configured but could not be fetched or parsed during
     * this refresh. Keep this separate from an empty event list so the UI can
     * distinguish "nothing scheduled" from "calendar unavailable".
     */
    val failedSources: Set<CalendarSource> = emptySet(),
)
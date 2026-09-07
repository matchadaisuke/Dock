package com.ambient.tvclock

import android.content.Context
import android.util.Log
import java.util.Calendar

object CalendarRepository {

    private const val TAG = "CalendarRepository"

    /** How far past today to look for each deck's "next event" preview. */
    private const val PREVIEW_LOOKAHEAD_DAYS = 7

    fun refresh(context: Context): CalendarSnapshot {
        if (!CalendarPreferences.isEnabled(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val personalUrl = CalendarPreferences.getPersonalUrl(context)
        val workUrl = CalendarPreferences.getWorkUrl(context)
        val googleApi = GoogleCalendarClient.isConfigured
        if (!CalendarPreferences.hasConfiguredSource(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, PREVIEW_LOOKAHEAD_DAYS)
        val previewEnd = cal.timeInMillis

        val merged = mutableListOf<CalendarEvent>()
        val failedSources = mutableSetOf<CalendarSource>()

        // Personal: Google Calendar API when provisioned (real colors, RSVP,
        // attendees, server-side recurrence expansion), else the ICS feed.
        // If the API fails and an ICS fallback exists, use it before surfacing
        // an error. A successful fallback is a successful personal source.
        val apiEvents = if (googleApi) {
            GoogleCalendarClient.fetchEvents(startOfDay, previewEnd)
        } else {
            null
        }
        when {
            apiEvents != null -> merged.addAll(apiEvents)
            personalUrl.isNotBlank() -> {
                if (!mergeFeed(personalUrl, CalendarSource.PERSONAL, merged)) {
                    failedSources.add(CalendarSource.PERSONAL)
                }
            }
            googleApi -> failedSources.add(CalendarSource.PERSONAL)
        }

        if (workUrl.isNotBlank()) {
            if (!mergeFeed(workUrl, CalendarSource.WORK, merged)) {
                failedSources.add(CalendarSource.WORK)
            }
        }

        // Expand across the whole preview window in one pass; today's list
        // and the per-source "next after today" previews both come out of it.
        val expanded = try {
            RruleExpander.expand(merged.sortedBy { it.startMillis }, startOfDay, previewEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Expand failed: ${e.message}", e)
            // Recurrence expansion is part of source interpretation. Do not
            // present an empty result as a trustworthy "nothing scheduled".
            if (CalendarPreferences.isPersonalConfigured(context)) {
                failedSources.add(CalendarSource.PERSONAL)
            }
            if (CalendarPreferences.isWorkConfigured(context)) {
                failedSources.add(CalendarSource.WORK)
            }
            merged.sortedBy { it.startMillis }
        }

        val today = filterToday(expanded, startOfDay, endOfDay)
        val nextAfterToday = CalendarSource.entries.mapNotNull { source ->
            expanded
                .filter {
                    it.rrule == null && it.source == source &&
                        it.startMillis >= endOfDay && it.startMillis < previewEnd
                }
                .minByOrNull { it.startMillis }
                ?.let { source to it }
        }.toMap()

        Log.i(TAG, "Today events: ${today.size} (failedSources=$failedSources)")

        return CalendarSnapshot(
            events = today,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorMessage = if (failedSources.isNotEmpty()) "error" else null,
            nextAfterToday = nextAfterToday,
            failedSources = failedSources.toSet(),
        )
    }

    private fun mergeFeed(url: String, source: CalendarSource, merged: MutableList<CalendarEvent>): Boolean {
        val body = IcalFetcher.fetch(url) ?: return false
        return try {
            merged.addAll(IcalParser.parse(body, source))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed for $source: ${e.message}", e)
            false
        }
    }

    private fun filterToday(
        events: List<CalendarEvent>,
        startOfDay: Long,
        endOfDay: Long
    ): List<CalendarEvent> {
        return events.filter { event ->
            event.rrule == null &&
                event.startMillis < endOfDay &&
                event.endMillis > startOfDay
        }
    }
}
package com.ambient.tvclock

import android.content.Context
import android.util.Log
import java.util.Calendar

object CalendarRepository {

    private const val TAG = "CalendarRepository"

    /** How far past today to look for the home deck's "next event" preview. */
    private const val PREVIEW_LOOKAHEAD_DAYS = 7

    fun refresh(context: Context, dayOffset: Int = 0): CalendarSnapshot {
        if (!CalendarPreferences.isEnabled(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val calendarUrl = CalendarPreferences.getCalendarUrl(context)
        val googleApi = GoogleCalendarClient.isConfigured
        if (!CalendarPreferences.isConfigured(context)) {
            return CalendarSnapshot(emptyList(), System.currentTimeMillis())
        }

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
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
        var failureDetail: String? = null

        // There is one logical calendar. Prefer the provisioned Google API when
        // available because it carries richer metadata; the single iCal URL is
        // its fallback. Without API credentials, that same URL is the source.
        val apiEvents = if (googleApi) {
            GoogleCalendarClient.fetchEvents(startOfDay, previewEnd)
        } else {
            null
        }
        when {
            apiEvents != null -> merged.addAll(apiEvents)
            calendarUrl.isNotBlank() -> {
                val failure = mergeFeed(calendarUrl, merged, startOfDay, previewEnd)
                if (failure != null) {
                    failedSources.add(CalendarSource.PERSONAL)
                    failureDetail = failure
                }
            }
            googleApi -> {
                failedSources.add(CalendarSource.PERSONAL)
                failureDetail = "Google Calendar APIから予定を取得できませんでした"
            }
        }

        // Expand across the whole preview window in one pass; today's list and
        // the single deck's "next after today" preview both come out of it.
        val expanded = try {
            RruleExpander.expand(merged.sortedBy { it.startMillis }, startOfDay, previewEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Expand failed: ${e.message}", e)
            if (CalendarPreferences.isConfigured(context)) {
                failedSources.add(CalendarSource.PERSONAL)
            }
            failureDetail = "繰り返し予定の展開に失敗しました: ${e.javaClass.simpleName}"
            merged.sortedBy { it.startMillis }
        }

        val today = filterToday(expanded, startOfDay, endOfDay)
        val next = expanded
            .filter {
                it.rrule == null &&
                    it.startMillis >= endOfDay && it.startMillis < previewEnd
            }
            .minByOrNull { it.startMillis }
        val nextAfterToday = next?.let { mapOf(CalendarSource.PERSONAL to it) }.orEmpty()

        Log.i(TAG, "Today events: ${today.size} (failedSources=$failedSources)")

        return CalendarSnapshot(
            events = today,
            lastUpdatedMillis = System.currentTimeMillis(),
            errorMessage = if (failedSources.isNotEmpty()) failureDetail ?: "原因不明のエラー" else null,
            nextAfterToday = nextAfterToday,
            failedSources = failedSources.toSet(),
        )
    }

    private fun mergeFeed(
        url: String,
        merged: MutableList<CalendarEvent>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): String? {
        val fetch = IcalFetcher.fetchDetailed(url)
        val body = fetch.body ?: return fetch.failure ?: "iCalデータを取得できませんでした"
        return try {
            merged.addAll(
                IcalParser.parse(
                    body,
                    CalendarSource.PERSONAL,
                    windowStartMillis,
                    windowEndMillis,
                )
            )
            null
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${e.message}", e)
            "iCal解析エラー: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
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

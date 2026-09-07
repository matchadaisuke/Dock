package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

object CalendarRefresh {
    private const val TAG = "CalendarRefresh"
    private val mainHandler = Handler(Looper.getMainLooper())

    // One persistent daemon thread services every refresh — receiver pings,
    // poller ticks, and settings refreshes all coalesce here instead of
    // allocating a fresh OS thread per call.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "calendar-fetch").apply { isDaemon = true }
    }

    fun publishAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val snapshot = try {
                CalendarRepository.refresh(app)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh crashed: ${e.message}", e)
                val previous = CalendarCenter.current
                CalendarSnapshot(
                    // Keep the last known-good payload visible, but do not lie
                    // that this failed refresh produced fresh data.
                    events = previous.events,
                    lastUpdatedMillis = previous.lastUpdatedMillis,
                    errorMessage = "error",
                    nextAfterToday = previous.nextAfterToday,
                    failedSources = if (CalendarPreferences.isConfigured(app)) {
                        setOf(CalendarSource.PERSONAL)
                    } else {
                        emptySet()
                    },
                )
            }
            mainHandler.post { CalendarCenter.update(snapshot) }
        }
    }
}

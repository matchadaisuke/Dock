package com.ambient.tvclock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object CalendarRefresh {
    private const val TAG = "CalendarRefresh"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshInFlight = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "calendar-fetch").apply { isDaemon = true }
    }

    fun publishAsync(context: Context) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            val previous = CalendarCenter.current
            val refreshed = try {
                CalendarRepository.refresh(app)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh crashed: ${e.message}", e)
                CalendarSnapshot(
                    events = emptyList(),
                    lastUpdatedMillis = System.currentTimeMillis(),
                    errorMessage = "更新処理エラー: ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}",
                    failedSources = if (CalendarPreferences.isConfigured(app)) setOf(CalendarSource.PERSONAL) else emptySet(),
                )
            }

            val snapshot = retainLastGoodOnFailure(refreshed, previous)
            mainHandler.post {
                try {
                    CalendarCenter.update(snapshot)
                } finally {
                    refreshInFlight.set(false)
                }
            }
        }
    }

    internal fun retainLastGoodOnFailure(
        refreshed: CalendarSnapshot,
        previous: CalendarSnapshot,
    ): CalendarSnapshot {
        if (refreshed.failedSources.isEmpty() || refreshed.events.isNotEmpty() || previous.events.isEmpty()) {
            return refreshed
        }
        return refreshed.copy(
            events = previous.events,
            lastUpdatedMillis = previous.lastUpdatedMillis,
            nextAfterToday = previous.nextAfterToday,
        )
    }
}

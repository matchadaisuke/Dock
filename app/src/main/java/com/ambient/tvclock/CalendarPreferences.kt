package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object CalendarPreferences {
    const val KEY_SHOW_CALENDAR = "show_calendar"
    const val KEY_PERSONAL_URL = "personal_calendar_url"
    const val KEY_WORK_URL = "work_calendar_url"

    private const val POLL_MS = 15 * 60 * 1000L

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_CALENDAR, true)
    }

    fun getPersonalUrl(context: Context): String =
        SecureCalendarStore.get(context, KEY_PERSONAL_URL)

    fun getWorkUrl(context: Context): String =
        SecureCalendarStore.get(context, KEY_WORK_URL)

    /** Personal events can come from either the provisioned Google API or an ICS fallback. */
    fun isPersonalConfigured(context: Context): Boolean =
        GoogleCalendarClient.isConfigured || getPersonalUrl(context).isNotBlank()

    fun isWorkConfigured(context: Context): Boolean =
        getWorkUrl(context).isNotBlank()

    fun hasConfiguredSource(context: Context): Boolean =
        isPersonalConfigured(context) || isWorkConfigured(context)

    fun setUrl(context: Context, key: String, value: String) {
        require(key == KEY_PERSONAL_URL || key == KEY_WORK_URL)
        SecureCalendarStore.put(context, key, value)
    }

    fun pollIntervalMs(): Long = POLL_MS
}

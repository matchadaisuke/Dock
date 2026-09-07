package com.ambient.tvclock

import android.content.Context
import androidx.preference.PreferenceManager

object CalendarPreferences {
    const val KEY_SHOW_CALENDAR = "show_calendar"

    /**
     * The app now exposes one calendar input. Keep the historical personal key
     * as the on-disk canonical key so existing installs retain their URL.
     */
    const val KEY_CALENDAR_URL = "personal_calendar_url"
    const val KEY_PERSONAL_URL = KEY_CALENDAR_URL

    /** Legacy only: migrated into [KEY_CALENDAR_URL] and then removed. */
    const val KEY_WORK_URL = "work_calendar_url"

    private const val POLL_MS = 15 * 60 * 1000L

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_CALENDAR, true)
    }

    fun getCalendarUrl(context: Context): String =
        SecureCalendarStore.get(context, KEY_CALENDAR_URL)

    // Compatibility aliases for code that still uses the old source vocabulary.
    fun getPersonalUrl(context: Context): String = getCalendarUrl(context)

    fun getWorkUrl(context: Context): String {
        SecureCalendarStore.migrateLegacy(context)
        return ""
    }

    /** The single calendar can come from the provisioned Google API or one ICS feed. */
    fun isConfigured(context: Context): Boolean =
        GoogleCalendarClient.isConfigured || getCalendarUrl(context).isNotBlank()

    fun isPersonalConfigured(context: Context): Boolean = isConfigured(context)

    fun isWorkConfigured(context: Context): Boolean {
        SecureCalendarStore.migrateLegacy(context)
        return false
    }

    fun hasConfiguredSource(context: Context): Boolean = isConfigured(context)

    /**
     * Accept the legacy work key from older callers, but always persist into the
     * single canonical calendar slot.
     */
    fun setUrl(context: Context, key: String, value: String) {
        require(key == KEY_CALENDAR_URL || key == KEY_WORK_URL)
        SecureCalendarStore.put(context, KEY_CALENDAR_URL, value)
    }

    fun pollIntervalMs(): Long = POLL_MS
}

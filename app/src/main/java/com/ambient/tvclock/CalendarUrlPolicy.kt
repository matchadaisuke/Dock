package com.ambient.tvclock

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Security policy for calendar feed URLs, which act as bearer credentials. */
object CalendarUrlPolicy {
    fun parseAllowed(url: String): HttpUrl? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        // Credentials in URL authority are easy to leak through logs/UI and are
        // not needed by supported Google/Outlook iCal feeds.
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed
    }

    fun isAllowed(url: String): Boolean = url.isBlank() || parseAllowed(url) != null
}

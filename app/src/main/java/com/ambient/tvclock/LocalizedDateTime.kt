package com.ambient.tvclock

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for user-facing date/time formatting.
 *
 * Do not hard-code English ordering such as `EEE · MMM d` or `h:mm a` in UI
 * binders. Locale data decides date field order and AM/PM placement, while the
 * Android 12/24-hour preference decides the hour cycle.
 */
object LocalizedDateTime {

    fun locale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.getDefault()
    }

    fun uses24HourClock(context: Context): Boolean = DateFormat.is24HourFormat(context)

    fun usesDayPeriod(context: Context): Boolean = !uses24HourClock(context)

    /** True when the locale normally places its day period before the hour (e.g. Japanese). */
    fun dayPeriodBeforeTime(context: Context): Boolean {
        if (!usesDayPeriod(context)) return false
        val pattern = DateFormat.getBestDateTimePattern(locale(context), "hm")
        val dayPeriod = pattern.indexOf('a')
        val hour = pattern.indexOfFirst { it == 'h' || it == 'H' || it == 'K' || it == 'k' }
        return dayPeriod >= 0 && hour >= 0 && dayPeriod < hour
    }

    /** Clock body without AM/PM because the home clock renders the day period separately. */
    fun formatClockTime(context: Context, millis: Long): String {
        val pattern = if (uses24HourClock(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, locale(context)).format(Date(millis))
    }

    fun formatClockSeconds(context: Context, millis: Long): String =
        SimpleDateFormat(":ss", locale(context)).format(Date(millis))

    fun formatDayPeriod(context: Context, millis: Long): String {
        if (!usesDayPeriod(context)) return ""
        return SimpleDateFormat("a", locale(context)).format(Date(millis))
    }

    /** Compact home-screen date, e.g. `MON, SEP 7` / `9月7日(月)`. */
    fun formatHomeDate(context: Context, millis: Long): String {
        val locale = locale(context)
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
        return displayCase(locale, SimpleDateFormat(pattern, locale).format(Date(millis)))
    }

    /** Full calendar heading, e.g. `Monday, September 7, 2026` / `2026年9月7日月曜日`. */
    fun formatCalendarDate(context: Context, millis: Long): String {
        val locale = locale(context)
        val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMMEEEEd")
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    /** User-preferred local time including a locale-correct day period when in 12-hour mode. */
    fun formatTime(context: Context, millis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(millis))

    /** Local time without a day period, useful only when a surrounding label already supplies it. */
    fun formatTimeWithoutDayPeriod(context: Context, millis: Long): String {
        val pattern = if (uses24HourClock(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, locale(context)).format(Date(millis))
    }

    fun formatWeekday(context: Context, millis: Long): String {
        val locale = locale(context)
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEE")
        return displayCase(locale, SimpleDateFormat(pattern, locale).format(Date(millis)))
    }

    fun formatPreviewDateTime(context: Context, millis: Long): String {
        val locale = locale(context)
        val skeleton = if (uses24HourClock(context)) "EHm" else "Ehm"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return displayCase(locale, SimpleDateFormat(pattern, locale).format(Date(millis)))
    }

    /**
     * Uppercase is a visual convention in the Latin UI, not a localization rule.
     * Avoid forcing case on CJK text (and on embedded Latin words inside CJK copy).
     */
    fun displayCase(context: Context, value: String): String =
        displayCase(locale(context), value)

    private fun displayCase(locale: Locale, value: String): String =
        when (locale.language) {
            Locale.JAPANESE.language,
            Locale.CHINESE.language,
            Locale.KOREAN.language -> value
            else -> value.uppercase(locale)
        }
}

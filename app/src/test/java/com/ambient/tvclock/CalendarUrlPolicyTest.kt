package com.ambient.tvclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarUrlPolicyTest {
    @Test
    fun acceptsHttpsCalendarUrls() {
        assertTrue(CalendarUrlPolicy.isAllowed("https://calendar.google.com/calendar/ical/example/basic.ics"))
        assertTrue(CalendarUrlPolicy.isAllowed(""))
    }

    @Test
    fun normalizesWebcalToHttps() {
        val parsed = CalendarUrlPolicy.parseAllowed("webcal://example.test/calendar.ics")
        assertEquals("https", parsed?.scheme)
        assertEquals("example.test", parsed?.host)
        assertEquals("/calendar.ics", parsed?.encodedPath)
    }

    @Test
    fun rejectsCleartextCalendarUrls() {
        assertFalse(CalendarUrlPolicy.isAllowed("http://example.test/calendar.ics"))
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertFalse(CalendarUrlPolicy.isAllowed("https://user:password@example.test/calendar.ics"))
        assertFalse(CalendarUrlPolicy.isAllowed("webcal://user:password@example.test/calendar.ics"))
    }

    @Test
    fun rejectsNonHttpSchemesAndMalformedValues() {
        assertFalse(CalendarUrlPolicy.isAllowed("file:///sdcard/calendar.ics"))
        assertFalse(CalendarUrlPolicy.isAllowed("not a url"))
    }
}
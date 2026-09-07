package com.ambient.tvclock

object IcalParser {

    fun parse(
        icsBody: String,
        source: CalendarSource,
        windowStartMillis: Long = Long.MIN_VALUE,
        windowEndMillis: Long = Long.MAX_VALUE,
    ): List<CalendarEvent> {
        val vtimezones = parseVTimeZones(icsBody)
        val events = mutableListOf<CalendarEvent>()
        var eventBody: StringBuilder? = null
        forEachUnfoldedLine(icsBody) { line ->
            when {
                line == "BEGIN:VEVENT" -> eventBody = StringBuilder(512)
                line == "END:VEVENT" -> {
                    val body = eventBody
                    eventBody = null
                    if (body != null) {
                        try {
                            parseEvent(body.toString(), source, vtimezones)?.let { event ->
                                if (event.rrule != null ||
                                    (event.startMillis < windowEndMillis && event.endMillis > windowStartMillis)
                                ) {
                                    events.add(event)
                                }
                            }
                        } catch (_: Exception) {
                            // Skip malformed events; keep the rest of the feed.
                        }
                    }
                }
                eventBody != null -> eventBody?.append(line)?.append('\n')
            }
        }
        return events.sortedBy { it.startMillis }
    }

    /**
     * Maps each VTIMEZONE's TZID to a GMT-offset zone ID, so TZIDs that Java doesn't
     * recognize (e.g. Outlook's "Customized Time Zone") still resolve to a usable zone.
     */
    private fun parseVTimeZones(icsBody: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var inTimeZone = false
        var inStandard = false
        var tzid: String? = null
        var firstOffset: String? = null
        var standardOffset: String? = null

        forEachUnfoldedLine(icsBody) { line ->
            when (line) {
                "BEGIN:VTIMEZONE" -> {
                    inTimeZone = true
                    inStandard = false
                    tzid = null
                    firstOffset = null
                    standardOffset = null
                }
                "BEGIN:STANDARD" -> if (inTimeZone) inStandard = true
                "END:STANDARD" -> inStandard = false
                "END:VTIMEZONE" -> {
                    if (inTimeZone && tzid != null) {
                        val offset = standardOffset ?: firstOffset
                        if (offset != null) {
                            IcalTimeZones.offsetToZoneId(offset)?.let { map[tzid!!] = it }
                        }
                    }
                    inTimeZone = false
                    inStandard = false
                }
                else -> if (inTimeZone) {
                    when {
                        line.startsWith("TZID:") && tzid == null ->
                            tzid = line.substring("TZID:".length).trim()
                        line.startsWith("TZOFFSETTO:") -> {
                            val offset = line.substring("TZOFFSETTO:".length).trim()
                            if (firstOffset == null) firstOffset = offset
                            if (inStandard && standardOffset == null) standardOffset = offset
                        }
                    }
                }
            }
        }
        return map
    }

    private fun parseEvent(
        body: String,
        source: CalendarSource,
        vtimezones: Map<String, String>
    ): CalendarEvent? {
        val props = parseProperties(body)

        // Cancelled events still appear in published feeds; never surface them.
        if (props["STATUS"]?.value?.trim()?.uppercase() == "CANCELLED") {
            return null
        }

        val summary = props["SUMMARY"]?.value
            ?.replace("\\n", " ")
            ?.replace("\\,", ",")
            ?.trim()
        if (summary.isNullOrEmpty()) {
            return null
        }

        val startProp = props["DTSTART"] ?: return null
        val endProp = props["DTEND"]
        val tzId = IcalTimeZones.resolveId(startProp.param("TZID"), vtimezones)
        val endTzId = endProp?.param("TZID")
            ?.let { IcalTimeZones.resolveId(it, vtimezones) }
            ?: tzId
        val allDay = startProp.param("VALUE") == "DATE" || startProp.value.length == 8

        val startMillis = if (allDay) {
            IcalDateTimes.parseDate(startProp.value, endOfDay = false, timeZoneId = tzId)
        } else {
            IcalDateTimes.parseDateTime(startProp.value, timeZoneId = tzId)
        }
        val endMillis = when {
            endProp != null && (endProp.param("VALUE") == "DATE" || endProp.value.length == 8) ->
                IcalDateTimes.parseDate(endProp.value, endOfDay = true, timeZoneId = endTzId)
            endProp != null ->
                IcalDateTimes.parseDateTime(endProp.value, timeZoneId = endTzId)
            allDay -> startMillis + 24 * 60 * 60 * 1000L
            else -> startMillis + 60 * 60 * 1000L
        }

        val location = props["LOCATION"]?.value
            ?.replace("\\n", " ")
            ?.replace("\\,", ",")
            ?.trim()
            .orEmpty()

        val rrule = props["RRULE"]?.value

        return CalendarEvent(
            title = summary,
            startMillis = startMillis,
            endMillis = endMillis,
            isAllDay = allDay,
            location = location,
            source = source,
            timeZoneId = tzId,
            rrule = rrule,
            busyStatus = parseBusyStatus(props),
            categories = parseCategories(props),
            onlineMeetingUrl = findMeetingUrl(props, location),
            organizer = parseOrganizer(props),
            colorHex = parseColor(props)
        )
    }

    private fun parseBusyStatus(props: Map<String, IcalProperty>): BusyStatus {
        when (props["X-MICROSOFT-CDO-BUSYSTATUS"]?.value?.trim()?.uppercase()) {
            "FREE" -> return BusyStatus.FREE
            "TENTATIVE" -> return BusyStatus.TENTATIVE
            "OOF" -> return BusyStatus.OOF
            "BUSY" -> return BusyStatus.BUSY
        }
        if (props["STATUS"]?.value?.trim()?.uppercase() == "TENTATIVE") {
            return BusyStatus.TENTATIVE
        }
        if (props["TRANSP"]?.value?.trim()?.uppercase() == "TRANSPARENT") {
            return BusyStatus.FREE
        }
        return BusyStatus.BUSY
    }

    private fun parseCategories(props: Map<String, IcalProperty>): List<String> {
        val raw = props["CATEGORIES"]?.value ?: return emptyList()
        return raw.split(',')
            .map { it.replace("\\,", ",").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun findMeetingUrl(props: Map<String, IcalProperty>, location: String): String? {
        props["X-MICROSOFT-SKYPETEAMSMEETINGURL"]?.value?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        val haystacks = listOfNotNull(
            location,
            props["DESCRIPTION"]?.value,
            props["X-GOOGLE-CONFERENCE"]?.value,
            props["URL"]?.value
        )
        for (text in haystacks) {
            MEETING_URL_REGEX.find(text)?.let { return it.value.trimEnd('\\', '>', ')', '.') }
        }
        return null
    }

    private fun parseOrganizer(props: Map<String, IcalProperty>): String? {
        val prop = props["ORGANIZER"] ?: return null
        prop.param("CN")?.trim('"')?.takeIf { it.isNotBlank() }?.let { return it }
        return prop.value.removePrefix("mailto:").takeIf { it.isNotBlank() }
    }

    private fun parseColor(props: Map<String, IcalProperty>): String? {
        val raw = (props["X-APPLE-CALENDAR-COLOR"] ?: props["COLOR"])?.value?.trim()
            ?: return null
        // Accept #RRGGBB / #RRGGBBAA; CSS color names are not worth mapping.
        if (!raw.startsWith("#") || raw.length < 7) return null
        return raw.substring(0, 7)
    }

    private val MEETING_URL_REGEX = Regex(
        "https://(?:[\\w.-]*teams\\.microsoft\\.com/l/meetup-join|meet\\.google\\.com|[\\w.-]*zoom\\.us/j)[^\\s\"'<>]*"
    )

    private fun parseProperties(body: String): Map<String, IcalProperty> {
        val map = mutableMapOf<String, IcalProperty>()
        for (line in body.lines()) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val rawKey = line.substring(0, colon)
            val name = rawKey.substringBefore(';').uppercase()
            val params = mutableMapOf<String, String>()
            val semi = rawKey.indexOf(';')
            val paramPart = if (semi >= 0) rawKey.substring(semi + 1) else ""
            if (paramPart.isNotEmpty()) {
                for (segment in paramPart.split(';')) {
                    val eq = segment.indexOf('=')
                    if (eq > 0) {
                        params[segment.substring(0, eq).uppercase()] = segment.substring(eq + 1)
                    }
                }
            }
            val value = line.substring(colon + 1).trim()
            map[name] = IcalProperty(name, params, value)
        }
        return map
    }

    /**
     * Iterates RFC 5545 logical lines without normalizing or copying the whole feed.
     * Folded continuation lines are joined into one small per-line buffer.
     */
    private inline fun forEachUnfoldedLine(ics: String, consume: (String) -> Unit) {
        var cursor = 0
        var logical: StringBuilder? = null

        while (cursor <= ics.length) {
            var end = cursor
            while (end < ics.length && ics[end] != '\r' && ics[end] != '\n') end++

            val continuation = cursor < end && (ics[cursor] == ' ' || ics[cursor] == '\t')
            val contentStart = if (continuation) cursor + 1 else cursor

            if (continuation && logical != null) {
                logical.append(ics, contentStart, end)
            } else {
                logical?.let { consume(it.toString()) }
                logical = StringBuilder((end - contentStart).coerceAtLeast(16))
                    .append(ics, contentStart, end)
            }

            if (end >= ics.length) break
            cursor = end + 1
            if (ics[end] == '\r' && cursor < ics.length && ics[cursor] == '\n') cursor++
        }

        logical?.let { consume(it.toString()) }
    }
}

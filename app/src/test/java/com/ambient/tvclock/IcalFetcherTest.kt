package com.ambient.tvclock

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class IcalFetcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resumes a large calendar after the response is cut off`() {
        val event = "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20260908T090000Z\r\nDTEND:20260908T100000Z\r\nSUMMARY:Test event\r\nEND:VEVENT\r\n"
        val padding = buildString {
            repeat(18000) { append("X-LONG-$it:abcdefghijklmnopqrstuvwxyz0123456789\r\n") }
        }
        val calendar = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$padding${event}END:VCALENDAR\r\n"
        val bytes = calendar.toByteArray(Charsets.UTF_8)
        var sawRange = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("identity", request.getHeader("Accept-Encoding"))
                val range = request.getHeader("Range")
                if (range == null) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(bytes))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }

                sawRange = true
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                val remaining = bytes.copyOfRange(start, bytes.size)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    .setBody(Buffer().write(remaining))
            }
        }

        val client = OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val result = IcalFetcher.fetchForTest(server.url("/calendar.ics"), client)

        assertNotNull(result.body)
        assertEquals(calendar, result.body)
        assertTrue("resume request should use HTTP Range", sawRange)
        assertTrue(server.requestCount >= 2)
    }

    @Test
    fun `rejects a truncated response that never reaches calendar end`() {
        val truncated = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
        server.enqueue(MockResponse().setBody(truncated))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setBody(truncated))
        server.enqueue(MockResponse().setResponseCode(416))

        val result = IcalFetcher.fetchForTest(server.url("/calendar.ics"), OkHttpClient())

        assertEquals(null, result.body)
        assertTrue(result.failure.orEmpty().contains("途中"))
    }
}


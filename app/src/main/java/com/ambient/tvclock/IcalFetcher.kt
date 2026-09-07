package com.ambient.tvclock

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException

object IcalFetcher {

    private const val TAG = "IcalFetcher"
    private const val MAX_REDIRECTS = 3
    private const val MAX_DOWNLOAD_ATTEMPTS = 4
    private const val READ_BUFFER_BYTES = 64 * 1024

    // Published Google/Outlook feeds include history and recurrence metadata,
    // so mature calendars can legitimately exceed 1 MiB. Keep a hard bound to
    // protect a TV process from unbounded responses, but make it large enough
    // for real-world private iCal feeds.
    private const val MAX_CALENDAR_BYTES = 32L * 1024L * 1024L

    // Calendar URLs are bearer-like secrets. Handle redirects ourselves so an
    // HTTPS feed can never be silently downgraded to cleartext HTTP.
    private val client = HttpClients.shared.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class FetchOutcome(
        val body: String? = null,
        val failure: String? = null,
    )

    fun fetch(url: String): String? = fetchDetailed(url).body
    internal fun fetchForTest(url: HttpUrl, httpClient: OkHttpClient): FetchOutcome {
        return when (val result = fetchOnce(url, httpClient)) {
            is FetchResult.Body -> FetchOutcome(body = result.value)
            is FetchResult.Redirect -> FetchOutcome(failure = "redirect")
            is FetchResult.Failed -> FetchOutcome(failure = result.reason)
        }
    }

    fun fetchDetailed(url: String): FetchOutcome {
        if (url.isBlank()) return FetchOutcome(failure = "URLが空です")
        var current = CalendarUrlPolicy.parseAllowed(url) ?: run {
            Log.w(TAG, "Rejected calendar URL: HTTPS without embedded credentials is required")
            return FetchOutcome(failure = "URLが無効です（HTTPSのiCal URLが必要です）")
        }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val result = fetchOnce(current)
            when (result) {
                is FetchResult.Body -> return FetchOutcome(body = result.value)
                is FetchResult.Redirect -> {
                    if (redirectCount >= MAX_REDIRECTS) {
                        Log.w(TAG, "Too many calendar feed redirects")
                        return FetchOutcome(failure = "リダイレクト回数が多すぎます")
                    }
                    val next = current.resolve(result.location) ?: run {
                        Log.w(TAG, "Invalid calendar redirect target")
                        return FetchOutcome(failure = "リダイレクト先URLが無効です")
                    }
                    current = CalendarUrlPolicy.parseAllowed(next.toString()) ?: run {
                        Log.w(TAG, "Blocked insecure calendar redirect")
                        return FetchOutcome(failure = "安全でないリダイレクトが拒否されました")
                    }
                }
                is FetchResult.Failed -> return FetchOutcome(failure = result.reason)
            }
        }
        return FetchOutcome(failure = "取得処理を完了できませんでした")
    }

    private fun fetchOnce(url: HttpUrl, httpClient: OkHttpClient = client): FetchResult {
        val output = ByteArrayOutputStream(1024 * 1024)
        var lastFailure: Throwable? = null

        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            val resumeAt = output.size().toLong()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV Awake Clock) AppleWebKit/537.36")
                .header("Accept", "text/calendar,*/*")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .apply { if (resumeAt > 0L) header("Range", "bytes=$resumeAt-") }
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val location = response.header("Location")
                        return if (location.isNullOrBlank()) {
                            FetchResult.Failed("リダイレクト先が返されませんでした")
                        } else FetchResult.Redirect(location)
                    }
                    if (resumeAt > 0L && response.code == 416) {
                        completeCalendar(output)?.let { return FetchResult.Body(it) }
                        output.reset()
                        return@use
                    }
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} for calendar feed")
                        return FetchResult.Failed("HTTP ${response.code}")
                    }

                    if (resumeAt > 0L && response.code == 206) {
                        val rangeStart = parseContentRangeStart(response.header("Content-Range"))
                        if (rangeStart != resumeAt) {
                            Log.w(TAG, "Invalid Content-Range while resuming: ${response.header("Content-Range")}")
                            output.reset()
                            return@use
                        }
                    } else if (resumeAt > 0L) {
                        // The server ignored Range and returned the complete feed.
                        output.reset()
                    }

                    val body = response.body ?: return FetchResult.Failed("サーバーの応答が空です")
                    val declaredLength = body.contentLength()
                    if (declaredLength >= 0L && output.size().toLong() + declaredLength > MAX_CALENDAR_BYTES) {
                        return FetchResult.Failed("iCalデータが32 MiBを超えています")
                    }

                    val beforeRead = output.size()
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    try {
                        val input = body.byteStream()
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size().toLong() + read > MAX_CALENDAR_BYTES) {
                                return FetchResult.Failed("iCalデータが32 MiBを超えています")
                            }
                            output.write(buffer, 0, read)
                        }
                    } catch (e: IOException) {
                        lastFailure = e
                        if (!isRetryable(e) || attempt == MAX_DOWNLOAD_ATTEMPTS - 1) throw e
                        Log.w(TAG, "Calendar response interrupted at ${output.size()} bytes; resuming (${attempt + 2}/$MAX_DOWNLOAD_ATTEMPTS)")
                        return@use
                    }

                    val actualRead = output.size() - beforeRead
                    if (declaredLength >= 0L && actualRead.toLong() < declaredLength) {
                        lastFailure = EOFException("expected $declaredLength bytes, received $actualRead")
                        if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) return@use
                    }
                    completeCalendar(output)?.let { return FetchResult.Body(it) }
                    lastFailure = EOFException("iCal response ended before END:VCALENDAR")
                }
            } catch (e: Exception) {
                lastFailure = e
                if (!isRetryable(e) || attempt == MAX_DOWNLOAD_ATTEMPTS - 1) {
                    Log.e(TAG, "Fetch failed: ${e.javaClass.simpleName}: ${e.message}")
                    return FetchResult.Failed(failureMessage(e))
                }
                Log.w(TAG, "Calendar fetch failed; retrying (${attempt + 2}/$MAX_DOWNLOAD_ATTEMPTS): ${e.javaClass.simpleName}")
            }
        }
        return FetchResult.Failed(lastFailure?.let(::failureMessage) ?: "iCalデータを取得できませんでした")
    }

    private fun completeCalendar(output: ByteArrayOutputStream): String? {
        if (output.size() == 0) return null
        val text = output.toString(Charsets.UTF_8.name())
        val trimmed = text.trimEnd('\u0000', ' ', '\t', '\r', '\n')
        val startsCorrectly = trimmed.startsWith("BEGIN:VCALENDAR") || trimmed.startsWith("\uFEFFBEGIN:VCALENDAR")
        return text.takeIf { startsCorrectly && trimmed.endsWith("END:VCALENDAR") }
    }

    private fun parseContentRangeStart(header: String?): Long? {
        if (header == null || !header.startsWith("bytes ", ignoreCase = true)) return null
        return header.substring(6).substringBefore('-').trim().toLongOrNull()
    }

    private fun isRetryable(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is EOFException || current is java.net.SocketTimeoutException ||
                current is SocketException || current is ProtocolException ||
                current.message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                current.message?.contains("unexpected end of input", ignoreCase = true) == true ||
                current.message?.contains("connection reset", ignoreCase = true) == true
            ) return true
            current = current.cause
        }
        return false
    }

    private fun failureMessage(error: Throwable): String = when {
        isPrematureEof(error) -> "通信が途中で切断されました（$MAX_DOWNLOAD_ATTEMPTS 回試行）"
        error is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
        error is java.net.UnknownHostException -> "サーバー名を解決できませんでした"
        error is javax.net.ssl.SSLException -> "SSL/TLS接続に失敗しました"
        error is SocketException -> "ネットワーク接続が切断されました: ${error.message.orEmpty()}"
        else -> "通信エラー: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}"
    }
    private fun isPrematureEof(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is EOFException || current is ProtocolException ||
                current.message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                current.message?.contains("unexpected end of input", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private sealed interface FetchResult {
        data class Body(val value: String) : FetchResult
        data class Redirect(val location: String) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }
}

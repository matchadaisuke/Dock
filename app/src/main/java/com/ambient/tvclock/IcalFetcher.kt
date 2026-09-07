package com.ambient.tvclock

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Request

object IcalFetcher {

    private const val TAG = "IcalFetcher"
    private const val MAX_REDIRECTS = 3

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

    private fun fetchOnce(url: HttpUrl): FetchResult {
        val request = Request.Builder()
            .url(url)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; TV Awake Clock) AppleWebKit/537.36"
            )
            .header("Accept", "text/calendar,*/*")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) {
                        Log.w(TAG, "Calendar redirect did not include Location")
                        return FetchResult.Failed("リダイレクト先が返されませんでした")
                    }
                    return FetchResult.Redirect(location)
                }
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for calendar feed")
                    return FetchResult.Failed("HTTP ${response.code}")
                }

                val body = response.body ?: return FetchResult.Failed("サーバーの応答が空です")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_CALENDAR_BYTES) {
                    Log.w(TAG, "Calendar feed too large: $declaredLength bytes")
                    return FetchResult.Failed("iCalデータが32 MiBを超えています")
                }

                val bytes = body.source().readByteArray(MAX_CALENDAR_BYTES + 1)
                if (bytes.size > MAX_CALENDAR_BYTES) {
                    Log.w(TAG, "Calendar feed exceeded size limit")
                    return FetchResult.Failed("iCalデータが32 MiBを超えています")
                }
                FetchResult.Body(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            FetchResult.Failed(
                when (e) {
                    is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
                    is java.net.UnknownHostException -> "サーバー名を解決できませんでした"
                    is javax.net.ssl.SSLException -> "SSL/TLS接続に失敗しました"
                    else -> "通信エラー: ${e.javaClass.simpleName}"
                }
            )
        }
    }

    private sealed interface FetchResult {
        data class Body(val value: String) : FetchResult
        data class Redirect(val location: String) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }
}

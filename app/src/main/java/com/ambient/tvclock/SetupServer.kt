package com.ambient.tvclock

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.ambient.tvclock.vpn.ConfigImportSession
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived LAN HTTP server used only while [SetupActivity] is visible.
 *
 * Security notes:
 * - GET / never exposes saved settings; the PIN must be verified first.
 * - Secret calendar URLs are read from/written to encrypted storage.
 * - Calendar feeds must use HTTPS.
 * - Only local/link-local clients are accepted and responses are no-store.
 *
 * The setup transport itself remains HTTP so the feature works on a trusted
 * home LAN without certificate provisioning. Do not expose these ports outside
 * the LAN.
 */
class SetupServer(
    private val context: Context,
    private val session: ConfigImportSession,
    private val listener: Listener,
) {

    interface Listener {
        fun onSettingsSaved(labels: List<String>)
        fun onServerError(message: String)
    }

    private data class Field(
        val key: String,
        @StringRes val labelRes: Int,
        val calendarSecret: Boolean = false,
    )

    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2)
    private var serverSocket: ServerSocket? = null
    private var boundPort: Int = -1

    val port: Int get() = boundPort

    fun start(): Boolean {
        if (running.get()) return true
        val socket = bindFirstFreePort() ?: run {
            listener.onServerError("Could not bind to any LAN port")
            return false
        }
        serverSocket = socket
        boundPort = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "setup-accept").start()
        Timber.i("SetupServer: listening on 0.0.0.0:%d", boundPort)
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: IOException) { }
        executor.shutdownNow()
    }

    private fun bindFirstFreePort(): ServerSocket? {
        for (candidate in PORT_CANDIDATES) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", candidate))
                return socket
            } catch (_: BindException) {
                continue
            } catch (e: IOException) {
                Timber.w(e, "SetupServer: failed to bind :%d", candidate)
            }
        }
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5_000
                val output = sock.getOutputStream()
                if (!isLocalClient(sock.inetAddress)) {
                    writeStatus(output, 403, "Forbidden", "text/plain", "LAN clients only")
                    return
                }

                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeStatus(output, 400, "Bad Request", "text/plain", "malformed request line")
                    return
                }
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(reader)

                when {
                    method == "GET" && path == "/" -> servePinForm(output)
                    method == "POST" && path == "/unlock" -> handleUnlock(reader, headers, output)
                    method == "POST" && path == "/save" -> handleSave(reader, headers, output)
                    else -> writeStatus(output, 404, "Not Found", "text/plain", "")
                }
            } catch (e: Exception) {
                Timber.w(e, "SetupServer: error handling client")
            }
        }
    }

    private fun isLocalClient(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val out = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            out[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return out
    }

    private fun readForm(reader: BufferedReader, headers: Map<String, String>, output: OutputStream): Map<String, String>? {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: -1
        if (contentLength <= 0) {
            writeStatus(output, 411, "Length Required", "text/plain", "Content-Length required")
            return null
        }
        if (contentLength > MAX_BODY_BYTES) {
            writeStatus(output, 413, "Payload Too Large", "text/plain", "body exceeds ${MAX_BODY_BYTES / 1024} KB")
            return null
        }
        val bodyBuf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(bodyBuf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return parseForm(String(bodyBuf, 0, read))
    }

    private fun verifyPin(pin: String, output: OutputStream): Boolean {
        when (session.verify(pin)) {
            ConfigImportSession.Verdict.EXPIRED -> {
                writeStatus(output, 410, "Gone", "text/plain", "setup window expired")
                return false
            }
            ConfigImportSession.Verdict.LOCKED_OUT -> {
                writeStatus(output, 429, "Too Many Requests", "text/plain", "rate limited; try again in a minute")
                return false
            }
            ConfigImportSession.Verdict.BAD_PIN -> {
                writeStatus(output, 401, "Unauthorized", "text/plain", "bad PIN")
                return false
            }
            ConfigImportSession.Verdict.OK -> return true
        }
    }

    private fun handleUnlock(
        reader: BufferedReader,
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val fields = readForm(reader, headers, output) ?: return
        val pin = headers["x-setup-pin"] ?: fields["pin"].orEmpty()
        if (!verifyPin(pin, output)) return
        serveSettingsForm(output, pin)
    }

    private fun handleSave(
        reader: BufferedReader,
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val fields = readForm(reader, headers, output) ?: return
        val pin = headers["x-setup-pin"] ?: fields["pin"].orEmpty()
        if (!verifyPin(pin, output)) return

        val touched = FIELDS.filter { it.key in fields }
        if (touched.isEmpty()) {
            writeStatus(output, 400, "Bad Request", "text/plain", "no known fields in body")
            return
        }

        val badCalendar = touched.firstOrNull { field ->
            field.calendarSecret && !CalendarUrlPolicy.isAllowed(fields.getValue(field.key).trim())
        }
        if (badCalendar != null) {
            writeStatus(output, 400, "Bad Request", "text/plain", "calendar URLs must use HTTPS")
            return
        }

        val normalEditor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        for (field in touched) {
            val value = fields.getValue(field.key).trim()
            if (field.calendarSecret) {
                CalendarPreferences.setUrl(context, field.key, value)
            } else {
                normalEditor.putString(field.key, value)
            }
        }
        normalEditor.apply()

        if (touched.any { it.calendarSecret }) {
            CalendarRefresh.publishAsync(context)
        }

        val labels = touched.map { context.getString(it.labelRes) }
        writeStatus(output, 200, "OK", "text/plain", "saved: ${labels.joinToString(", ")}")
        listener.onSettingsSaved(labels)
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotBlank() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) URLDecoder.decode(it, "UTF-8") to ""
            else URLDecoder.decode(it.substring(0, eq), "UTF-8") to
                URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }

    /** First page: PIN only. Saved secrets are deliberately not rendered here. */
    private fun servePinForm(output: OutputStream) {
        val html = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${htmlEscape(context.getString(R.string.setup_title))}</title>
            ${commonStyle()}
            <h1>${htmlEscape(context.getString(R.string.setup_title))}</h1>
            <p>${htmlEscape(context.getString(R.string.setup_web_pin_explanation))}</p>
            <form method="POST" action="/unlock" enctype="application/x-www-form-urlencoded">
              <p><label>${htmlEscape(context.getString(R.string.setup_pin_label))}<br><input name="pin" type="password" inputmode="numeric" autocomplete="off" required></label></p>
              <p><button type="submit">${htmlEscape(context.getString(R.string.setup_web_unlock))}</button></p>
            </form>
        """.trimIndent()
        writeStatus(output, 200, "OK", "text/html; charset=utf-8", html)
    }

    /** Render current values only after successful PIN verification. */
    private fun serveSettingsForm(output: OutputStream, pin: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val inputs = FIELDS.joinToString("\n") { field ->
            val rawValue = when (field.key) {
                CalendarPreferences.KEY_PERSONAL_URL -> CalendarPreferences.getPersonalUrl(context)
                CalendarPreferences.KEY_WORK_URL -> CalendarPreferences.getWorkUrl(context)
                else -> prefs.getString(field.key, "").orEmpty()
            }
            val value = htmlEscape(rawValue)
            val label = htmlEscape(context.getString(field.labelRes))
            val placeholder = if (field.calendarSecret) "https://…" else "https://… / http://LAN…"
            """<p><label>$label<br><input name="${field.key}" type="url" value="$value" placeholder="$placeholder"></label></p>"""
        }
        val html = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${htmlEscape(context.getString(R.string.setup_title))}</title>
            ${commonStyle()}
            <h1>${htmlEscape(context.getString(R.string.setup_title))}</h1>
            <form method="POST" action="/save" enctype="application/x-www-form-urlencoded">
              <input name="pin" type="hidden" value="${htmlEscape(pin)}">
              $inputs
              <p><button type="submit">${htmlEscape(context.getString(R.string.setup_web_save))}</button></p>
            </form>
        """.trimIndent()
        writeStatus(output, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun commonStyle(): String = """
        <style>
          body{font:15px/1.5 system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 16px;background:#111;color:#eee}
          h1{font-weight:600}
          input{width:100%;box-sizing:border-box;font:14px ui-monospace,monospace;padding:8px 10px;background:#000;color:#eee;border:1px solid #333}
          label{color:#bbb}
          button{padding:10px 22px;background:#0a7;color:#fff;border:0;font-weight:600;cursor:pointer;font-size:15px}
        </style>
    """.trimIndent()

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun writeStatus(out: OutputStream, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = PrintWriter(out)
        writer.print("HTTP/1.1 $code $reason\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Cache-Control: no-store, max-age=0\r\n")
        writer.print("Pragma: no-cache\r\n")
        writer.print("X-Content-Type-Options: nosniff\r\n")
        writer.print("X-Frame-Options: DENY\r\n")
        writer.print("Referrer-Policy: no-referrer\r\n")
        if (contentType.startsWith("text/html")) {
            writer.print("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n")
        }
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val PORT_CANDIDATES = intArrayOf(8768, 8769, 8770)
        private const val MAX_BODY_BYTES = 16 * 1024

        private val FIELDS = listOf(
            Field(CalendarPreferences.KEY_PERSONAL_URL, R.string.pref_personal_calendar_url_title, calendarSecret = true),
            Field(CalendarPreferences.KEY_WORK_URL, R.string.pref_work_calendar_url_title, calendarSecret = true),
            Field(HomeLabPreferences.KEY_HOMELAB_URL, R.string.pref_homelab_url_title),
            Field(AdBlockPreferences.KEY_DASHBOARD_URL, R.string.pref_adblock_url_title),
        )
    }
}

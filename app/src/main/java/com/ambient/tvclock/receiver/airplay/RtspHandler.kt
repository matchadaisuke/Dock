package com.ambient.tvclock.receiver.airplay

import com.ambient.tvclock.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * WHY: AirPlay uses RTSP (Real Time Streaming Protocol) to negotiate and control
 * a streaming session. Before any video or audio arrives, macOS and PhairPlay
 * must exchange a series of RTSP messages to agree on codecs, ports, and encryption keys.
 *
 * HOW: Listens on TCP port 7000. When a macOS device connects, it handles the
 * RTSP message exchange, extracts stream parameters from the SDP body, and then
 * creates [VideoDecoder] and [AudioPlayer] instances to handle the media.
 *
 * The RTSP state machine:
 *   IDLE → (client connects) → CONNECTED → (OPTIONS/SETUP/ANNOUNCE) → NEGOTIATING
 *   NEGOTIATING → (RECORD received) → STREAMING → (TEARDOWN or disconnect) → IDLE
 *
 * Example:
 *   val handler = RtspHandler(
 *       videoSurface = surface,
 *       onStreamingStarted = { showVideo() },
 *       onStreamingStopped = { showWaiting() }
 *   )
 *   handler.start(coroutineScope)
 *   handler.stop()
 */
open class RtspHandler(
    // Called lazily when RECORD is received — Surface is ready by then
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val controlHandler: AirPlayControlHandler,
    private val onMirrorRecord: () -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var running = false

    private var currentCSeq: Int = 0

    @Volatile
    private var currentSession: SessionDescription? = null

    private var setupCount = 0

    @Volatile
    private var macOsMirrorHandshake = false

    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(RTSP_PORT)
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${AirPlayNetwork.formatAddress(clientSocket.inetAddress)}")

                if (activeClient != null && !activeClient!!.isClosed) {
                    Logger.w("Rejecting second client — already streaming")
                    sendServiceUnavailable(clientSocket)
                    clientSocket.close()
                    continue
                }

                activeClient = clientSocket
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    private fun peerAddress(socket: Socket): InetAddress =
        AirPlayNetwork.normalizePeerAddress(socket.inetAddress, socket)

    internal fun handleClient(socket: Socket) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()
        var mirrorMode = false
        var streamingStarted = false

        try {
            while (running && !socket.isClosed) {
                val request = readAirPlayRequest(inputStream) ?: break
                val response = if (isAirPlayControlRequest(request)) {
                    val r = controlHandler.handle(request, peerAddress(socket), socket)
                    if (request.method == "SETUP" && r.statusCode == 200 && isBinaryPlist(request.bodyBytes)) {
                        macOsMirrorHandshake = true
                    }
                    if (request.method == "RECORD" && r.statusCode == 200) {
                        mirrorMode = true
                        streamingStarted = true
                        onMirrorRecord()
                    }
                    if (request.method == "POST" && pathOf(request.uri) == "/play" &&
                        r.statusCode == 200) {
                        streamingStarted = true
                    }
                    r
                } else {
                    val r = routeRequest(request)
                    if (request.method == "RECORD" && r.statusCode == 200) {
                        streamingStarted = true
                    }
                    r
                }
                sendResponse(outputStream, request.protocol, response)

                if (!mirrorMode && request.method == "RECORD" && response.statusCode == 200) {
                    Logger.d("RTSP handshake complete — switching to RTP interleaved mode")
                    break
                }
            }

            if (mirrorMode) {
                while (running && !socket.isClosed) {
                    val request = readAirPlayRequest(inputStream) ?: break
                    if (!isAirPlayControlRequest(request)) continue
                    val response = controlHandler.handle(request, peerAddress(socket), socket)
                    sendResponse(outputStream, request.protocol, response)
                    if (request.method == "TEARDOWN") break
                }
                return
            }

            val session = currentSession
            if (session != null && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    }
                )
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("Client disconnected (streaming=$streamingStarted)")
            socket.close()
            activeClient = null
            currentSession = null
            setupCount = 0
            macOsMirrorHandshake = false
            if (streamingStarted) onStreamingStopped()
        }
    }

    private fun pathOf(uri: String): String {
        val q = uri.indexOf('?')
        return if (q >= 0) uri.substring(0, q) else uri
    }

    private fun isAirPlayControlRequest(request: RtspRequest): Boolean {
        if (request.uri.startsWith("/")) return true
        if (request.method == "SETUP" && isBinaryPlist(request.bodyBytes)) return true
        if (macOsMirrorHandshake && request.method in MACOS_MIRROR_CONTROL_METHODS) return true
        return false
    }

    private fun isBinaryPlist(body: ByteArray): Boolean =
        body.size >= 6 && body[0] == 'b'.code.toByte() &&
            String(body, 0, 6, Charsets.US_ASCII) == "bplist"

    private fun readAirPlayRequest(inputStream: InputStream): RtspRequest? {
        val requestLine = readLine(inputStream) ?: return null
        if (requestLine.isBlank()) return readAirPlayRequest(inputStream)

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed request line: '$requestLine'")
            return null
        }
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]

        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLine.length

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) break

            totalBytes += line.length
            if (totalBytes > MAX_MESSAGE_BYTES) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] =
                    line.substring(colonIndex + 1).trim()
            }
        }

        currentCSeq = headers["CSeq"]?.toIntOrNull() ?: 0

        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (contentLength in 1..MAX_MESSAGE_BYTES) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inputStream.read(buf, read, contentLength - read)
                if (n == -1) return null
                read += n
            }
            buf
        } else if (contentLength > MAX_MESSAGE_BYTES) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        } else {
            byteArrayOf()
        }
        val body = if (bodyBytes.isEmpty()) "" else String(bodyBytes, Charsets.UTF_8)

        return RtspRequest(
            method = method,
            uri = uri,
            protocol = protocol,
            headers = headers,
            body = body,
            bodyBytes = bodyBytes
        )
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > MAX_MESSAGE_BYTES) return null
        }
    }

    private fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            "SETUP"         -> handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter()
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush()
            "PAUSE"         -> handlePauseInternal(request)
            else            -> handleUnknownInternal(request)
        }
    }

    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession
        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            "RTP/AVP/UDP;unicast;" +
            "client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "timing-port=${TimingHandler.TIMING_PORT}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        val session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        Logger.i("TEARDOWN received — streaming stopping")
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun handleGetParameter(): RtspResponse =
        RtspResponse(statusCode = 200, statusMessage = "OK")

    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        Logger.d("SET_PARAMETER: ${request.body}")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown RTSP method: ${request.method}")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
    }

    private fun handleFlush(): RtspResponse =
        RtspResponse(statusCode = 200, statusMessage = "OK")

    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun sendResponse(outputStream: OutputStream, protocol: String, response: RtspResponse) {
        val bodyBytes = response.bodyBytes
        val sb = StringBuilder()
        sb.append("$protocol ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }
        if (bodyBytes.isNotEmpty()) {
            sb.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        sb.append("\r\n")
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) {
            outputStream.write(bodyBytes)
        }
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        private val MACOS_MIRROR_CONTROL_METHODS = setOf(
            "RECORD", "TEARDOWN", "FLUSH", "GET_PARAMETER", "SET_PARAMETER", "OPTIONS"
        )

        private const val RTSP_PORT = 7000
        private const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
        private const val SESSION_ID = "PhairPlaySession"
        private const val AUDIO_RTP_PORT = 6001
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"
    }
}

// RtspRequest and RtspResponse are defined in RtspMessages.kt

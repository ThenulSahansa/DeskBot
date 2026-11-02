package com.example.deskbot

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GeminiLiveManager(
    private val onResponse: (String) -> Unit,
    private val onSetupComplete: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        // Put your active Gemini API key here
        var API_KEY = "API_KEY"

        // Live model
        var MODEL_ID = "models/gemini-3.8-live"

        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2

        // Fallback: if the ESP32 VOICE_END marker is lost, finalize after this much BLE silence.
        private const val AUDIO_IDLE_END_OF_TURN_MS = 1000L

        // One WebSocket audio message = 50 ms of 16 kHz mono 16-bit PCM.
        private const val SEND_CHUNK_BYTES = 1600

        // Digital silence appended after speech so Gemini's server-side VAD sees the speech end.
        private const val TRAILING_SILENCE_MS = 500

        // A BLE packet counts as "voiced" above this RMS (firmware output scale, before AGC).
        private const val ACTIVE_CHUNK_RMS = 45f

        // A turn is only sent to Gemini once at least 120 ms of voiced audio has been seen.
        // Blips / clicks that slip past the firmware VAD are dropped locally and never reach Gemini.
        private const val MIN_ACTIVE_SAMPLES = SAMPLE_RATE * 120 / 1000

        // Automatic gain control (replaces the old blind 6x gain, which clipped loud speech).
        private const val AGC_TARGET_RMS = 3000f
        private const val AGC_MIN_GAIN = 1f
        private const val AGC_MAX_GAIN = 20f
        private const val AGC_START_GAIN = 8f
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile private var isSetupComplete = false
    @Volatile private var isConnected = false
    @Volatile private var userDisconnected = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Per-turn microphone state (all guarded by turnLock) ----
    private val turnLock = Any()
    private var hasUnfinishedAudioTurn = false
    private var turnStreaming = false            // true once speech is confirmed and audio is being sent
    private val pending = ByteArrayOutputStream() // gained audio not yet sent to Gemini
    private var turnSamples = 0
    private var turnActiveSamples = 0
    private var turnBytesIn = 0
    private var turnPeak = 0
    private var turnPeakRms = 0f
    private var turnFirstPacketMs = 0L
    private var turnLastPacketMs = 0L
    private var agcGain = AGC_START_GAIN

    // ---- Model output / transcript state ----
    private val turnResponseBuffer = StringBuilder()
    private val heardBuffer = StringBuilder()
    private var heardSeq = 0
    @Volatile private var responseStartedThisTurn = false
    @Volatile private var heardShownThisTurn = false

    // Always post TerminalLog calls to the Main UI thread so Jetpack Compose's
    // SnapshotStateList never crashes with ConcurrentModificationException!
    private fun logUi(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            TerminalLog.log(tag, message, level)
        } else {
            mainHandler.post {
                TerminalLog.log(tag, message, level)
            }
        }
    }

    // Live-updating terminal line (used for the "what Gemini heard" transcript).
    private fun logLive(key: String, tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            TerminalLog.upsert(key, tag, message, level)
        } else {
            mainHandler.post {
                TerminalLog.upsert(key, tag, message, level)
            }
        }
    }

    private val idleEndOfTurnRunnable = Runnable {
        val open = synchronized(turnLock) { hasUnfinishedAudioTurn }
        if (open) {
            logUi("GEMINI", "Audio stream idle – finalizing turn", LogLevel.INFO)
            sendEndOfTurn()
        }
    }

    fun connect() {
        userDisconnected = false
        logUi("GEMINI", "Connecting to Gemini Live API...", LogLevel.INFO)

        if (client == null) {
            client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build()
        }

        val request = Request.Builder()
            .url("$WS_URL?key=$API_KEY")
            .addHeader("x-goog-api-key", API_KEY)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logUi("GEMINI", "WebSocket connected, sending setup...", LogLevel.SUCCESS)
                isConnected = true
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logUi("GEMINI", "WebSocket closing ($code): $reason", LogLevel.WARN)
                isConnected = false
                isSetupComplete = false
                resetSessionState()
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {}

                // Auto-reconnect if the server dropped the connection (e.g. code 1011)
                // instead of calling onError -> stopAiSession()
                if (!userDisconnected && (code == 1011 || code == 1006 || code == 1001 || code == 1000)) {
                    logUi("GEMINI", "Reconnecting Gemini Live session in 1.5s...", LogLevel.INFO)
                    mainHandler.postDelayed({
                        if (!userDisconnected) connect()
                    }, 1500L)
                } else if (!userDisconnected) {
                    mainHandler.post { onError("Session closed ($code): $reason") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logUi("GEMINI", "WebSocket closed ($code): $reason", LogLevel.INFO)
                isConnected = false
                isSetupComplete = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: "Unknown WebSocket error"
                logUi("GEMINI", "WebSocket failure: $msg", LogLevel.ERROR)
                isConnected = false
                isSetupComplete = false
                resetSessionState()

                if (!userDisconnected) {
                    logUi("GEMINI", "Retrying connection in 2s...", LogLevel.WARN)
                    mainHandler.postDelayed({
                        if (!userDisconnected) connect()
                    }, 2000L)
                }
            }
        })
    }

    private fun sendSetup(ws: WebSocket) {
        // NOTE: do NOT add realtimeInputConfig here. This model closes the socket with 1007 when
        // server-side VAD is disabled. Server VAD stays on; we end each utterance with
        // `audioStreamEnd` (see sendEndOfTurn) instead.
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL_ID)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                })
                // Transcripts: what Gemini HEARD (input) and what it SAID (output).
                put("outputAudioTranscription", JSONObject())
                put("inputAudioTranscription", JSONObject())
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put(
                                "text",
                                "You are Maya. An AI agent, a concise and friendly desktop companion. " +
                                    "Keep your answers short (1 to 2 sentences). " +
                                    "Answer only what the user has just said in the audio. Never continue or repeat " +
                                    "an earlier topic unless the user brings it up. If the audio is unclear, " +
                                    "silent or just noise, briefly ask the user to repeat instead of guessing."
                            )
                        })
                    })
                })
            })
        }
        ws.send(setupJson.toString())
        logUi("GEMINI", "Setup message sent (model=$MODEL_ID, modality=AUDIO)", LogLevel.INFO)
    }

    // ------------------------------------------------------------------------------------
    // Server -> app
    // ------------------------------------------------------------------------------------

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                val errorCode = errorObj.optInt("code", 0)
                val errorMessage = errorObj.optString("message", text)
                logUi("GEMINI", "API Error ($errorCode): $errorMessage", LogLevel.ERROR)
                mainHandler.post { onError("Gemini ($errorCode): $errorMessage") }
                return
            }

            if (json.has("setupComplete")) {
                logUi("GEMINI", "Setup complete – ready for voice input!", LogLevel.SUCCESS)
                isSetupComplete = true
                mainHandler.post { onSetupComplete() }
                return
            }

            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                // ---- What Gemini HEARD (live transcript of the user's speech) ----
                // Fragments arrive incrementally and already contain their own spaces, so they must
                // be concatenated raw and only trimmed for display.
                val inputFragment = when {
                    serverContent.has("inputTranscription") ->
                        serverContent.getJSONObject("inputTranscription").optString("text", "")
                    serverContent.has("inputAudioTranscription") ->
                        serverContent.getJSONObject("inputAudioTranscription").optString("text", "")
                    else -> ""
                }
                if (inputFragment.isNotEmpty()) {
                    val heardNow: String
                    val key: String
                    synchronized(heardBuffer) {
                        heardBuffer.append(inputFragment)
                        heardNow = heardBuffer.toString().trim()
                        key = "heard-$heardSeq"
                    }
                    if (heardNow.isNotEmpty()) {
                        heardShownThisTurn = true
                        logLive(key, "HEARD", "\"$heardNow\"", LogLevel.INFO)
                    }
                }

                // ---- What Gemini SAYS ----
                val outputFragment = when {
                    serverContent.has("outputTranscription") ->
                        serverContent.getJSONObject("outputTranscription").optString("text", "")
                    serverContent.has("outputAudioTranscription") ->
                        serverContent.getJSONObject("outputAudioTranscription").optString("text", "")
                    else -> ""
                }
                if (outputFragment.isNotEmpty()) {
                    markResponseStarted()
                    synchronized(turnResponseBuffer) {
                        turnResponseBuffer.append(outputFragment)
                    }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text") && !part.optBoolean("thought", false)) {
                                markResponseStarted()
                                synchronized(turnResponseBuffer) {
                                    turnResponseBuffer.append(part.getString("text"))
                                }
                            }
                            if (part.has("inlineData")) {
                                // Audio reply: we only need its transcript, but it still means the
                                // model has finished listening to this turn.
                                markResponseStarted()
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("interrupted", false)) {
                    // User spoke over the reply: drop the partial answer so it can't leak into the next turn.
                    synchronized(turnResponseBuffer) { turnResponseBuffer.setLength(0) }
                    responseStartedThisTurn = false
                }

                val generationComplete = serverContent.optBoolean("generationComplete", false)
                val turnComplete = serverContent.optBoolean("turnComplete", false)

                if (generationComplete || turnComplete) {
                    val fullResponse: String
                    synchronized(turnResponseBuffer) {
                        fullResponse = turnResponseBuffer.toString().trim()
                        turnResponseBuffer.setLength(0)
                    }
                    if (fullResponse.isNotEmpty()) {
                        logUi("GEMINI", "Response: $fullResponse", LogLevel.SUCCESS)
                        onResponse(fullResponse)
                    }
                    if (turnComplete) {
                        finalizeHeardIfPending()
                        if (responseStartedThisTurn && !heardShownThisTurn) {
                            logUi(
                                "HEARD",
                                "(no transcript) Gemini answered without recognising any speech - " +
                                    "the audio was probably too quiet or noisy",
                                LogLevel.WARN
                            )
                        }
                        responseStartedThisTurn = false
                        heardShownThisTurn = false
                        logUi("GEMINI", "Turn complete", LogLevel.INFO)
                    }
                }
            }
        } catch (e: Exception) {
            logUi("GEMINI", "Parse error: ${e.message}", LogLevel.ERROR)
        }
    }

    // The first piece of model output for a turn means the user's speech for that turn has been
    // transcribed: freeze the "HEARD" line and start a fresh one for the next utterance.
    private fun markResponseStarted() {
        if (responseStartedThisTurn) return
        responseStartedThisTurn = true
        finalizeHeardIfPending()
    }

    private fun finalizeHeardIfPending() {
        val heard: String
        val key: String
        synchronized(heardBuffer) {
            heard = heardBuffer.toString().trim()
            if (heard.isEmpty()) return
            heardBuffer.setLength(0)
            key = "heard-$heardSeq"
            heardSeq++
        }
        logLive(key, "HEARD", "\"$heard\"", LogLevel.SUCCESS)
    }

    // ------------------------------------------------------------------------------------
    // App -> server (microphone audio)
    // ------------------------------------------------------------------------------------

    fun sendStartOfTurn() {
        if (!isConnected || !isSetupComplete) return
        synchronized(turnLock) {
            // Idempotent: the ESP32 sends VOICE_START on two characteristics, and audio packets
            // may already be flowing by the time the second marker is processed.
            if (!hasUnfinishedAudioTurn) beginTurnLocked()
        }
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        mainHandler.postDelayed(idleEndOfTurnRunnable, AUDIO_IDLE_END_OF_TURN_MS)
    }

    private fun beginTurnLocked() {
        hasUnfinishedAudioTurn = true
        turnStreaming = false
        pending.reset()
        turnSamples = 0
        turnActiveSamples = 0
        turnBytesIn = 0
        turnPeak = 0
        turnPeakRms = 0f
        turnFirstPacketMs = 0L
        turnLastPacketMs = 0L
        agcGain = agcGain.coerceIn(4f, 16f)
        logUi("GEMINI", "Voice turn started", LogLevel.INFO)
    }

    fun sendAudio(pcmData: ByteArray) {
        // Silently ignore audio packets if WebSocket setup hasn't completed yet
        if (!isConnected || !isSetupComplete || pcmData.size < 2) return

        val n = pcmData.size / 2
        synchronized(turnLock) {
            if (!hasUnfinishedAudioTurn) beginTurnLocked() // tolerate a lost VOICE_START marker

            val now = System.currentTimeMillis()
            if (turnFirstPacketMs == 0L) turnFirstPacketMs = now
            turnLastPacketMs = now
            turnBytesIn += pcmData.size

            // Decode little-endian 16-bit samples and measure level.
            val samples = ShortArray(n)
            var sumSq = 0.0
            var peak = 0
            for (i in 0 until n) {
                val s = ((pcmData[2 * i + 1].toInt() shl 8) or (pcmData[2 * i].toInt() and 0xFF)).toShort()
                samples[i] = s
                val a = abs(s.toInt())
                if (a > peak) peak = a
                sumSq += s.toDouble() * s.toDouble()
            }
            val rms = sqrt(sumSq / n).toFloat()
            if (peak > turnPeak) turnPeak = peak
            if (rms > turnPeakRms) turnPeakRms = rms
            turnSamples += n
            val voiced = rms >= ACTIVE_CHUNK_RMS
            if (voiced) turnActiveSamples += n

            // AGC: drop gain quickly when loud, raise it slowly, and never raise it on background noise.
            val startGain = agcGain
            if (voiced) {
                val desired = (AGC_TARGET_RMS / rms).coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
                val speed = if (desired < agcGain) 0.6f else 0.03f
                agcGain += (desired - agcGain) * speed
            }
            val endGain = agcGain

            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val g = startGain + (endGain - startGain) * (i + 1) / n
                var v = samples[i] * g
                val av = abs(v)
                if (av > 28000f) { // soft limiter instead of hard clipping
                    val limited = 28000f + (av - 28000f) * 0.15f
                    v = if (v < 0f) -limited else limited
                }
                val iv = v.roundToInt().coerceIn(-32768, 32767)
                out[2 * i] = (iv and 0xFF).toByte()
                out[2 * i + 1] = ((iv shr 8) and 0xFF).toByte()
            }
            pending.write(out)

            // Hold audio back until the turn is confirmed to contain real speech.
            if (!turnStreaming && turnActiveSamples >= MIN_ACTIVE_SAMPLES) {
                turnStreaming = true
                logUi("AUDIO", "Speech confirmed – streaming to Gemini", LogLevel.INFO)
            }
            if (turnStreaming) flushPendingLocked(force = false)
        }

        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        mainHandler.postDelayed(idleEndOfTurnRunnable, AUDIO_IDLE_END_OF_TURN_MS)
    }

    // Must be called with turnLock held.
    private fun flushPendingLocked(force: Boolean) {
        if (!turnStreaming || pending.size() == 0) return
        val data = pending.toByteArray()
        var offset = 0
        while (data.size - offset >= SEND_CHUNK_BYTES || (force && offset < data.size)) {
            val end = minOf(offset + SEND_CHUNK_BYTES, data.size)
            sendAudioChunk(data, offset, end)
            offset = end
        }
        pending.reset()
        if (offset < data.size) pending.write(data, offset, data.size - offset)
    }

    private fun sendAudioChunk(data: ByteArray, from: Int, to: Int) {
        val base64Audio = Base64.encodeToString(data, from, to - from, Base64.NO_WRAP)
        // `realtimeInput.audio` is the current field; `mediaChunks` is deprecated.
        val audioJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=$SAMPLE_RATE")
                    put("data", base64Audio)
                })
            })
        }
        webSocket?.send(audioJson.toString())
    }

    fun sendEndOfTurn() {
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        synchronized(turnLock) {
            if (!hasUnfinishedAudioTurn) return // second marker / idle timer after the turn already ended
            hasUnfinishedAudioTurn = false

            val seconds = turnSamples.toFloat() / SAMPLE_RATE
            val voicedMs = turnActiveSamples * 1000 / SAMPLE_RATE
            val peak = turnPeak
            val peakRms = turnPeakRms.roundToInt()
            val gain = agcGain
            val bytesIn = turnBytesIn
            val elapsedMs = turnLastPacketMs - turnFirstPacketMs
            val streaming = turnStreaming
            turnStreaming = false

            if (!isConnected || !isSetupComplete) {
                pending.reset()
                return
            }

            if (!streaming) {
                // Never reached Gemini, so it cannot hallucinate an answer from it.
                pending.reset()
                logUi(
                    "AUDIO",
                    "Ignored non-speech turn (%.1fs, voiced=%dms, peakRms=%d, peak=%d). If real speech keeps landing here, the mic is too quiet - check the INMP441 L/R pin is tied to GND."
                        .format(seconds, voicedMs, peakRms, peak),
                    LogLevel.WARN
                )
                return
            }

            // Everything still buffered, then trailing silence, then tell Gemini the mic stream ended.
            // This is what commits the turn; an empty clientContent.turnComplete would make Gemini
            // answer from the old conversation history instead of from this audio.
            turnStreaming = true
            flushPendingLocked(force = true)
            turnStreaming = false

            val tail = ByteArray(BYTES_PER_SECOND * TRAILING_SILENCE_MS / 1000)
            var off = 0
            while (off < tail.size) {
                val end = minOf(off + SEND_CHUNK_BYTES, tail.size)
                sendAudioChunk(tail, off, end)
                off = end
            }
            val endJson = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            webSocket?.send(endJson.toString())

            logUi(
                "GEMINI",
                "Speech turn sent (%.1fs, voiced=%dms, peakRms=%d, gain=%.1fx)"
                    .format(seconds, voicedMs, peakRms, gain),
                LogLevel.INFO
            )

            // Rough BLE health check: audio should arrive at >= real-time rate (32 KB/s).
            if (elapsedMs > 300) {
                val ratePct = (bytesIn * 1000L * 100L / (BYTES_PER_SECOND.toLong() * elapsedMs)).toInt()
                if (ratePct < 85) {
                    logUi(
                        "AUDIO",
                        "BLE delivered only ~$ratePct% of the expected audio - packets are being lost, which hurts recognition.",
                        LogLevel.WARN
                    )
                }
            }
        }
    }

    private fun resetSessionState() {
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        synchronized(turnLock) {
            hasUnfinishedAudioTurn = false
            turnStreaming = false
            pending.reset()
        }
        synchronized(turnResponseBuffer) { turnResponseBuffer.setLength(0) }
        synchronized(heardBuffer) { heardBuffer.setLength(0) }
        responseStartedThisTurn = false
        heardShownThisTurn = false
    }

    fun disconnect() {
        userDisconnected = true
        logUi("GEMINI", "Disconnecting from Gemini Live API", LogLevel.INFO)
        isConnected = false
        isSetupComplete = false
        resetSessionState()
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
    }

    fun isConnected(): Boolean = isConnected && isSetupComplete
}

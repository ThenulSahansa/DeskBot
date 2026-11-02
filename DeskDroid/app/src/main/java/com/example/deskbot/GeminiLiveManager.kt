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

        private const val AUDIO_IDLE_END_OF_TURN_MS = 900L
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile private var isSetupComplete = false
    @Volatile private var isConnected = false
    @Volatile private var userDisconnected = false
    @Volatile private var hasUnfinishedAudioTurn = false
    @Volatile private var sentAudioChunksInTurn = 0
    @Volatile private var turnMaxPeak = 0
    @Volatile private var lastTurnStartTimeMs = 0L
    @Volatile private var lastTurnEndTimeMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val turnResponseBuffer = StringBuilder()
    private val audioBuffer = ByteArrayOutputStream()
    private val BUFFER_THRESHOLD_BYTES = 1600 // ~50ms of 16kHz mono 16-bit PCM

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

    private val idleEndOfTurnRunnable = Runnable {
        if (hasUnfinishedAudioTurn && sentAudioChunksInTurn > 0) {
            logUi("GEMINI", "Audio stream idle ($sentAudioChunksInTurn chunks) – finalizing turn", LogLevel.INFO)
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
                hasUnfinishedAudioTurn = false
                sentAudioChunksInTurn = 0
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
                hasUnfinishedAudioTurn = false
                sentAudioChunksInTurn = 0

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
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL_ID)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                })
                put("outputAudioTranscription", JSONObject())
                put("inputAudioTranscription", JSONObject())
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put(
                                "text",
                                "You are Maya. An AI agent, a concise and friendly desktop companion. Keep your answers short (1 to 2 sentences)."
                            )
                        })
                    })
                })
            })
        }
        ws.send(setupJson.toString())
        logUi("GEMINI", "Setup message sent (model=$MODEL_ID, modality=AUDIO)", LogLevel.INFO)
    }

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

                val inputTranscript = when {
                    serverContent.has("inputTranscription") ->
                        serverContent.getJSONObject("inputTranscription").optString("text", "").trim()
                    serverContent.has("inputAudioTranscription") ->
                        serverContent.getJSONObject("inputAudioTranscription").optString("text", "").trim()
                    else -> ""
                }
                if (inputTranscript.isNotEmpty()) {
                    logUi("GEMINI", "Heard: $inputTranscript", LogLevel.INFO)
                }

                val outputTranscript = when {
                    serverContent.has("outputTranscription") ->
                        serverContent.getJSONObject("outputTranscription").optString("text", "")
                    serverContent.has("outputAudioTranscription") ->
                        serverContent.getJSONObject("outputAudioTranscription").optString("text", "")
                    else -> ""
                }
                if (outputTranscript.isNotEmpty()) {
                    synchronized(turnResponseBuffer) {
                        turnResponseBuffer.append(outputTranscript)
                    }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text") && !part.optBoolean("thought", false)) {
                                synchronized(turnResponseBuffer) {
                                    turnResponseBuffer.append(part.getString("text"))
                                }
                            }
                        }
                    }
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
                        logUi("GEMINI", "Turn complete", LogLevel.INFO)
                    }
                }
            }
        } catch (e: Exception) {
            logUi("GEMINI", "Parse error: ${e.message}", LogLevel.ERROR)
        }
    }

    fun sendStartOfTurn() {
        val now = System.currentTimeMillis()
        if (now - lastTurnStartTimeMs < 300L) return
        lastTurnStartTimeMs = now

        if (!isConnected || !isSetupComplete) return
        hasUnfinishedAudioTurn = true
        sentAudioChunksInTurn = 0
        turnMaxPeak = 0
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        synchronized(turnResponseBuffer) {
            turnResponseBuffer.setLength(0)
        }
        logUi("GEMINI", "Voice turn started", LogLevel.INFO)
    }

    fun sendAudio(pcmData: ByteArray) {
        // Silently ignore audio packets if WebSocket setup hasn't completed yet
        if (!isConnected || !isSetupComplete || pcmData.isEmpty()) {
            return
        }

        hasUnfinishedAudioTurn = true
        sentAudioChunksInTurn++

        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        mainHandler.postDelayed(idleEndOfTurnRunnable, AUDIO_IDLE_END_OF_TURN_MS)

        // Process audio with 6x software gain to ensure voice is at intelligible levels,
        // and calculate peak amplitude to diagnose silence/noise.
        val boosted = ByteArray(pcmData.size)
        var chunkPeak = 0
        for (i in 0 until pcmData.size - 1 step 2) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val absSample = kotlin.math.abs(sample.toInt())
            if (absSample > chunkPeak) chunkPeak = absSample

            val amplified = (sample * 6.0f).toInt().coerceIn(-32768, 32767)
            boosted[i] = (amplified and 0xFF).toByte()
            boosted[i + 1] = ((amplified shr 8) and 0xFF).toByte()
        }

        if (chunkPeak > turnMaxPeak) {
            turnMaxPeak = chunkPeak
        }

        if (sentAudioChunksInTurn == 1 || sentAudioChunksInTurn % 30 == 0) {
            logUi("AUDIO", "Audio chunk #$sentAudioChunksInTurn (peak=$chunkPeak, gain=6x)", LogLevel.INFO)
        }

        synchronized(audioBuffer) {
            audioBuffer.write(boosted)
            if (audioBuffer.size() >= BUFFER_THRESHOLD_BYTES) {
                flushAudioBufferLocked()
            }
        }
    }

    private fun flushAudioBufferLocked() {
        if (audioBuffer.size() == 0 || !isConnected || !isSetupComplete) return
        val chunk = audioBuffer.toByteArray()
        audioBuffer.reset()

        val base64Audio = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val audioJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    })
                })
            })
        }
        webSocket?.send(audioJson.toString())
    }

    fun sendEndOfTurn() {
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        val now = System.currentTimeMillis()
        if (now - lastTurnEndTimeMs < 300L) return
        lastTurnEndTimeMs = now

        // Only finalize turn if we actually sent audio chunks in this turn
        if (!hasUnfinishedAudioTurn || sentAudioChunksInTurn == 0) {
            return
        }
        hasUnfinishedAudioTurn = false
        val chunksSent = sentAudioChunksInTurn
        val maxPeak = turnMaxPeak
        sentAudioChunksInTurn = 0
        turnMaxPeak = 0

        if (!isConnected || !isSetupComplete) return

        // Flush any remaining buffered audio bytes before ending the turn
        synchronized(audioBuffer) {
            flushAudioBufferLocked()
        }

        // Diagnostic guard: If fewer than 3 chunks or max peak is negligible (< 35),
        // it was just a false trigger or dead silence. Don't send turnComplete to avoid hallucinations!
        if (chunksSent < 3 || maxPeak < 35) {
            logUi("AUDIO", "Ignored silence ($chunksSent chunks, peak=$maxPeak). Check INMP441 L/R pin is tied to GND!", LogLevel.WARN)
            return
        }

        // Commit turn: tell Gemini that the user speech stream is complete
        val endJson = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turnComplete", true)
            })
        }
        webSocket?.send(endJson.toString())
        logUi("GEMINI", "Speech turn committed ($chunksSent audio chunks, peak=$maxPeak)", LogLevel.INFO)
    }

    fun disconnect() {
        userDisconnected = true
        logUi("GEMINI", "Disconnecting from Gemini Live API", LogLevel.INFO)
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        hasUnfinishedAudioTurn = false
        sentAudioChunksInTurn = 0
        turnMaxPeak = 0
        isConnected = false
        isSetupComplete = false
        synchronized(turnResponseBuffer) {
            turnResponseBuffer.setLength(0)
        }
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
    }

    fun isConnected(): Boolean = isConnected && isSetupComplete
}
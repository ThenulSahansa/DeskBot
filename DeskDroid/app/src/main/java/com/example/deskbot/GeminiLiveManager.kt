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
import java.util.concurrent.TimeUnit

class GeminiLiveManager(
    private val onResponse: (String) -> Unit,
    private val onSetupComplete: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        // Put your active Gemini API key here
        var API_KEY = "api-key"

        // Current Gemini Live API model
        var MODEL_ID = "models/gemini-2.5-flash-native-audio-preview-12-2025"

        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Auto-finalize speech turn if no BLE audio packets arrive for 800ms
        private const val AUDIO_IDLE_END_OF_TURN_MS = 800L
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile private var isSetupComplete = false
    @Volatile private var isConnected = false
    @Volatile private var hasUnfinishedAudioTurn = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleEndOfTurnRunnable = Runnable {
        if (hasUnfinishedAudioTurn) {
            TerminalLog.log("GEMINI", "Audio stream idle – auto-sending end of turn", LogLevel.INFO)
            sendEndOfTurn()
        }
    }

    private val turnResponseBuffer = StringBuilder()

    fun connect() {
        TerminalLog.log("GEMINI", "Connecting to Gemini Live API...", LogLevel.INFO)

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$WS_URL?key=$API_KEY")
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                TerminalLog.log("GEMINI", "WebSocket connected, sending setup...", LogLevel.SUCCESS)
                isConnected = true
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            // Gemini Live WebSocket sends all server messages as binary frames (ByteString)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                TerminalLog.log("GEMINI", "WebSocket closing: $code $reason", LogLevel.WARN)
                webSocket.close(1000, null)
                isConnected = false
                isSetupComplete = false
                if (code != 1000) {
                    onError("Session closed ($code): $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                TerminalLog.log("GEMINI", "WebSocket closed: $code $reason", LogLevel.INFO)
                isConnected = false
                isSetupComplete = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: "Unknown WebSocket error"
                TerminalLog.log("GEMINI", "WebSocket failure: $msg", LogLevel.ERROR)
                isConnected = false
                isSetupComplete = false
                onError(msg)
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
                                "You are DeskBot, a concise and friendly desktop companion. Keep your answers short (1 to 2 sentences) so they fit cleanly on a 128x64 OLED screen."
                            )
                        })
                    })
                })
            })
        }
        ws.send(setupJson.toString())
        TerminalLog.log(
            "GEMINI",
            "Setup message sent (model=$MODEL_ID)",
            LogLevel.INFO
        )
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                TerminalLog.log("GEMINI", "Setup complete – ready for audio input", LogLevel.SUCCESS)
                isSetupComplete = true
                onSetupComplete()
                return
            }

            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                if (serverContent.has("inputTranscription")) {
                    val inputTranscript = serverContent.getJSONObject("inputTranscription")
                        .optString("text", "")
                    if (inputTranscript.isNotEmpty()) {
                        TerminalLog.log("GEMINI", "Heard: $inputTranscript", LogLevel.INFO)
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val outputTranscript = serverContent.getJSONObject("outputTranscription")
                        .optString("text", "")
                    if (outputTranscript.isNotEmpty()) {
                        synchronized(turnResponseBuffer) {
                            turnResponseBuffer.append(outputTranscript)
                        }
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
                        TerminalLog.log("GEMINI", "Response: $fullResponse", LogLevel.SUCCESS)
                        onResponse(fullResponse)
                    }
                    if (turnComplete) {
                        TerminalLog.log("GEMINI", "Turn complete", LogLevel.INFO)
                    }
                }
            }
        } catch (e: Exception) {
            TerminalLog.log("GEMINI", "Parse error: ${e.message}", LogLevel.ERROR)
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected || !isSetupComplete) {
            TerminalLog.log("GEMINI", "Skipping audio chunk: Gemini setup not complete yet", LogLevel.WARN)
            return
        }

        hasUnfinishedAudioTurn = true
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        mainHandler.postDelayed(idleEndOfTurnRunnable, AUDIO_IDLE_END_OF_TURN_MS)

        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val audioJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }

        webSocket?.send(audioJson.toString())
    }

    fun sendEndOfTurn() {
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        hasUnfinishedAudioTurn = false
        if (!isConnected || !isSetupComplete) return

        val endJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audioStreamEnd", true)
            })
        }

        webSocket?.send(endJson.toString())
        TerminalLog.log("GEMINI", "Audio stream end signal sent", LogLevel.INFO)
    }

    fun disconnect() {
        TerminalLog.log("GEMINI", "Disconnecting from Gemini Live API", LogLevel.INFO)
        mainHandler.removeCallbacks(idleEndOfTurnRunnable)
        hasUnfinishedAudioTurn = false
        isConnected = false
        isSetupComplete = false
        synchronized(turnResponseBuffer) {
            turnResponseBuffer.setLength(0)
        }
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        client = null
    }

    fun isConnected(): Boolean = isConnected && isSetupComplete
}
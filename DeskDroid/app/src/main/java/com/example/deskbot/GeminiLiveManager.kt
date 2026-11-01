package com.example.deskbot

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveManager(
    private val onResponse: (String) -> Unit,
    private val onSetupComplete: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        var API_KEY = "AIzaSyDriOQWl_CU5y9TByR6M6W0CzhI0uPJxBk"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var isSetupComplete = false
    private var isConnected = false

    fun connect() {
        TerminalLog.log("GEMINI", "Connecting to Gemini Live API…", LogLevel.INFO)

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$WS_URL?key=$API_KEY")
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                TerminalLog.log("GEMINI", "WebSocket connected, sending setup…", LogLevel.SUCCESS)
                isConnected = true
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                TerminalLog.log("GEMINI", "WebSocket closing: $code $reason", LogLevel.WARN)
                webSocket.close(1000, null)
                isConnected = false
                isSetupComplete = false
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
                put("model", "models/gemini-2.0-flash-live")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("TEXT")
                    })
                })
            })
        }
        ws.send(setupJson.toString())
        TerminalLog.log("GEMINI", "Setup message sent (model=gemini-2.0-flash-live, modality=TEXT)", LogLevel.INFO)
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Check for setupComplete
            if (json.has("setupComplete")) {
                TerminalLog.log("GEMINI", "Setup complete — ready for audio input", LogLevel.SUCCESS)
                isSetupComplete = true
                onSetupComplete()
                return
            }

            // Check for serverContent with text parts
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        val responseBuilder = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                responseBuilder.append(part.getString("text"))
                            }
                        }
                        val responseText = responseBuilder.toString()
                        if (responseText.isNotEmpty()) {
                            TerminalLog.log("GEMINI", "Response: $responseText", LogLevel.SUCCESS)
                            onResponse(responseText)
                        }
                    }
                }

                // Check if turn is complete
                if (serverContent.optBoolean("turnComplete", false)) {
                    TerminalLog.log("GEMINI", "Turn complete", LogLevel.INFO)
                }
            }
        } catch (e: Exception) {
            TerminalLog.log("GEMINI", "Parse error: ${e.message}", LogLevel.ERROR)
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected || !isSetupComplete) return

        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)

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
        if (!isConnected || !isSetupComplete) return

        val endJson = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("activityEnd", JSONObject())
            })
        }

        webSocket?.send(endJson.toString())
        TerminalLog.log("GEMINI", "End of turn signal sent", LogLevel.INFO)
    }

    fun disconnect() {
        TerminalLog.log("GEMINI", "Disconnecting from Gemini Live API", LogLevel.INFO)
        isConnected = false
        isSetupComplete = false
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

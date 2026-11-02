package com.example.deskbot

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val id: Long = 0L,
    val timestamp: String,
    val tag: String,     // BLE, AUDIO, GEMINI, DISPLAY, SYSTEM
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

object TerminalLog {
    val entries = mutableStateListOf<LogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idCounter = AtomicLong(0L)
    private const val MAX_ENTRIES = 500

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            level = level
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addEntryInternal(entry)
        } else {
            mainHandler.post {
                addEntryInternal(entry)
            }
        }
    }

    private fun addEntryInternal(entry: LogEntry) {
        while (entries.size >= MAX_ENTRIES) {
            entries.removeAt(0)
        }
        entries.add(entry)
    }

    fun clear() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            entries.clear()
        } else {
            mainHandler.post {
                entries.clear()
            }
        }
    }
}

package com.example.deskbot

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val tag: String,     // BLE, AUDIO, GEMINI, DISPLAY, SYSTEM
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

object TerminalLog {
    val entries = mutableStateListOf<LogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(timeFormat.format(Date()), tag, message, level)
        entries.add(entry)
        // Keep max 500 entries
        while (entries.size > 500) entries.removeAt(0)
    }

    fun clear() = entries.clear()
}

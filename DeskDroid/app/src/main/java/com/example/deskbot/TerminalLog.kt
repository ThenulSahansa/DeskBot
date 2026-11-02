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
    val tag: String,     // BLE, AUDIO, GEMINI, HEARD, DISPLAY, SYSTEM
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

    // key -> entry id, for lines that are updated in place (e.g. the live "HEARD" transcript).
    // Only touched on the main thread.
    private val keyedIds = HashMap<String, Long>()

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

    /**
     * Adds a line, or rewrites the existing line registered under [key]. Used to show a transcript
     * that grows while Gemini is still recognising speech, without spamming one line per fragment.
     */
    fun upsert(key: String, tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val action = Runnable {
            val existingId = keyedIds[key]
            val index = if (existingId != null) entries.indexOfFirst { it.id == existingId } else -1
            if (index >= 0) {
                entries[index] = entries[index].copy(message = message, level = level)
            } else {
                val entry = LogEntry(
                    id = idCounter.incrementAndGet(),
                    timestamp = timeFormat.format(Date()),
                    tag = tag,
                    message = message,
                    level = level
                )
                if (keyedIds.size > 100) keyedIds.clear()
                keyedIds[key] = entry.id
                addEntryInternal(entry)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
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
            keyedIds.clear()
        } else {
            mainHandler.post {
                entries.clear()
                keyedIds.clear()
            }
        }
    }
}

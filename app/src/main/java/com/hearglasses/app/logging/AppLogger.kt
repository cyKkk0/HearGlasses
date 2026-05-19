package com.hearglasses.app.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AppLogger(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(capacity = LOG_BUFFER_CAPACITY)

    val logFile: File = File(
        File(appContext.filesDir, LOG_DIR_NAME).also { it.mkdirs() },
        "hearglasses-${FILE_FORMATTER.format(LocalDateTime.now())}.log",
    )

    val logFilePath: String get() = logFile.absolutePath

    init {
        scope.launch {
            for (line in logChannel) {
                runCatching {
                    logFile.appendText(line + "\n")
                }.onFailure {
                    Log.w(TAG, "Failed to write log: ${it.message}")
                }
            }
        }
        info("app_logger_start", "path=$logFilePath")
    }

    fun info(event: String, message: String = "") {
        enqueue("INFO", event, message)
    }

    fun warn(event: String, message: String = "") {
        enqueue("WARN", event, message)
    }

    fun error(event: String, message: String = "") {
        enqueue("ERROR", event, message)
    }

    private fun enqueue(level: String, event: String, message: String) {
        val timestamp = LINE_FORMATTER.format(LocalDateTime.now())
        val sanitizedMessage = message
            .replace('\n', ' ')
            .replace('\r', ' ')
        val line = "$timestamp $level $event $sanitizedMessage"
        logChannel.trySend(line)
    }

    private companion object {
        const val TAG = "HearGlassesLog"
        const val LOG_DIR_NAME = "logs"
        const val LOG_BUFFER_CAPACITY = 512
        val FILE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val LINE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}

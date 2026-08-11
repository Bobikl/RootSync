package com.rootsync.android.diagnostics

import android.content.Context
import android.net.Uri
import com.rootsync.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DiagnosticLogger(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val directory = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val currentFile = File(directory, "rootsync-diagnostic.log")
    private val previousFile = File(directory, "rootsync-diagnostic.previous.log")

    fun append(level: String, message: String) {
        if (message.isBlank()) return
        val sanitized = message
            .replace('\u0000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT)
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                OutputStreamWriter(
                    FileOutputStream(currentFile, true),
                    StandardCharsets.UTF_8
                ).use { writer ->
                    sanitized.lineSequence().forEach { line ->
                        writer.append(timestamp)
                            .append(" [")
                            .append(level.take(16))
                            .append("] ")
                            .append(line)
                            .append('\n')
                    }
                }
            }
        }
    }

    suspend fun export(uri: Uri, diagnosticHeader: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val output = requireNotNull(appContext.contentResolver.openOutputStream(uri, "wt")) {
                    "无法打开导出目标"
                }
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                    writer.append("RootSync 详细诊断日志\n")
                    writer.append("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                    writer.append("导出时间：${LocalDateTime.now().format(EXPORT_TIME_FORMAT)}\n")
                    writer.append("日志编码：UTF-8\n\n")
                    writer.append(diagnosticHeader.trim()).append("\n\n")
                    synchronized(lock) {
                        if (previousFile.isFile) {
                            writer.append("===== 上一段持久日志 =====\n")
                            previousFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                                reader.copyTo(writer)
                            }
                            writer.append('\n')
                        }
                        writer.append("===== 当前持久日志 =====\n")
                        if (currentFile.isFile) {
                            currentFile.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                                reader.copyTo(writer)
                            }
                        } else {
                            writer.append("（暂无持久日志）\n")
                        }
                    }
                }
                Unit
            }
        }

    fun adbReadCommand(): String =
        "adb shell run-as ${BuildConfig.APPLICATION_ID} cat files/logs/${currentFile.name}"

    fun pathDescription(): String = "files/logs/${currentFile.name}"

    private fun rotateIfNeeded() {
        if (!currentFile.isFile || currentFile.length() < MAX_LOG_BYTES) return
        if (previousFile.exists() && !previousFile.delete()) return
        currentFile.renameTo(previousFile)
    }

    private companion object {
        const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val EXPORT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

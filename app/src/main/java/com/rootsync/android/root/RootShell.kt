package com.rootsync.android.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

data class CommandResult(
    val exitCode: Int,
    val output: List<String>,
    val totalOutputLines: Int = output.size
) {
    val text: String get() = output.joinToString("\n")
}

class RootShell {
    private val activeProcess = AtomicReference<Process?>(null)

    suspend fun execute(
        command: String,
        onLine: (String) -> Unit = {},
        maxCapturedLines: Int = DEFAULT_MAX_CAPTURED_LINES
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        activeProcess.set(process)
        val lines = ArrayDeque<String>()
        var totalOutputLines = 0
        try {
            process.inputStream.bufferedReader().useLines { stream ->
                stream.forEach { line ->
                    totalOutputLines += 1
                    if (maxCapturedLines > 0) {
                        if (lines.size >= maxCapturedLines) lines.removeFirst()
                        lines.addLast(line)
                    }
                    onLine(line)
                }
            }
            CommandResult(process.waitFor(), lines.toList(), totalOutputLines)
        } finally {
            activeProcess.compareAndSet(process, null)
        }
    }

    fun cancel() {
        activeProcess.getAndSet(null)?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        const val DEFAULT_MAX_CAPTURED_LINES = 2_000
    }
}

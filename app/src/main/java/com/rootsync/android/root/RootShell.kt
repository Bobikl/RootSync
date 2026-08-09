package com.rootsync.android.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class CommandResult(
    val exitCode: Int,
    val output: List<String>
) {
    val text: String get() = output.joinToString("\n")
}

class RootShell {
    private val activeProcess = AtomicReference<Process?>(null)

    suspend fun execute(
        command: String,
        onLine: (String) -> Unit = {}
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        activeProcess.set(process)
        val lines = mutableListOf<String>()
        try {
            process.inputStream.bufferedReader().useLines { stream ->
                stream.forEach { line ->
                    lines += line
                    onLine(line)
                }
            }
            CommandResult(process.waitFor(), lines)
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
}

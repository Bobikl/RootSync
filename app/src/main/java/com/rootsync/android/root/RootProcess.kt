package com.rootsync.android.root

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared argv transport for authorization and execution. Only a daemon reader blocks on pipes;
 * the coroutine polls a bounded queue, so cancellation never waits for readLine()/waitFor().
 */
internal object RootProcess {
    fun start(args: List<String>): Process =
        ProcessBuilder(args).redirectErrorStream(true).start()

    suspend fun execute(
        args: List<String>,
        onLine: (String) -> Unit = {},
        maxCapturedLines: Int = 2_000,
        startProcess: (List<String>) -> Process = ::start
    ): CommandResult {
        currentCoroutineContext().ensureActive()
        val process = startProcess(args)
        val queue = ArrayBlockingQueue<String>(256)
        val done = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val failure = AtomicReference<Exception?>(null)
        var reader: Thread? = null
        try {
            // Cancellation can occur inside native start(). Once it returns, enter
            // cleanup immediately rather than opening streams/starting a reader.
            currentCoroutineContext().ensureActive()
            // Commands are noninteractive; do not leave a prompt waiting on our stdin.
            process.outputStream.close()
            reader = thread(name = "root-output", isDaemon = true) {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                        while (!stopped.get()) {
                            val line = input.readLine() ?: break
                            queue.put(line)
                        }
                    }
                } catch (error: Exception) {
                    if (!stopped.get()) failure.set(error)
                } finally {
                    done.set(true)
                }
            }
            val captured = ArrayDeque<String>()
            var total = 0
            var exitedAt: Long? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                // Bound each batch so continuous output cannot starve cancellation.
                for (index in 0 until 256) {
                    val line = queue.poll() ?: break
                    total++
                    if (maxCapturedLines > 0) {
                        if (captured.size >= maxCapturedLines) captured.removeFirst()
                        captured.addLast(line)
                    }
                    onLine(line)
                }
                failure.get()?.let { throw it }
                if (!process.isAlive) {
                    if (done.get() && queue.isEmpty()) {
                        return CommandResult(process.exitValue(), captured.toList(), total)
                    }
                    // A descendant can inherit stdout after su exits. Do not hang forever or
                    // report truncated output as success. This is NOT a command runtime limit.
                    val now = System.nanoTime()
                    if (exitedAt == null) exitedAt = now
                    if (now - exitedAt > 2_000_000_000L && queue.isEmpty()) {
                        throw IOException("ROOT 进程已退出，但输出管道未关闭")
                    }
                }
                delay(10)
            }
        } finally {
            stopped.set(true)
            reader?.interrupt()
            // Stream close/destroy may itself contend with a native read on some Android
            // runtimes. Keep teardown off the caller/cancellation path.
            thread(name = "root-cleanup", isDaemon = true) {
                runCatching { if (process.isAlive) process.destroyForcibly() }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }
}
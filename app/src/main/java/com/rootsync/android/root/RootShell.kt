package com.rootsync.android.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class CommandResult(
    val exitCode: Int,
    val output: List<String>,
    val totalOutputLines: Int = output.size
) {
    val text: String get() = output.joinToString("\n")
}

class RootShell internal constructor(private val startProcess: (List<String>) -> Process) {
    constructor() : this(RootProcess::start)

    private val activeJob = AtomicReference<Job?>(null)
    private val executeMutex = Mutex()

    // No execution deadline: rsync may transfer for hours, including silent periods.
    suspend fun execute(
        command: String,
        onLine: (String) -> Unit = {},
        maxCapturedLines: Int = 2_000
    ): CommandResult = executeMutex.withLock {
        coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            activeJob.set(job)
            try {
                withContext(Dispatchers.IO) {
                    RootProcess.execute(listOf("su", "-c", command), onLine, maxCapturedLines, startProcess)
                }
            } finally {
                activeJob.compareAndSet(job, null)
            }
        }
    }

    fun cancel() {
        activeJob.get()?.cancel(CancellationException("ROOT 命令已取消"))
    }
}
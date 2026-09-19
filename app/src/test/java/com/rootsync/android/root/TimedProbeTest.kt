package com.rootsync.android.root

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TimedProbeTest {
    @Test fun returnsResultAndStageTiming() = runBlocking {
        val logs = mutableListOf<String>()
        val result = TimedProbe.run("中文", 1000, logs::add) { CommandResult(0, listOf("ready")) }
        assertEquals(0, result.exitCode)
        assertTrue(logs.first().contains("PROBE_START name=中文"))
        assertTrue(logs.last().contains("elapsedMs="))
    }
    @Test fun timeoutCancelsCommandAndReturns124() = runBlocking {
        var cleaned = false
        val result = TimedProbe.run("timeout", 30) {
            try { delay(10000); CommandResult(0, emptyList()) } finally { cleaned = true }
        }
        assertEquals(124, result.exitCode)
        assertTrue(cleaned)
    }
    @Test fun exceptionIsReported() = runBlocking {
        assertEquals(126, TimedProbe.run("error", 1000) { error("unavailable") }.exitCode)
    }
    @Test fun parentCancellationPropagates() = runBlocking {
        var returned = false
        val started = CompletableDeferred<Unit>()
        val task = launch {
            TimedProbe.run("cancel", 10000) { started.complete(Unit); awaitCancellation() }
            returned = true
        }
        started.await()
        task.cancelAndJoin()
        assertFalse(returned)
        assertTrue(task.isCancelled)
    }
}

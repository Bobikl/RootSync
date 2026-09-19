package com.rootsync.android.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RootTransportTest {
    @Test fun identityAllowsBannerButRequiresUnambiguousUidZeroAndSuccess() {
        assertTrue(RootAuthorization.isRootIdentity(CommandResult(0, listOf(" 0 "))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(1, listOf("0"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(0, listOf("2000"))))
        assertTrue(RootAuthorization.isRootIdentity(CommandResult(0, listOf("warning", "0"))))
        assertTrue(RootAuthorization.isRootIdentity(CommandResult(0, listOf("0", "manager banner"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(0, listOf("uid=0"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(0, listOf("0", "2000"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(0, listOf("0", "0"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(1, listOf("banner", "0"))))
        assertFalse(RootAuthorization.isRootIdentity(CommandResult(0, emptyList())))
    }

    @Test fun managerNamesAreOptionalAndImplementationSpecific() {
        assertEquals("KernelSU", RootAuthorization.parseManager("v1.0:KernelSU"))
        assertEquals("Magisk", RootAuthorization.parseManager("30.0:MAGISKSU"))
        assertNull(RootAuthorization.parseManager("some other su"))
        assertNull(RootAuthorization.parseManager(""))
        // A valid identity does not depend on version parsing.
        assertTrue(RootAuthorization.isRootIdentity(CommandResult(0, listOf("0"))))
    }

    @Test fun executionUsesSingleCommandArgumentAndKeepsTail() = runBlocking {
        val command = "printf '中文; spaces'"
        val seen = mutableListOf<String>()
        val shell = RootShell { args ->
            assertEquals(listOf("su", "-c", command), args)
            FinishedProcess("一\n二\n三\n".toByteArray(Charsets.UTF_8), 7)
        }
        val result = shell.execute(command, seen::add, 2)
        assertEquals(listOf("一", "二", "三"), seen)
        assertEquals(listOf("二", "三"), result.output)
        assertEquals(3, result.totalOutputLines)
        assertEquals(7, result.exitCode)
    }

    @Test fun disabledCaptureStillStreamsAndCounts() = runBlocking {
        val seen = mutableListOf<String>()
        val result = RootShell { FinishedProcess("a\nb\n".toByteArray(), 0) }
            .execute("test", seen::add, 0)
        assertTrue(result.output.isEmpty())
        assertEquals(2, result.totalOutputLines)
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun timeoutCancelsSilentProcessAndDestroysIt() = runBlocking {
        val process = SilentProcess()
        val result = withTimeoutOrNull(100) {
            RootProcess.execute(listOf("su", "-c", "id -u"), startProcess = { process })
        }
        assertNull(result)
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
    }

    @Test fun explicitCancelUnblocksSilentReadAndAllowsReuse() = runBlocking {
        val process = SilentProcess()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val shell = RootShell {
            if (calls++ == 0) {
                started.complete(Unit)
                process
            } else FinishedProcess("ok\n".toByteArray(), 0)
        }
        val work = async { shell.execute("silent") }
        started.await()
        shell.cancel()
        withTimeout(2_000) {
            try {
                work.await()
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                // Expected.
            }
        }
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
        assertEquals("ok", shell.execute("next").text)
    }

    @Test fun parentCancellationDestroysProcess() = runBlocking {
        val process = SilentProcess()
        val started = CompletableDeferred<Unit>()
        val work = async {
            RootShell { started.complete(Unit); process }.execute("silent")
        }
        started.await()
        work.cancel()
        withTimeout(2_000) { work.join() }
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
    }

    @Test fun silentExecutionHasNoAuthorizationDeadline() = runBlocking {
        val process = SilentProcess()
        val started = CompletableDeferred<Unit>()
        val work = async {
            RootShell { started.complete(Unit); process }.execute("rsync")
        }
        started.await()
        // Longer than the automatic authorization budget; execution must remain alive.
        delay(RootAuthorization.CHECK_TIMEOUT_MS + 100)
        assertTrue(work.isActive)
        work.cancel()
        withTimeout(2_000) { work.join() }
    }

    @Test fun cancelledBeforeStartDoesNotCreateProcess() = runBlocking {
        val called = AtomicBoolean(false)
        val work = async {
            currentCoroutineContext()[Job]!!.cancel()
            RootProcess.execute(listOf("su", "-c", "id -u"), startProcess = {
                called.set(true)
                FinishedProcess(byteArrayOf(), 0)
            })
        }
        work.join()
        assertTrue(work.isCancelled)
        assertFalse(called.get())
    }

    @Test fun cancellationDuringStartCleansReturnedProcess() = runBlocking {
        val process = SilentProcess()
        val work = async {
            val job = currentCoroutineContext()[Job]!!
            RootProcess.execute(listOf("su", "-c", "id -u"), startProcess = {
                job.cancel()
                process
            })
        }
        withTimeout(2_000) { work.join() }
        assertTrue(work.isCancelled)
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS))
    }

    private class FinishedProcess(bytes: ByteArray, private val code: Int) : Process() {
        private val input = ByteArrayInputStream(bytes)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun exitValue(): Int = code
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
    }

    private class SilentProcess : Process() {
        private val input = PipedInputStream()
        private val writer = PipedOutputStream(input)
        private val alive = AtomicBoolean(true)
        val destroyed = CountDownLatch(1)
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { destroyed.await(); return 137 }
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException()
            return 137
        }
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() {
            alive.set(false)
            writer.close()
            destroyed.countDown()
        }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
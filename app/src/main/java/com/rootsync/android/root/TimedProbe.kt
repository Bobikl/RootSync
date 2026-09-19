package com.rootsync.android.root
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Short diagnostic commands only; long transfers remain unlimited. */
internal object TimedProbe {
    suspend fun run(name: String, timeoutMillis: Long, onLog: (String) -> Unit = {},
        command: suspend () -> CommandResult): CommandResult {
        val start = System.nanoTime()
        onLog("PROBE_START name=$name budgetMs=$timeoutMillis")
        val result = try {
            withTimeoutOrNull(timeoutMillis) { command() }
                ?: CommandResult(124, listOf("检查超时（${timeoutMillis}ms），已停止等待"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CommandResult(126, listOf(error.message ?: error.javaClass.simpleName))
        }
        onLog("PROBE_END name=$name elapsedMs=${(System.nanoTime() - start) / 1_000_000} exit=${result.exitCode}")
        return result
    }
}

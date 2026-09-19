package com.rootsync.android.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class RootAuthorizationResult(
    val granted: Boolean,
    val detail: String,
    val managerName: String? = null
)

object RootAuthorization {
    private val requestMutex = Mutex()
    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detectionStarted = AtomicBoolean(false)

    private val mutableManagerState = MutableStateFlow<String?>(null)
    val managerState: StateFlow<String?> = mutableManagerState.asStateFlow()

    /** Best-effort snapshot; null means unknown, never that ROOT is unavailable. */
    val managerName: String? get() = managerState.value

    // Explicit user retry allows time to answer a manager prompt; automatic checks stay short.
    internal const val CHECK_TIMEOUT_MS = 3_000L
    internal const val REQUEST_TIMEOUT_MS = 10_000L

    suspend fun request(packageName: String, forceRetry: Boolean): RootAuthorizationResult =
        requestMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Always revalidate. No positive/negative libsu cache or interactive handshake.
                    val identity = withTimeoutOrNull(
                        if (forceRetry) REQUEST_TIMEOUT_MS else CHECK_TIMEOUT_MS
                    ) {
                        RootProcess.execute(listOf("su", "-c", "id -u"), maxCapturedLines = 32)
                    } ?: return@withContext RootAuthorizationResult(
                        false,
                        "等待 ROOT 授权超时；请在 ROOT 管理器中允许 $packageName，然后重试",
                        managerName
                    )
                    if (!isRootIdentity(identity)) {
                        return@withContext RootAuthorizationResult(
                            false,
                            "未获得 UID 0（退出码 ${identity.exitCode}）；请在 ROOT 管理器中允许 $packageName" +
                                identity.text.take(512).takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty(),
                            managerName
                        )
                    }
                    detectManagerOnce()
                    RootAuthorizationResult(true, "UID 0 已授权（$packageName）", managerName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RootAuthorizationResult(
                        false,
                        "无法执行 su；请确认 ROOT 管理器已启用并允许 $packageName：" +
                            (error.message ?: error.javaClass.simpleName).take(512),
                        managerName
                    )
                }
            }
        }

    internal fun isRootIdentity(result: CommandResult): Boolean {
        // su may print a banner to merged stderr/stdout. Accept exactly one numeric
        // UID line, not a substring such as "uid=0" or conflicting numeric identities.
        val uidLines = result.output.map(String::trim)
            .filter { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
        return result.exitCode == 0 && uidLines == listOf("0")
    }

    internal fun parseManager(version: String): String? = when {
        version.contains("KernelSU", ignoreCase = true) -> "KernelSU"
        version.contains("MAGISK", ignoreCase = true) -> "Magisk"
        else -> null
    }

    private fun detectManagerOnce() {
        if (!detectionStarted.compareAndSet(false, true)) return
        detectionScope.launch {
            try {
                // Informational -v only, once per app process, AFTER UID 0. No su -c,
                // interactive shell, magisk fallback, installed-package guesses, or retry.
                // Verified upstream: KernelSU userspace/ksud/src/su.rs and
                // Magisk native/src/core/su/su.cpp return their version for -v.
                val version = withTimeoutOrNull(750L) {
                    RootProcess.execute(listOf("su", "-v"), maxCapturedLines = 8)
                }
                if (version?.exitCode == 0) mutableManagerState.value = parseManager(version.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional metadata must never invalidate successful authorization.
            }
        }
    }
}
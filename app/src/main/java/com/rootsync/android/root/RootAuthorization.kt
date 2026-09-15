package com.rootsync.android.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RootAuthorizationResult(
    val granted: Boolean,
    val detail: String
)

object RootAuthorization {
    private val requestMutex = Mutex()

    suspend fun request(packageName: String, forceRetry: Boolean): RootAuthorizationResult =
        requestMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val cached = Shell.getCachedShell()
                    if (forceRetry && cached != null && !cached.isRoot) {
                        cached.close()
                    }

                    val rootShell = Shell.getShell()
                    if (!rootShell.isRoot) {
                        return@withContext deniedResult(packageName)
                    }

                    val stdout = mutableListOf<String>()
                    val stderr = mutableListOf<String>()
                    val identity = rootShell.newJob()
                        .add("id -u")
                        .to(stdout, stderr)
                        .exec()
                    val uidIsRoot = identity.code == 0 && stdout.any { it.trim() == "0" }
                    if (!uidIsRoot) {
                        val detail = (stdout + stderr).joinToString("\n").trim()
                        RootAuthorizationResult(
                            granted = false,
                            detail = detail.ifBlank {
                                "ROOT shell 已建立，但未返回 UID 0；请在 ROOT 管理器中重新授权 $packageName"
                            }
                        )
                    } else {
                        RootAuthorizationResult(
                            granted = true,
                            detail = "UID 0 已授权（$packageName）"
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    RootAuthorizationResult(
                        granted = false,
                        detail = failureDetail(packageName, error)
                    )
                }
            }
        }

    private fun deniedResult(packageName: String): RootAuthorizationResult {
        val grantState = Shell.isAppGrantedRoot()
        val detail = if (grantState == false) {
            "ROOT 请求被拒绝或 su 不可用；请在 Magisk/ROOT 管理器的超级用户列表中允许 $packageName，然后再次请求"
        } else {
            "未获得 ROOT shell；请确认 Magisk/ROOT 管理器已启用，并允许 $packageName 的超级用户请求"
        }
        return RootAuthorizationResult(granted = false, detail = detail)
    }

    private fun failureDetail(packageName: String, error: Exception): String {
        val causeText = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        val typeText = generateSequence<Throwable>(error) { it.cause }
            .joinToString("/") { it::class.java.simpleName }
        val prefix = if (
            typeText.contains("timeout", ignoreCase = true) ||
            causeText?.contains("timeout", ignoreCase = true) == true ||
            causeText?.contains("timed out", ignoreCase = true) == true
        ) {
            "等待 ROOT 授权超时"
        } else {
            "无法启动 ROOT shell"
        }
        return "$prefix；请确认 Magisk/ROOT 管理器已启用并允许 $packageName" +
            causeText?.let { "：$it" }.orEmpty()
    }
}

package com.rootsync.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rootsync.android.domain.CapabilityCheck
import com.rootsync.android.domain.CheckState
import com.rootsync.android.domain.DeviceCapabilities
import com.rootsync.android.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

data class EngineResult(
    val success: Boolean,
    val summary: String,
    val exitCode: Int = 0
)

class RootSyncEngine(private val context: Context) {
    private val shell = RootShell()
    private val runtimeDir = File(context.filesDir, "runtime").apply { mkdirs() }
    private val metadataDir = File(runtimeDir, "metadata").apply { mkdirs() }
    private val syncMetaPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libsyncmeta.so").absolutePath
    private val bundledRsyncPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "librsync.so").absolutePath

    suspend fun probe(sourcePath: String, destinationPath: String): DeviceCapabilities {
        val checks = mutableListOf<CapabilityCheck>()
        val root = try {
            shell.execute("id -u")
        } catch (error: Exception) {
            return DeviceCapabilities(
                checks = listOf(
                    CapabilityCheck("ROOT 授权", CheckState.FAIL, error.message ?: "无法启动 su")
                )
            )
        }
        val hasRoot = root.exitCode == 0 && root.output.any { it.trim() == "0" }
        checks += CapabilityCheck(
            "ROOT 授权",
            if (hasRoot) CheckState.PASS else CheckState.FAIL,
            if (hasRoot) "UID 0 已授权" else root.text.ifBlank { "su 未返回 UID 0" }
        )
        if (!hasRoot) return DeviceCapabilities(checks = checks)

        val bundledVersionResult = shell.execute(
            "${SafeInput.shellQuote(bundledRsyncPath)} --version 2>/dev/null | head -n 1"
        )
        val bundledReady = bundledVersionResult.exitCode == 0 &&
            bundledVersionResult.text.contains("rsync", ignoreCase = true)
        val externalPath = if (!bundledReady) findExternalRsync() else null
        val rsyncPath = if (bundledReady) bundledRsyncPath else externalPath
        val rsyncVersion = when {
            bundledReady -> bundledVersionResult.output.firstOrNull()?.trim()?.plus(" · 内置")
            externalPath != null -> shell.execute(
                "${SafeInput.shellQuote(externalPath)} --version 2>/dev/null | head -n 1"
            ).output.firstOrNull()?.trim()?.plus(" · 外部")
            else -> null
        }
        checks += CapabilityCheck(
            "rsync 引擎",
            if (rsyncPath != null) CheckState.PASS else CheckState.FAIL,
            rsyncVersion ?: "内置 rsync 无法执行，且未找到外部版本"
        )

        val meta = shell.execute("${SafeInput.shellQuote(syncMetaPath)} version")
        val metaReady = meta.exitCode == 0 && meta.text.contains("syncmeta")
        checks += CapabilityCheck(
            "目录时间工具",
            if (metaReady) CheckState.PASS else CheckState.FAIL,
            meta.output.firstOrNull() ?: "arm64 原生工具不可执行"
        )

        val detectedPath = detectKnownMediaPath()
        val effectiveSource = when {
            sourcePath == com.rootsync.android.domain.SyncUiState.LEGACY_BILI_PATH && detectedPath != null -> detectedPath
            sourcePath == com.rootsync.android.domain.SyncUiState.DEFAULT_BILI_PATH && detectedPath != null -> detectedPath
            else -> sourcePath
        }
        val sourceError = SafeInput.validateStoragePath(effectiveSource)
        val sourceResult = if (sourceError == null) {
            shell.execute(
                "test -d ${SafeInput.shellQuote(effectiveSource)} && " +
                    "test -r ${SafeInput.shellQuote(effectiveSource)}"
            )
        } else null
        checks += CapabilityCheck(
            "发送源目录",
            when {
                sourceError != null -> CheckState.FAIL
                sourceResult?.exitCode == 0 -> CheckState.PASS
                else -> CheckState.WARNING
            },
            sourceError ?: if (sourceResult?.exitCode == 0) {
                if (effectiveSource == sourcePath) "ROOT 可读取" else "已自动识别：$effectiveSource"
            } else "目录不存在；请确认应用版本和实际下载目录"
        )

        val destinationError = SafeInput.validateStoragePath(destinationPath)
        val destinationResult = if (destinationError == null) {
            shell.execute(
                "mkdir -p ${SafeInput.shellQuote(destinationPath)} && " +
                    "test -d ${SafeInput.shellQuote(destinationPath)} && " +
                    "test -w ${SafeInput.shellQuote(destinationPath)}"
            )
        } else null
        checks += CapabilityCheck(
            "本机接收目录",
            when {
                destinationError != null -> CheckState.FAIL
                destinationResult?.exitCode == 0 -> CheckState.PASS
                else -> CheckState.FAIL
            },
            destinationError ?: if (destinationResult?.exitCode == 0) {
                "ROOT 可写；原目录不存在时已自动创建"
            } else "无法创建或写入该目录"
        )

        return DeviceCapabilities(
            rootGranted = true,
            rsyncPath = rsyncPath,
            rsyncVersion = rsyncVersion,
            syncMetaReady = metaReady,
            detectedMediaPath = detectedPath,
            checks = checks
        )
    }

    private suspend fun findExternalRsync(): String? {
        val result = shell.execute(
            "command -v rsync 2>/dev/null || " +
                "for p in /data/adb/modules/*/system/bin/rsync /system/bin/rsync /system/xbin/rsync; " +
                "do [ -x \"\$p\" ] && echo \"\$p\" && break; done"
        )
        return result.output.firstOrNull { it.trim().startsWith('/') }?.trim()
    }

    private suspend fun detectKnownMediaPath(): String? {
        val candidates = listOf(
            "/storage/emulated/0/Android/data/tv.danmaku.bili/download",
            "/storage/emulated/0/Android/data/com.danmaku.bili/download",
            "/storage/emulated/0/Android/data/com.bstar.intl/download"
        )
        val command = candidates.joinToString("; ") { path ->
            "if [ -d ${SafeInput.shellQuote(path)} ]; then echo ${SafeInput.shellQuote(path)}; exit 0; fi"
        }
        return shell.execute(command).output.firstOrNull { it.startsWith("/storage/") }?.trim()
    }

    suspend fun startServer(
        rsyncPath: String,
        sourcePath: String,
        destinationPath: String,
        port: Int,
        secret: String,
        onLog: (String) -> Unit
    ): EngineResult = withContext(Dispatchers.IO) {
        SafeInput.validateStoragePath(sourcePath)?.let { return@withContext EngineResult(false, it) }
        SafeInput.validateStoragePath(destinationPath)?.let { return@withContext EngineResult(false, it) }
        if (secret.length < com.rootsync.android.domain.SyncUiState.MIN_SECRET_LENGTH) {
            return@withContext EngineResult(false, "配对密钥至少需要 6 位")
        }

        val sourceReady = shell.execute(
            "test -d ${SafeInput.shellQuote(sourcePath)} && test -r ${SafeInput.shellQuote(sourcePath)}"
        )
        if (sourceReady.exitCode != 0) {
            return@withContext EngineResult(false, "发送源目录不存在或不可读：$sourcePath")
        }
        val destinationReady = shell.execute(
            "mkdir -p ${SafeInput.shellQuote(destinationPath)} && test -w ${SafeInput.shellQuote(destinationPath)}"
        )
        if (destinationReady.exitCode != 0) {
            return@withContext EngineResult(false, "接收目录无法创建或不可写：$destinationPath")
        }

        val bindAddress = localIpv4()
        if (!SafeInput.isValidIpv4(bindAddress)) {
            return@withContext EngineResult(false, "未找到可绑定的 Wi-Fi IPv4 地址")
        }

        onLog("正在生成发送源目录时间快照…")
        val snapshot = File(metadataDir, "source.snapshot")
        val snapshotResult = shell.execute(
            listOf(syncMetaPath, "snapshot", sourcePath, snapshot.absolutePath)
                .joinToString(" ") { SafeInput.shellQuote(it) },
            onLog
        )
        if (snapshotResult.exitCode != 0) {
            return@withContext EngineResult(false, "目录时间快照失败", snapshotResult.exitCode)
        }

        val secrets = File(runtimeDir, "rsync.secrets")
        secrets.writeText("sync-user:$secret\n")
        secrets.setReadable(false, false)
        secrets.setReadable(true, true)
        secrets.setWritable(false, false)
        secrets.setWritable(true, true)

        val config = File(runtimeDir, "rsyncd.conf")
        val pidFile = File(runtimeDir, "rsyncd.pid")
        val lockFile = File(runtimeDir, "rsyncd.lock")
        val logFile = File(runtimeDir, "rsyncd.log")
        config.writeText(
            """
            pid file = ${pidFile.absolutePath}
            lock file = ${lockFile.absolutePath}
            log file = ${logFile.absolutePath}
            use chroot = false
            uid = 0
            gid = 0
            list = false
            max connections = 1
            timeout = 300
            strict modes = true
            address = $bindAddress

            [send]
            path = $sourcePath
            read only = true
            auth users = sync-user
            secrets file = ${secrets.absolutePath}

            [receive]
            path = $destinationPath
            read only = false
            write only = true
            munge symlinks = true
            auth users = sync-user
            secrets file = ${secrets.absolutePath}

            [meta]
            path = ${metadataDir.absolutePath}
            read only = true
            auth users = sync-user
            secrets file = ${secrets.absolutePath}
            """.trimIndent()
        )

        stopServer(onLog = {})
        onLog("正在启动受限服务端（send 只读、receive 只写）…")
        val result = shell.execute(
            "${SafeInput.shellQuote(rsyncPath)} --daemon --port=$port " +
                "--config=${SafeInput.shellQuote(config.absolutePath)}",
            onLog
        )
        if (result.exitCode != 0) {
            return@withContext EngineResult(false, "rsync 服务启动失败", result.exitCode)
        }
        val check = shell.execute(
            "test -s ${SafeInput.shellQuote(pidFile.absolutePath)} && " +
                "kill -0 \$(cat ${SafeInput.shellQuote(pidFile.absolutePath)})"
        )
        if (check.exitCode == 0) {
            EngineResult(true, "服务端已启动：支持只发送与只接收")
        } else {
            EngineResult(false, "rsync 未保持运行，请查看服务端日志", check.exitCode)
        }
    }

    suspend fun stopServer(onLog: (String) -> Unit): EngineResult {
        val pidFile = File(runtimeDir, "rsyncd.pid")
        val configFile = File(runtimeDir, "rsyncd.conf")
        val command =
            "pid_file=${SafeInput.shellQuote(pidFile.absolutePath)}; " +
                "if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
                "if [ -r \"/proc/\$pid/cmdline\" ] && " +
                "tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | " +
                "grep -Fq ${SafeInput.shellQuote(configFile.absolutePath)}; then " +
                "kill \"\$pid\" 2>/dev/null; fi ;; esac; " +
                "rm -f \"\$pid_file\"; fi"
        val result = shell.execute(command, onLog)
        return if (result.exitCode == 0) EngineResult(true, "服务端已停止")
        else EngineResult(false, "停止服务端失败", result.exitCode)
    }

    suspend fun pull(
        rsyncPath: String,
        host: String,
        port: Int,
        destinationPath: String,
        secret: String,
        mirror: Boolean,
        dryRun: Boolean,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): EngineResult = withContext(Dispatchers.IO) {
        if (!SafeInput.isValidIpv4(host)) return@withContext EngineResult(false, "请输入有效 IPv4 地址")
        SafeInput.validateStoragePath(destinationPath)?.let { return@withContext EngineResult(false, it) }
        val destinationReady = shell.execute(
            "mkdir -p ${SafeInput.shellQuote(destinationPath)} && test -w ${SafeInput.shellQuote(destinationPath)}"
        )
        if (destinationReady.exitCode != 0) {
            return@withContext EngineResult(false, "本机接收目录无法创建或不可写")
        }

        val password = writeClientPassword(secret)
        val remoteSnapshot = File(runtimeDir, "remote.snapshot")
        if (!dryRun) {
            onLog("下载发送端目录时间清单…")
            val metadataCommand = listOf(
                rsyncPath, "-rt", "--timeout=60", "--contimeout=15",
                "--password-file=${password.absolutePath}",
                "rsync://sync-user@$host:$port/meta/source.snapshot",
                remoteSnapshot.absolutePath
            ).joinToString(" ") { SafeInput.shellQuote(it) }
            val metadataResult = shell.execute(metadataCommand, onLog)
            if (metadataResult.exitCode != 0) {
                return@withContext EngineResult(false, "无法获取目录时间清单", metadataResult.exitCode)
            }
        }

        onLog(if (dryRun) "开始只接收差异预览…" else "开始只接收增量传输…")
        val command = RsyncCommandBuilder.pull(
            rsyncPath = rsyncPath,
            host = host,
            port = port,
            destination = destinationPath,
            passwordFile = password.absolutePath,
            mirror = mirror,
            dryRun = dryRun
        )
        val transfer = executeTransfer(command, onLog, onProgress)
        if (transfer.exitCode != 0) {
            return@withContext EngineResult(false, "rsync 返回错误 ${transfer.exitCode}", transfer.exitCode)
        }
        if (dryRun) return@withContext EngineResult(true, previewSummary(mirror))

        shell.execute(
            "find ${SafeInput.shellQuote(destinationPath)} -depth -type d " +
                "-name .rsync-partial -empty -delete 2>/dev/null || true"
        )
        onLog("按目录深度从深到浅恢复 mtime…")
        val restore = shell.execute(
            listOf(syncMetaPath, "restore", destinationPath, remoteSnapshot.absolutePath)
                .joinToString(" ") { SafeInput.shellQuote(it) },
            onLog
        )
        if (restore.exitCode != 0) {
            return@withContext EngineResult(false, "文件已接收，但目录时间恢复失败", restore.exitCode)
        }
        val verify = shell.execute(
            listOf(syncMetaPath, "verify", destinationPath, remoteSnapshot.absolutePath, "2000000000")
                .joinToString(" ") { SafeInput.shellQuote(it) },
            onLog
        )
        if (verify.exitCode != 0) {
            EngineResult(false, "接收完成，但部分目录时间超过 2 秒误差", verify.exitCode)
        } else {
            onProgress(1f)
            EngineResult(true, "只接收完成，目录时间验证通过")
        }
    }

    suspend fun push(
        rsyncPath: String,
        host: String,
        port: Int,
        sourcePath: String,
        secret: String,
        mirror: Boolean,
        dryRun: Boolean,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): EngineResult = withContext(Dispatchers.IO) {
        if (!SafeInput.isValidIpv4(host)) return@withContext EngineResult(false, "请输入有效 IPv4 地址")
        SafeInput.validateStoragePath(sourcePath)?.let { return@withContext EngineResult(false, it) }
        val sourceReady = shell.execute(
            "test -d ${SafeInput.shellQuote(sourcePath)} && test -r ${SafeInput.shellQuote(sourcePath)}"
        )
        if (sourceReady.exitCode != 0) {
            return@withContext EngineResult(false, "本机发送源目录不存在或不可读")
        }

        val password = writeClientPassword(secret)
        onLog(if (dryRun) "开始只发送差异预览…" else "开始只发送增量传输…")
        val command = RsyncCommandBuilder.push(
            rsyncPath = rsyncPath,
            host = host,
            port = port,
            source = sourcePath,
            passwordFile = password.absolutePath,
            mirror = mirror,
            dryRun = dryRun
        )
        val transfer = executeTransfer(command, onLog, onProgress)
        if (transfer.exitCode != 0) {
            return@withContext EngineResult(false, "rsync 返回错误 ${transfer.exitCode}", transfer.exitCode)
        }
        if (dryRun) EngineResult(true, previewSummary(mirror))
        else {
            onProgress(1f)
            EngineResult(true, "只发送完成；文件与目录 mtime 已交由 rsync 保留")
        }
    }

    private suspend fun executeTransfer(
        command: String,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ) = shell.execute(command) { line ->
        onLog(line)
        Regex("(?:^|\\s)([0-9]{1,3})%(?:\\s|$)")
            .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                onProgress(it.coerceIn(0, 100) / 100f)
            }
    }

    private fun writeClientPassword(secret: String): File = File(runtimeDir, "client.password").apply {
        writeText(secret + "\n")
        setReadable(false, false)
        setReadable(true, true)
        setWritable(false, false)
        setWritable(true, true)
    }

    private fun previewSummary(mirror: Boolean): String = if (mirror) {
        "预览完成；镜像删除硬限制为 100 项"
    } else {
        "预览完成；仅新增和更新，不删除目标端独有内容"
    }

    fun cancel() = shell.cancel()

    fun generateSecret(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun localIpv4(): String = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val isWifi = network != null && connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            "未连接 Wi-Fi"
        } else {
            connectivity.getLinkProperties(network)
                ?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.address
                ?.hostAddress
                ?: "未连接 Wi-Fi"
        }
    } catch (_: Exception) {
        "未连接 Wi-Fi"
    }
}

package com.rootsync.android.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import com.rootsync.android.domain.CapabilityCheck
import com.rootsync.android.domain.CheckState
import com.rootsync.android.domain.DeviceCapabilities
import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.root.CommandResult
import com.rootsync.android.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class EngineResult(
    val success: Boolean,
    val summary: String,
    val exitCode: Int = 0
)

enum class DestinationDirectoryState {
    READY,
    MISSING,
    UNWRITABLE,
    INVALID
}

data class DestinationDirectoryCheck(
    val state: DestinationDirectoryState,
    val message: String
)

class RootSyncEngine(private val context: Context) {
    private val shell = RootShell()
    private val controlShell = RootShell()
    private val watchdogShell = RootShell()
    private val runtimeDir = File(context.filesDir, "runtime").apply { mkdirs() }
    private val metadataDir = File(runtimeDir, "metadata").apply { mkdirs() }
    private val syncMetaPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libsyncmeta.so").absolutePath
    private val bundledRsyncPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "librsync.so").absolutePath
    private val appProcessId = Process.myPid()
    private val appProcessName = context.packageName

    suspend fun cleanupStaleRuntimeProcesses(onLog: (String) -> Unit): EngineResult =
        withContext(Dispatchers.IO) {
            val result = controlShell.execute(runtimeCleanupCommand(includeWatchdog = true), onLog)
            if (result.exitCode == 0) {
                EngineResult(true, "已清理上次异常退出遗留的 rsync 进程")
            } else {
                EngineResult(false, "遗留 rsync 进程清理失败", result.exitCode)
            }
        }

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
                "test -d ${SafeInput.shellQuote(destinationPath)} && " +
                    "test -w ${SafeInput.shellQuote(destinationPath)}"
            )
        } else null
        val destinationExists = destinationError == null && shell.execute(
            "test -d ${SafeInput.shellQuote(destinationPath)}"
        ).exitCode == 0
        checks += CapabilityCheck(
            "本机接收目录",
            when {
                destinationError != null -> CheckState.FAIL
                destinationResult?.exitCode == 0 -> CheckState.PASS
                !destinationExists -> CheckState.WARNING
                else -> CheckState.FAIL
            },
            destinationError ?: if (destinationResult?.exitCode == 0) {
                "ROOT 可写"
            } else if (!destinationExists) {
                "目录不存在；接收任务开始时会询问是否创建"
            } else "目录存在但无法写入"
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

    suspend fun inspectDestinationDirectory(path: String): DestinationDirectoryCheck =
        withContext(Dispatchers.IO) {
            SafeInput.validateStoragePath(path)?.let {
                return@withContext DestinationDirectoryCheck(DestinationDirectoryState.INVALID, it)
            }
            val result = shell.execute(
                "if [ -d ${SafeInput.shellQuote(path)} ]; then " +
                    "if [ -w ${SafeInput.shellQuote(path)} ]; then echo READY; else echo UNWRITABLE; fi; " +
                    "elif [ -e ${SafeInput.shellQuote(path)} ]; then echo UNWRITABLE; " +
                    "else echo MISSING; fi"
            )
            when (result.output.firstOrNull()?.trim()) {
                "READY" -> DestinationDirectoryCheck(DestinationDirectoryState.READY, "接收目录已存在且可写")
                "MISSING" -> DestinationDirectoryCheck(DestinationDirectoryState.MISSING, "接收目录不存在")
                else -> DestinationDirectoryCheck(DestinationDirectoryState.UNWRITABLE, "接收目录存在但不可写")
            }
        }

    suspend fun createDestinationDirectory(path: String): EngineResult = withContext(Dispatchers.IO) {
        SafeInput.validateStoragePath(path)?.let { return@withContext EngineResult(false, it) }
        val result = shell.execute(
            "mkdir -p ${SafeInput.shellQuote(path)} && " +
                "test -d ${SafeInput.shellQuote(path)} && test -w ${SafeInput.shellQuote(path)}"
        )
        if (result.exitCode == 0) {
            EngineResult(true, "已创建接收目录：$path")
        } else {
            EngineResult(false, "接收目录创建失败或不可写：$path", result.exitCode)
        }
    }

    suspend fun startServer(
        rsyncPath: String,
        sourcePath: String,
        destinationPath: String,
        port: Int,
        secret: String,
        onLog: (String) -> Unit,
        mode: SyncRole? = null,
        rangeMode: SyncRangeMode = SyncRangeMode.ALL,
        sinceEpochMillis: Long? = null,
        untilEpochMillis: Long = System.currentTimeMillis(),
        allowCreateDestination: Boolean = false
    ): EngineResult = withContext(Dispatchers.IO) {
        onLog(
            "DIAG_SERVER_PREPARE mode=${mode?.name ?: "MANUAL"} range=${rangeMode.name} " +
                "port=$port source=$sourcePath destination=$destinationPath"
        )
        SafeInput.validateStoragePath(sourcePath)?.let { return@withContext EngineResult(false, it) }
        SafeInput.validateStoragePath(destinationPath)?.let { return@withContext EngineResult(false, it) }
        if (secret.length < com.rootsync.android.domain.SyncUiState.MIN_SECRET_LENGTH) {
            return@withContext EngineResult(false, "配对密钥至少需要 6 位")
        }
        validateTimeRange(rangeMode, sinceEpochMillis, untilEpochMillis)?.let {
            return@withContext EngineResult(false, it)
        }

        val servesSendModule = mode != SyncRole.RECEIVE_ONLY
        val servesReceiveModule = mode != SyncRole.SEND_ONLY
        if (servesSendModule) {
            val sourceReady = shell.execute(
                "test -d ${SafeInput.shellQuote(sourcePath)} && test -r ${SafeInput.shellQuote(sourcePath)}"
            )
            if (sourceReady.exitCode != 0) {
                return@withContext EngineResult(false, "发送源目录不存在或不可读：$sourcePath")
            }
        }
        if (servesReceiveModule) {
            val destinationReady = shell.execute(
                if (allowCreateDestination) {
                    "mkdir -p ${SafeInput.shellQuote(destinationPath)} && " +
                        "test -d ${SafeInput.shellQuote(destinationPath)} && test -w ${SafeInput.shellQuote(destinationPath)}"
                } else {
                    "test -d ${SafeInput.shellQuote(destinationPath)} && test -w ${SafeInput.shellQuote(destinationPath)}"
                }
            )
            if (destinationReady.exitCode != 0) {
                return@withContext EngineResult(
                    false,
                    if (allowCreateDestination) {
                        "接收目录无法创建或不可写：$destinationPath"
                    } else {
                        "接收目录不存在或不可写：$destinationPath"
                    }
                )
            }
        }

        val bindAddress = localIpv4()
        if (!SafeInput.isValidIpv4(bindAddress)) {
            return@withContext EngineResult(false, "未找到可绑定的 Wi-Fi IPv4 地址")
        }
        onLog("DIAG_SERVER_BIND address=$bindAddress port=$port rsync=$rsyncPath")

        val snapshot = File(metadataDir, "source.snapshot")
        if (servesSendModule) {
            onLog("正在生成发送源目录时间快照…")
            val snapshotResult = shell.execute(
                listOf(syncMetaPath, "snapshot", sourcePath, snapshot.absolutePath)
                    .joinToString(" ") { SafeInput.shellQuote(it) },
                onLog
            )
            if (snapshotResult.exitCode != 0) {
                return@withContext EngineResult(false, "目录时间快照失败", snapshotResult.exitCode)
            }
            if (rangeMode == SyncRangeMode.SINCE) {
                val sourceFileList = File(metadataDir, "source.files")
                onLog("正在生成指定时间范围的发送文件清单…")
                val listResult = createTimeFileList(
                    rootPath = sourcePath,
                    output = sourceFileList,
                    sinceEpochMillis = requireNotNull(sinceEpochMillis),
                    untilEpochMillis = untilEpochMillis,
                    onLog = onLog
                )
                if (!listResult.success) return@withContext listResult
            }
        }

        val secrets = File(runtimeDir, "rsync.secrets")
        if (!writeRootOwnedSecret(secrets, "sync-user:$secret")) {
            return@withContext EngineResult(false, "无法创建 ROOT 所有的 rsync 密钥文件")
        }

        val config = File(runtimeDir, "rsyncd.conf")
        val pidFile = File(runtimeDir, "rsyncd.pid")
        val lockFile = File(runtimeDir, "rsyncd.lock")
        val logFile = File(runtimeDir, "rsyncd.log")
        val modules = buildString {
            if (servesSendModule) append(
                """
                [send]
                path = $sourcePath
                read only = true
                auth users = sync-user
                secrets file = ${secrets.absolutePath}

                [meta]
                path = ${metadataDir.absolutePath}
                read only = true
                auth users = sync-user
                secrets file = ${secrets.absolutePath}
                """.trimIndent()
            )
            if (servesSendModule && servesReceiveModule) append("\n\n")
            if (servesReceiveModule) append(
                """
                [receive]
                path = $destinationPath
                read only = false
                write only = true
                munge symlinks = true
                auth users = sync-user
                secrets file = ${secrets.absolutePath}
                """.trimIndent()
            )
        }
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
            refuse options = delete remove-source-files remove-sent-files inplace append append-verify
            address = $bindAddress

            $modules
            """.trimIndent() + "\n"
        )

        val stopResult = stopServer(onLog)
        onLog("DIAG_SERVER_STOP success=${stopResult.success} exit=${stopResult.exitCode} summary=${stopResult.summary}")
        if (!stopResult.success) {
            return@withContext EngineResult(false, "无法停止旧 rsync 服务：${stopResult.summary}")
        }
        var portReleased = !isLocalPortListening(port)
        repeat(15) {
            if (!portReleased) {
                delay(100)
                portReleased = !isLocalPortListening(port)
            }
        }
        if (!portReleased) {
            val detail = serverFailureDetail(logFile, emptyList(), onLog)
            return@withContext EngineResult(
                false,
                "端口 $port 仍被旧服务或其他程序占用${detail?.let { "：$it" }.orEmpty()}",
                98
            )
        }
        onLog(
            when (mode) {
                SyncRole.SEND_ONLY -> "正在为远端接收请求准备 send 模块…"
                SyncRole.RECEIVE_ONLY -> "正在为远端发送请求准备 receive 模块…"
                SyncRole.BIDIRECTIONAL -> "正在准备双向同步的 send 与 receive 模块…"
                null -> "正在启动受限服务端（send 只读、receive 只写）…"
            }
        )
        val launchMarker = "ROOTSYNC_START_${backupRunId()}"
        shell.execute(
            "printf '%s\\n' ${SafeInput.shellQuote(launchMarker)} >> " +
                SafeInput.shellQuote(logFile.absolutePath)
        )
        val result = shell.execute(
            "${SafeInput.shellQuote(rsyncPath)} --daemon --port=$port " +
                "--config=${SafeInput.shellQuote(config.absolutePath)}",
            onLog
        )
        onLog("DIAG_SERVER_LAUNCH exit=${result.exitCode} outputLines=${result.output.size}")
        if (result.exitCode != 0) {
            val detail = serverFailureDetail(logFile, result.output, onLog)
            return@withContext EngineResult(
                false,
                "rsync 服务启动失败${detail?.let { "：$it" }.orEmpty()}",
                result.exitCode
            )
        }
        val check = shell.execute(
            "pid_file=${SafeInput.shellQuote(pidFile.absolutePath)}; attempt=0; " +
                "while [ \"\$attempt\" -lt 30 ]; do " +
                "if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
                "if kill -0 \"\$pid\" 2>/dev/null; then exit 0; fi ;; esac; fi; " +
                "attempt=\$((attempt + 1)); sleep 0.1; done; exit 1"
        )
        var listening = false
        if (check.exitCode == 0) {
            repeat(20) {
                if (!listening) {
                    listening = isLocalPortListening(port)
                    if (!listening) delay(100)
                }
            }
        }
        if (check.exitCode == 0 && listening) {
            ensureLifecycleWatchdog(rsyncPath, onLog)
            onLog("DIAG_SERVER_READY pidCheck=0 listening=true address=$bindAddress port=$port")
            EngineResult(
                true,
                when (mode) {
                    SyncRole.SEND_ONLY -> "服务端已启动：本机发送源已可供远端接收"
                    SyncRole.RECEIVE_ONLY -> "服务端已启动：本机接收目录已可供远端发送"
                    SyncRole.BIDIRECTIONAL -> "服务端已启动：支持本次双向同步"
                    null -> "服务端已启动：支持只发送与只接收"
                }
            )
        } else {
            onLog(
                "DIAG_SERVER_NOT_READY pidCheck=${check.exitCode} listening=$listening " +
                    "checkLines=${check.output.size}"
            )
            val detail = serverFailureDetail(logFile, result.output + check.output, onLog)
            EngineResult(
                false,
                if (check.exitCode != 0) {
                    "rsync 后台进程未保持运行${detail?.let { "：$it" }.orEmpty()}"
                } else {
                    "rsync 进程已启动但端口 $port 不可访问${detail?.let { "：$it" }.orEmpty()}"
                },
                if (check.exitCode != 0) check.exitCode else 10
            )
        }
    }

    suspend fun stopServer(onLog: (String) -> Unit): EngineResult {
        val pidFile = File(runtimeDir, "rsyncd.pid")
        val configFile = File(runtimeDir, "rsyncd.conf")
        val command =
            "pid_file=${SafeInput.shellQuote(pidFile.absolutePath)}; " +
                "status=0; if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
                "if [ -r \"/proc/\$pid/cmdline\" ] && " +
                "tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | " +
                "grep -Fq ${SafeInput.shellQuote(configFile.absolutePath)}; then " +
                "kill \"\$pid\" 2>/dev/null; attempt=0; " +
                "while kill -0 \"\$pid\" 2>/dev/null && [ \"\$attempt\" -lt 20 ]; do " +
                "attempt=\$((attempt + 1)); sleep 0.1; done; " +
                "if kill -0 \"\$pid\" 2>/dev/null; then kill -KILL \"\$pid\" 2>/dev/null; sleep 0.1; fi; " +
                "if kill -0 \"\$pid\" 2>/dev/null; then echo '旧 rsync 进程无法停止'; status=1; fi; " +
                "fi ;; esac; rm -f \"\$pid_file\"; fi; exit \"\$status\""
        val result = shell.execute(command, onLog)
        return if (result.exitCode == 0) EngineResult(true, "服务端已停止")
        else EngineResult(false, "停止服务端失败", result.exitCode)
    }

    private suspend fun serverFailureDetail(
        logFile: File,
        launchOutput: List<String>,
        onLog: (String) -> Unit
    ): String? {
        val tail = shell.execute(
            "if [ -f ${SafeInput.shellQuote(logFile.absolutePath)} ]; then " +
                "tail -n 40 ${SafeInput.shellQuote(logFile.absolutePath)}; fi"
        ).output
        val lines = (launchOutput + RsyncServerLogParser.currentLaunchLines(tail)).takeLast(40)
        lines.forEach { onLog("rsyncd：$it") }
        if (lines.isEmpty()) {
            onLog("rsyncd：未写出详细错误；已完成 PID、端口和旧进程检查")
            return null
        }
        return RsyncServerLogParser.summarize(lines)
    }

    suspend fun pull(
        rsyncPath: String,
        host: String,
        port: Int,
        destinationPath: String,
        secret: String,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?,
        untilEpochMillis: Long,
        bidirectional: Boolean,
        dryRun: Boolean,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onItem: (RsyncItem) -> Unit,
        onTransferredBytes: (Long) -> Unit = {}
    ): EngineResult = withContext(Dispatchers.IO) {
        if (!SafeInput.isValidIpv4(host)) return@withContext EngineResult(false, "请输入有效 IPv4 地址")
        SafeInput.validateStoragePath(destinationPath)?.let { return@withContext EngineResult(false, it) }
        validateTimeRange(rangeMode, sinceEpochMillis, untilEpochMillis)?.let {
            return@withContext EngineResult(false, it)
        }
        val destinationReady = shell.execute(
            "test -d ${SafeInput.shellQuote(destinationPath)} && test -w ${SafeInput.shellQuote(destinationPath)}"
        )
        if (destinationReady.exitCode != 0) {
            return@withContext EngineResult(false, "本机接收目录不存在或不可写；未获得确认时不会自动创建")
        }

        val password = File(runtimeDir, "client.password")
        if (!writeRootOwnedSecret(password, secret)) {
            return@withContext EngineResult(false, "无法准备 ROOT 所有的客户端密钥文件")
        }
        val remoteSnapshot = File(runtimeDir, "remote.snapshot")
        val remoteFileList = File(runtimeDir, "remote.files")
        if (rangeMode == SyncRangeMode.SINCE) {
            onLog("下载发送端指定时间范围文件清单…")
            val listResult = downloadMetadata(
                rsyncPath = rsyncPath,
                host = host,
                port = port,
                passwordFile = password,
                remoteName = "source.files",
                destination = remoteFileList,
                onLog = onLog
            )
            if (!listResult.success) {
                return@withContext EngineResult(
                    false,
                    "无法获取指定时间范围文件清单",
                    listResult.exitCode
                )
            }
        }
        if (!dryRun) {
            onLog("下载发送端目录时间清单…")
            val snapshotResult = downloadMetadata(
                rsyncPath = rsyncPath,
                host = host,
                port = port,
                passwordFile = password,
                remoteName = "source.snapshot",
                destination = remoteSnapshot,
                onLog = onLog
            )
            if (!snapshotResult.success) {
                return@withContext EngineResult(false, "无法获取目录时间清单", snapshotResult.exitCode)
            }
        }

        val operation = if (bidirectional) "双向同步·接收阶段" else "只接收"
        onLog(if (dryRun) "开始${operation}差异预览…" else "开始${operation}增量传输…")
        val integrityCapture = TransferIntegrityCapture()
        val command = RsyncCommandBuilder.pull(
            rsyncPath = rsyncPath,
            host = host,
            port = port,
            destination = destinationPath,
            passwordFile = password.absolutePath,
            backupRunId = backupRunId(),
            filesFrom = remoteFileList.absolutePath.takeIf { rangeMode == SyncRangeMode.SINCE },
            bidirectional = bidirectional,
            dryRun = dryRun
        )
        val transfer = executeTransfer(
            command,
            onLog,
            onProgress,
            onItem,
            onTransferredBytes,
            integrityCapture.takeUnless { dryRun }
        )
        if (transfer.exitCode != 0) {
            return@withContext EngineResult(
                false,
                rsyncFailureSummary(transfer.exitCode, transfer.text),
                transfer.exitCode
            )
        }
        if (dryRun) return@withContext EngineResult(true, previewSummary())

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
            return@withContext EngineResult(false, "接收完成，但部分目录时间超过 2 秒误差", verify.exitCode)
        }
        val integrity = verifyTransferredData(
            rsyncPath = rsyncPath,
            host = host,
            port = port,
            localPath = destinationPath,
            passwordFile = password,
            direction = VerificationDirection.PULL,
            capture = integrityCapture,
            onLog = onLog
        )
        if (!integrity.success) return@withContext integrity
        onProgress(1f)
        EngineResult(
            true,
            "${if (bidirectional) "双向同步接收阶段" else "只接收"}完成，数据完整性校验通过；" +
                "未删除目标端数据，覆盖前版本已保存到 .rootsync-history"
        )
    }

    suspend fun push(
        rsyncPath: String,
        host: String,
        port: Int,
        sourcePath: String,
        secret: String,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?,
        untilEpochMillis: Long,
        bidirectional: Boolean,
        dryRun: Boolean,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onItem: (RsyncItem) -> Unit,
        onTransferredBytes: (Long) -> Unit = {}
    ): EngineResult = withContext(Dispatchers.IO) {
        if (!SafeInput.isValidIpv4(host)) return@withContext EngineResult(false, "请输入有效 IPv4 地址")
        SafeInput.validateStoragePath(sourcePath)?.let { return@withContext EngineResult(false, it) }
        validateTimeRange(rangeMode, sinceEpochMillis, untilEpochMillis)?.let {
            return@withContext EngineResult(false, it)
        }
        val sourceReady = shell.execute(
            "test -d ${SafeInput.shellQuote(sourcePath)} && test -r ${SafeInput.shellQuote(sourcePath)}"
        )
        if (sourceReady.exitCode != 0) {
            return@withContext EngineResult(false, "本机发送源目录不存在或不可读")
        }

        val password = File(runtimeDir, "client.password")
        if (!writeRootOwnedSecret(password, secret)) {
            return@withContext EngineResult(false, "无法准备 ROOT 所有的客户端密钥文件")
        }
        val localFileList = File(runtimeDir, "local.files")
        if (rangeMode == SyncRangeMode.SINCE) {
            onLog("正在生成本机指定时间范围文件清单…")
            val listResult = createTimeFileList(
                rootPath = sourcePath,
                output = localFileList,
                sinceEpochMillis = requireNotNull(sinceEpochMillis),
                untilEpochMillis = untilEpochMillis,
                onLog = onLog
            )
            if (!listResult.success) return@withContext listResult
        }
        val operation = if (bidirectional) "双向同步·发送阶段" else "只发送"
        onLog(if (dryRun) "开始${operation}差异预览…" else "开始${operation}增量传输…")
        val integrityCapture = TransferIntegrityCapture()
        val command = RsyncCommandBuilder.push(
            rsyncPath = rsyncPath,
            host = host,
            port = port,
            source = sourcePath,
            passwordFile = password.absolutePath,
            backupRunId = backupRunId(),
            filesFrom = localFileList.absolutePath.takeIf { rangeMode == SyncRangeMode.SINCE },
            bidirectional = bidirectional,
            dryRun = dryRun
        )
        val transfer = executeTransfer(
            command,
            onLog,
            onProgress,
            onItem,
            onTransferredBytes,
            integrityCapture.takeUnless { dryRun }
        )
        if (transfer.exitCode != 0) {
            return@withContext EngineResult(
                false,
                rsyncFailureSummary(transfer.exitCode, transfer.text),
                transfer.exitCode
            )
        }
        if (dryRun) EngineResult(true, previewSummary())
        else {
            val integrity = verifyTransferredData(
                rsyncPath = rsyncPath,
                host = host,
                port = port,
                localPath = sourcePath,
                passwordFile = password,
                direction = VerificationDirection.PUSH,
                capture = integrityCapture,
                onLog = onLog
            )
            if (!integrity.success) return@withContext integrity
            onProgress(1f)
            EngineResult(
                true,
                "${if (bidirectional) "双向同步发送阶段" else "只发送"}完成，数据完整性校验通过；" +
                    "未删除目标端数据，覆盖前版本已保存到 .rootsync-history"
            )
        }
    }

    private suspend fun verifyTransferredData(
        rsyncPath: String,
        host: String,
        port: Int,
        localPath: String,
        passwordFile: File,
        direction: VerificationDirection,
        capture: TransferIntegrityCapture,
        onLog: (String) -> Unit
    ): EngineResult {
        if (capture.relativeFiles.isEmpty()) {
            return if (capture.observedBytes > 0L) {
                EngineResult(false, "传输产生了数据，但未能建立完整性校验清单；任务不会标记为完成")
            } else {
                onLog("INTEGRITY_VERIFY_PASSED count=0 没有新增或更新文件")
                EngineResult(true, "没有新增或更新文件，完整性校验通过")
            }
        }
        val safeFiles = capture.relativeFiles.filter { relative ->
            relative.isNotBlank() && !relative.startsWith('/') && '\u0000' !in relative &&
                relative.split('/').none { it == ".." } &&
                !relative.startsWith(".rootsync-history/") &&
                !relative.startsWith(".rsync-partial/")
        }.distinct()
        if (safeFiles.size != capture.relativeFiles.size) {
            return EngineResult(false, "完整性校验清单包含不安全路径；任务不会标记为完成")
        }
        val verifyList = File(runtimeDir, "integrity-${direction.name.lowercase()}.files")
        verifyList.outputStream().buffered().use { output ->
            safeFiles.forEach { relative ->
                output.write(relative.toByteArray(Charsets.UTF_8))
                output.write(0)
            }
        }
        onLog("INTEGRITY_VERIFY_START count=${safeFiles.size} 正在逐文件计算并比对校验和…")
        val command = when (direction) {
            VerificationDirection.PULL -> RsyncCommandBuilder.verifyPull(
                rsyncPath,
                host,
                port,
                localPath,
                passwordFile.absolutePath,
                verifyList.absolutePath
            )
            VerificationDirection.PUSH -> RsyncCommandBuilder.verifyPush(
                rsyncPath,
                host,
                port,
                localPath,
                passwordFile.absolutePath,
                verifyList.absolutePath
            )
        }
        ensureLifecycleWatchdog(rsyncPath, onLog)
        val mismatches = linkedSetOf<String>()
        val result = shell.execute(
            command = "umask 077; echo \$\$ > ${SafeInput.shellQuote(File(runtimeDir, "transfer.pid").absolutePath)}; " +
                "exec $command",
            onLine = { line ->
                val item = RsyncOutputParser.parseItem(line)
                if (item != null && item.itemizedChange.getOrNull(1) != 'd') {
                    if (mismatches.size < MAX_REPORTED_INTEGRITY_MISMATCHES) {
                        mismatches += item.relativePath
                    }
                } else if (!line.contains(RsyncOutputParser.ITEM_PREFIX)) {
                    onLog(line)
                }
            },
            maxCapturedLines = 500
        )
        if (result.exitCode != 0) {
            return EngineResult(
                false,
                "数据已传输，但完整性校验执行失败：${rsyncFailureSummary(result.exitCode, result.text)}",
                result.exitCode
            )
        }
        if (mismatches.isNotEmpty()) {
            mismatches.forEach { onLog("INTEGRITY_MISMATCH $it") }
            return EngineResult(
                false,
                "完整性校验未通过，发现至少 ${mismatches.size} 个内容不一致文件；任务不会标记为完成"
            )
        }
        onLog("INTEGRITY_VERIFY_PASSED count=${safeFiles.size} 已同步文件内容一致")
        return EngineResult(true, "${safeFiles.size} 个已同步文件完整性校验通过")
    }

    private suspend fun createTimeFileList(
        rootPath: String,
        output: File,
        sinceEpochMillis: Long,
        untilEpochMillis: Long,
        onLog: (String) -> Unit
    ): EngineResult {
        val result = shell.execute(
            listOf(
                syncMetaPath,
                "filelist",
                rootPath,
                output.absolutePath,
                sinceEpochMillis.toString(),
                untilEpochMillis.toString()
            ).joinToString(" ") { SafeInput.shellQuote(it) },
            onLog
        )
        return if (result.exitCode == 0) EngineResult(true, "时间范围文件清单已生成")
        else EngineResult(false, "生成时间范围文件清单失败", result.exitCode)
    }

    private suspend fun downloadMetadata(
        rsyncPath: String,
        host: String,
        port: Int,
        passwordFile: File,
        remoteName: String,
        destination: File,
        onLog: (String) -> Unit
    ): EngineResult {
        val command = listOf(
            rsyncPath, "-rt", "--timeout=60", "--contimeout=15",
            "--password-file=${passwordFile.absolutePath}",
            "rsync://sync-user@$host:$port/meta/$remoteName",
            destination.absolutePath
        ).joinToString(" ") { SafeInput.shellQuote(it) }
        val result = shell.execute(command, onLog)
        return if (result.exitCode == 0) EngineResult(true, "$remoteName 已下载")
        else EngineResult(false, "下载 $remoteName 失败", result.exitCode)
    }

    private fun validateTimeRange(
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?,
        untilEpochMillis: Long
    ): String? = when {
        untilEpochMillis <= 0L -> "同步截止时间无效"
        rangeMode == SyncRangeMode.SINCE && sinceEpochMillis == null -> "请选择同步起始时间"
        rangeMode == SyncRangeMode.SINCE &&
            sinceEpochMillis != null && sinceEpochMillis > untilEpochMillis -> "同步起始时间不能晚于当前时间"
        else -> null
    }

    private suspend fun executeTransfer(
        command: String,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onItem: (RsyncItem) -> Unit,
        onTransferredBytes: (Long) -> Unit,
        integrityCapture: TransferIntegrityCapture? = null
    ): CommandResult {
        var parsedItemCount = 0
        ensureLifecycleWatchdog(bundledRsyncPath, onLog)
        onLog("DIAG_TRANSFER_COMMAND $command")
        val result = shell.execute(
            command = "umask 077; echo \$\$ > ${SafeInput.shellQuote(File(runtimeDir, "transfer.pid").absolutePath)}; " +
                "exec $command",
            onLine = { line ->
            val item = RsyncOutputParser.parseItem(line)
            val transferredBytes = RsyncOutputParser.parseTransferredBytes(line)
            val progress = Regex("(?:^|\\s)([0-9]{1,3})%(?:\\s|$)")
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (item != null) {
                parsedItemCount += 1
                if (item.itemizedChange.getOrNull(1) != 'd') {
                    integrityCapture?.relativeFiles?.add(item.relativePath.trimEnd('/'))
                }
                onItem(item)
            } else if (line.contains(RsyncOutputParser.ITEM_PREFIX)) {
                onLog("DIAG_PARSER_REJECTED $line")
            } else if (progress == null && transferredBytes == null) {
                onLog(line)
            }
            progress?.let { onProgress(it.coerceIn(0, 100) / 100f) }
            transferredBytes?.let { bytes ->
                integrityCapture?.observedBytes = maxOf(integrityCapture?.observedBytes ?: 0L, bytes)
                onTransferredBytes(bytes)
            }
            },
            maxCapturedLines = 500
        )
        onLog(
            "DIAG_TRANSFER_FINISH exit=${result.exitCode} outputLines=${result.totalOutputLines} " +
                "capturedLines=${result.output.size} " +
                "parsedItems=$parsedItemCount"
        )
        return result
    }

    private enum class VerificationDirection { PULL, PUSH }

    private data class TransferIntegrityCapture(
        val relativeFiles: MutableSet<String> = linkedSetOf(),
        var observedBytes: Long = 0L
    )

    private companion object {
        const val MAX_REPORTED_INTEGRITY_MISMATCHES = 20
    }

    private suspend fun ensureLifecycleWatchdog(rsyncPath: String, onLog: (String) -> Unit) {
        val watchdogPidFile = File(runtimeDir, "lifecycle-watchdog.pid")
        val watchdogScriptFile = File(runtimeDir, "lifecycle-watchdog.sh")
        val transferPidFile = File(runtimeDir, "transfer.pid")
        val daemonPidFile = File(runtimeDir, "rsyncd.pid")
        val configFile = File(runtimeDir, "rsyncd.conf")
        val existing = watchdogShell.execute(
            "pid_file=${SafeInput.shellQuote(watchdogPidFile.absolutePath)}; " +
                "script=${SafeInput.shellQuote(watchdogScriptFile.absolutePath)}; " +
                "[ -s \"\$pid_file\" ] || exit 1; pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') exit 1 ;; esac; " +
                "[ -r \"/proc/\$pid/cmdline\" ] || exit 1; " +
                "cmd=\$(tr '\\000' ' ' < \"/proc/\$pid/cmdline\"); " +
                "kill -0 \"\$pid\" 2>/dev/null && " +
                "printf '%s' \"\$cmd\" | grep -Fq \"\$script\" && " +
                "printf '%s' \"\$cmd\" | grep -Fq 'rootsync-watchdog'"
        )
        if (existing.exitCode == 0) {
            onLog("DIAG_WATCHDOG reuseExisting=true appPid=$appProcessId")
            return
        }
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("umask 077\n")
            append("echo \$\$ > ${SafeInput.shellQuote(watchdogPidFile.absolutePath)}; ")
            append("trap \"rm -f ${SafeInput.shellQuote(watchdogPidFile.absolutePath)}\" EXIT INT TERM HUP; ")
            append("idle=0; while true; do ")
            append("alive=0; if [ -r /proc/$appProcessId/cmdline ]; then ")
            append("name=\$(tr '\\000' ' ' < /proc/$appProcessId/cmdline); ")
            append("case \"\$name\" in ${SafeInput.shellQuote(appProcessName)}*) alive=1 ;; esac; fi; ")
            append("active=0; for pid_file in ")
            append(SafeInput.shellQuote(transferPidFile.absolutePath)).append(' ')
            append(SafeInput.shellQuote(daemonPidFile.absolutePath)).append("; do ")
            append("if [ -s \"\$pid_file\" ]; then child=\$(cat \"\$pid_file\"); ")
            append("case \"\$child\" in *[!0-9]*|'') ;; *) ")
            append("if [ -r \"/proc/\$child/cmdline\" ] && kill -0 \"\$child\" 2>/dev/null && ")
            append("tr '\\000' ' ' < \"/proc/\$child/cmdline\" | grep -Fq 'rsync'; then active=1; fi ;; esac; fi; done; ")
            append("if [ \"\$alive\" -eq 1 ] && [ \"\$active\" -eq 1 ]; then idle=0; sleep 2; continue; fi; ")
            append("if [ \"\$alive\" -eq 1 ]; then idle=\$((idle + 1)); ")
            append("if [ \"\$idle\" -lt 5 ]; then sleep 2; continue; fi; rm -f ")
            append(SafeInput.shellQuote(transferPidFile.absolutePath)).append(' ')
            append(SafeInput.shellQuote(daemonPidFile.absolutePath)).append("; exit 0; fi; ")
            append(runtimeCleanupCommand(includeWatchdog = false))
            append("; exit 0; done")
        }
        watchdogScriptFile.writeText(script, Charsets.UTF_8)
        val stopOld =
            "watchdog_file=${SafeInput.shellQuote(watchdogPidFile.absolutePath)}; " +
                "script=${SafeInput.shellQuote(watchdogScriptFile.absolutePath)}; " +
                "if [ -s \"\$watchdog_file\" ]; then old=\$(cat \"\$watchdog_file\"); " +
                "case \"\$old\" in *[!0-9]*|'') ;; *) " +
                "if [ -r \"/proc/\$old/cmdline\" ] && " +
                "tr '\\000' ' ' < \"/proc/\$old/cmdline\" | grep -Fq \"\$script\"; then " +
                "kill \"\$old\" 2>/dev/null || true; attempt=0; " +
                "while kill -0 \"\$old\" 2>/dev/null && [ \"\$attempt\" -lt 10 ]; do " +
                "attempt=\$((attempt + 1)); sleep 0.1; done; " +
                "kill -KILL \"\$old\" 2>/dev/null || true; fi ;; esac; fi; " +
                "rm -f \"\$watchdog_file\""
        watchdogShell.execute(stopOld)
        val launch =
            "umask 077; chmod 700 ${SafeInput.shellQuote(watchdogScriptFile.absolutePath)}; " +
                "nohup sh ${SafeInput.shellQuote(watchdogScriptFile.absolutePath)} rootsync-watchdog " +
                ">/dev/null 2>&1 &"
        val result = watchdogShell.execute(launch)
        onLog(
            "DIAG_WATCHDOG launchExit=${result.exitCode} appPid=$appProcessId " +
                "rsync=$rsyncPath transferPid=${transferPidFile.absolutePath} " +
                "daemonPid=${daemonPidFile.absolutePath} config=${configFile.absolutePath}"
        )
    }

    private fun runtimeCleanupCommand(includeWatchdog: Boolean): String {
        val transferPidFile = File(runtimeDir, "transfer.pid")
        val daemonPidFile = File(runtimeDir, "rsyncd.pid")
        val configFile = File(runtimeDir, "rsyncd.conf")
        val watchdogPidFile = File(runtimeDir, "lifecycle-watchdog.pid")
        val watchdogScriptFile = File(runtimeDir, "lifecycle-watchdog.sh")
        return buildString {
            append("for pid_file in ")
            append(SafeInput.shellQuote(transferPidFile.absolutePath)).append(' ')
            append(SafeInput.shellQuote(daemonPidFile.absolutePath)).append("; do ")
            append("if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); ")
            append("case \"\$pid\" in *[!0-9]*|'') ;; *) ")
            append("if [ -r \"/proc/\$pid/cmdline\" ] && ")
            append("tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | grep -Fq 'rsync'; then ")
            append("kill -INT \"\$pid\" 2>/dev/null || true; sleep 0.2; ")
            append("kill -KILL \"\$pid\" 2>/dev/null || true; fi ;; esac; fi; ")
            append("rm -f \"\$pid_file\"; done; ")
            append("config=${SafeInput.shellQuote(configFile.absolutePath)}; ")
            append("app_name=${SafeInput.shellQuote(appProcessName)}; self=\$\$; parent=\$PPID; ")
            append("for cmdline in /proc/[0-9]*/cmdline; do ")
            append("[ -r \"\$cmdline\" ] || continue; ")
            append("pid=\${cmdline#/proc/}; pid=\${pid%/cmdline}; ")
            append("[ \"\$pid\" = \"\$self\" ] && continue; [ \"\$pid\" = \"\$parent\" ] && continue; ")
            append("cmd=\$(tr '\\000' ' ' < \"\$cmdline\"); ")
            append("if printf '%s' \"\$cmd\" | grep -Fq \"\$config\" || ")
            append("{ printf '%s' \"\$cmd\" | grep -Fq \"\$app_name\" && ")
            append("printf '%s' \"\$cmd\" | grep -Fq 'librsync.so'; }; then ")
            append("kill -INT \"\$pid\" 2>/dev/null || true; sleep 0.1; ")
            append("kill -KILL \"\$pid\" 2>/dev/null || true; fi; done")
            if (includeWatchdog) {
                append("; watchdog_file=${SafeInput.shellQuote(watchdogPidFile.absolutePath)}; ")
                append("watchdog_script=${SafeInput.shellQuote(watchdogScriptFile.absolutePath)}; ")
                append("if [ -s \"\$watchdog_file\" ]; then pid=\$(cat \"\$watchdog_file\"); ")
                append("case \"\$pid\" in *[!0-9]*|'') ;; *) if [ -r \"/proc/\$pid/cmdline\" ] && ")
                append("tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | grep -Fq \"\$watchdog_script\"; then ")
                append("kill \"\$pid\" 2>/dev/null || true; fi ;; esac; fi; ")
                append("rm -f \"\$watchdog_file\"; ")
                append("for cmdline in /proc/[0-9]*/cmdline; do [ -r \"\$cmdline\" ] || continue; ")
                append("pid=\${cmdline#/proc/}; pid=\${pid%/cmdline}; ")
                append("[ \"\$pid\" = \"\$self\" ] && continue; [ \"\$pid\" = \"\$parent\" ] && continue; ")
                append("cmd=\$(tr '\\000' ' ' < \"\$cmdline\"); ")
                append("if printf '%s' \"\$cmd\" | grep -Fq \"\$watchdog_script\" && ")
                append("printf '%s' \"\$cmd\" | grep -Fq 'rootsync-watchdog'; then ")
                append("kill \"\$pid\" 2>/dev/null || true; sleep 0.1; ")
                append("kill -KILL \"\$pid\" 2>/dev/null || true; fi; done")
            }
        }
    }

    suspend fun pauseTransfer(): EngineResult = withContext(Dispatchers.IO) {
        val pidFile = File(runtimeDir, "transfer.pid")
        val command =
            "pid_file=${SafeInput.shellQuote(pidFile.absolutePath)}; " +
                "if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
                "if [ -r \"/proc/\$pid/cmdline\" ] && " +
                "tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | grep -Fq 'rsync'; then " +
                "kill -INT \"\$pid\" 2>/dev/null || true; fi ;; esac; fi"
        val result = controlShell.execute(command)
        shell.cancel()
        if (result.exitCode == 0) EngineResult(true, "传输已暂停，临时分片已保留")
        else EngineResult(false, "暂停信号发送失败", result.exitCode)
    }

    suspend fun isServerReachable(host: String, port: Int, timeoutMillis: Int = 1_500): Boolean =
        withContext(Dispatchers.IO) {
            if (!SafeInput.isValidIpv4(host) || port !in 1024..65535) return@withContext false
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMillis)
                }
                true
            }.getOrDefault(false)
        }

    private suspend fun isLocalPortListening(port: Int): Boolean {
        if (port !in 1024..65535) return false
        val hexPort = port.toString(16).uppercase().padStart(4, '0')
        val command =
            "awk '\$2 ~ /:$hexPort\$/ && \$4 == \"0A\" { found=1 } " +
                "END { exit(found ? 0 : 1) }' /proc/net/tcp /proc/net/tcp6 2>/dev/null"
        return shell.execute(command).exitCode == 0
    }

    private suspend fun writeRootOwnedSecret(file: File, value: String): Boolean {
        val command =
            "umask 077; printf '%s\\n' ${SafeInput.shellQuote(value)} > " +
                "${SafeInput.shellQuote(file.absolutePath)} && " +
                "chown 0:0 ${SafeInput.shellQuote(file.absolutePath)} && " +
                "chmod 600 ${SafeInput.shellQuote(file.absolutePath)}"
        return shell.execute(command).exitCode == 0
    }

    private fun rsyncFailureSummary(exitCode: Int, output: String): String {
        val detail = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() && !it.startsWith("rsync error:") }
            ?.take(120)
        val summary = when {
            output.contains("max connections", ignoreCase = true) ->
                "远端 rsync 连接槽被占用"
            exitCode == 5 -> "远端 rsync 服务拒绝连接或密钥不匹配"
            exitCode == 10 -> "无法建立或维持远端 rsync Socket 连接"
            exitCode == 12 -> "远端 rsync 协议数据流中断"
            exitCode == 23 -> "部分文件传输失败"
            exitCode == 30 -> "rsync 数据传输超时"
            exitCode == 35 -> "等待远端 rsync 服务连接超时"
            else -> "rsync 返回错误 $exitCode"
        }
        return if (detail.isNullOrBlank()) "$summary（错误 $exitCode）" else "$summary：$detail"
    }

    private fun previewSummary(): String =
        "预览完成；零删除模式只新增或更新，覆盖前版本会保存到 .rootsync-history"

    private fun backupRunId(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))

    suspend fun cancel(): EngineResult = withContext(Dispatchers.IO) {
        val pidFile = File(runtimeDir, "transfer.pid")
        val command =
            "pid_file=${SafeInput.shellQuote(pidFile.absolutePath)}; " +
                "if [ -s \"\$pid_file\" ]; then pid=\$(cat \"\$pid_file\"); " +
                "case \"\$pid\" in *[!0-9]*|'') ;; *) " +
                "if [ -r \"/proc/\$pid/cmdline\" ] && " +
                "tr '\\000' ' ' < \"/proc/\$pid/cmdline\" | grep -Fq 'rsync'; then " +
                "kill -INT \"\$pid\" 2>/dev/null || true; attempt=0; " +
                "while kill -0 \"\$pid\" 2>/dev/null && [ \"\$attempt\" -lt 10 ]; do " +
                "attempt=\$((attempt + 1)); sleep 0.1; done; " +
                "if kill -0 \"\$pid\" 2>/dev/null; then kill -KILL \"\$pid\" 2>/dev/null || true; fi; " +
                "fi ;; esac; fi; rm -f \"\$pid_file\""
        val result = controlShell.execute(command)
        shell.cancel()
        if (result.exitCode == 0) EngineResult(true, "任务已取消，rsync 进程已结束")
        else EngineResult(false, "任务取消请求已发送，但 rsync 进程清理失败", result.exitCode)
    }

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

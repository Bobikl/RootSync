package com.rootsync.android.engine

object SafeInput {
    private val ipv4Regex = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")

    fun parsePort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1024..65535 }

    fun isValidIpv4(value: String): Boolean {
        if (!ipv4Regex.matches(value)) return false
        return value.split('.').all { part ->
            part.toIntOrNull()?.let { number -> number in 0..255 } == true
        }
    }

    fun validateStoragePath(path: String): String? {
        if (path.contains('\u0000') || path.contains('\n') || path.contains('\r')) {
            return "路径包含不允许的控制字符"
        }
        if (!path.startsWith("/storage/emulated/0/")) {
            return "首版只允许 /storage/emulated/0/ 下的目录"
        }
        val segments = path.split('/')
        if (segments.any { it == "." || it == ".." }) return "路径不能包含 . 或 .. 段"
        if (segments.any { it == ".rsync-partial" || it == ".rootsync-history" }) {
            return "不能选择 RootSync 临时目录或历史备份目录及其子目录"
        }
        return null
    }

    fun isLocalSelfTarget(remoteHost: String, localHost: String): Boolean =
        remoteHost == localHost || remoteHost == "0.0.0.0" || remoteHost.startsWith("127.")

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class RsyncItem(
    val itemizedChange: String,
    val relativePath: String,
    val folder: String,
    val changeLabel: String,
    val sizeBytes: Long = 0L
)

object RsyncOutputParser {
    const val ITEM_PREFIX = "ROOTSYNC_ITEM:"

    fun parseItem(line: String): RsyncItem? {
        val marker = line.indexOf(ITEM_PREFIX)
        if (marker < 0) return null
        val payload = line.substring(marker + ITEM_PREFIX.length)
        val separator = payload.indexOf('|')
        if (separator <= 0 || separator == payload.lastIndex) return null
        val itemized = payload.substring(0, separator).trim()
        if (itemized.startsWith("*deleting")) return null
        val pathAndSize = payload.substring(separator + 1)
        val sizeSeparator = pathAndSize.lastIndexOf('|')
        val parsedSize = if (sizeSeparator >= 0) {
            pathAndSize.substring(sizeSeparator + 1).trim().toLongOrNull()
        } else null
        val pathPayload = if (parsedSize != null) pathAndSize.substring(0, sizeSeparator) else pathAndSize
        val relativePath = pathPayload
            .substringBefore(" -> ")
            .removePrefix("./")
        if (relativePath.isBlank()) return null
        val normalized = relativePath.trimEnd('/')
        val isDirectory = relativePath.endsWith('/') || itemized.getOrNull(1) == 'd'
        val folder = when {
            normalized.isBlank() -> "（根目录）"
            isDirectory -> normalized
            '/' in normalized -> normalized.substringBeforeLast('/')
            else -> "（根目录）"
        }
        val changeLabel = when {
            itemized.contains("+++++++++") -> "新增"
            isDirectory -> "目录更新"
            else -> "更新"
        }
        return RsyncItem(itemized, relativePath, folder, changeLabel, parsedSize?.coerceAtLeast(0L) ?: 0L)
    }

    fun parseTransferredBytes(line: String): Long? {
        val match = Regex("^\\s*([0-9][0-9,.]*\\s*[KMGTPE]?)\\s+([0-9]{1,3})%(?:\\s|$)", RegexOption.IGNORE_CASE)
            .find(line) ?: return null
        val raw = match.groupValues[1].replace(",", "").replace(" ", "").uppercase()
        val suffix = raw.lastOrNull()?.takeIf { it in "KMGTPE" }
        val number = if (suffix == null) raw else raw.dropLast(1)
        val value = number.toDoubleOrNull() ?: return null
        val power = suffix?.let { "KMGTPE".indexOf(it) + 1 } ?: 0
        // rsync 单个 --human-readable（-h）使用 SI 1000 进位；此前按 1024 解析会把
        // 已传输量放大约 7.37%，导致它与由 %l 精确求和的同步总量不一致。
        var factor = 1.0
        repeat(power) { factor *= 1000.0 }
        return (value * factor).toLong().coerceAtLeast(0L)
    }
}

object RsyncServerLogParser {
    fun currentLaunchLines(tail: List<String>): List<String> {
        val markerIndex = tail.indexOfLast { it.trim().startsWith("ROOTSYNC_START_") }
        return (if (markerIndex >= 0) tail.drop(markerIndex + 1) else tail)
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("ROOTSYNC_START_") }
            .takeLast(40)
    }

    fun summarize(lines: List<String>): String? {
        val normalized = lines.map(String::trim).filter(String::isNotBlank).takeLast(40)
        if (normalized.isEmpty()) return null
        val errors = normalized.filter { line ->
            val lower = line.lowercase()
            lower.contains("fail") || lower.contains("error") || lower.contains("bind") ||
                lower.contains("address") || lower.contains("socket") || lower.contains("config") ||
                lower.contains("pid") || lower.contains("permission")
        }
        return (errors.ifEmpty { normalized }.takeLast(3)).joinToString(" | ").take(360)
    }
}

object RsyncCommandBuilder {
    fun verifyPull(
        rsyncPath: String,
        host: String,
        port: Int,
        destination: String,
        passwordFile: String,
        filesFrom: String
    ): String = verificationCommand(
        rsyncPath = rsyncPath,
        host = host,
        port = port,
        localPath = destination,
        passwordFile = passwordFile,
        filesFrom = filesFrom,
        pull = true
    )

    fun verifyPush(
        rsyncPath: String,
        host: String,
        port: Int,
        source: String,
        passwordFile: String,
        filesFrom: String
    ): String = verificationCommand(
        rsyncPath = rsyncPath,
        host = host,
        port = port,
        localPath = source,
        passwordFile = passwordFile,
        filesFrom = filesFrom,
        pull = false
    )

    fun pull(
        rsyncPath: String,
        host: String,
        port: Int,
        destination: String,
        passwordFile: String,
        backupRunId: String,
        filesFrom: String?,
        dryRun: Boolean,
        strictChecksum: Boolean = false
    ): String {
        require(SafeInput.isValidIpv4(host))
        require(SafeInput.validateStoragePath(destination) == null)
        require(port in 1024..65535)
        require(backupRunId.matches(Regex("[0-9]{8}-[0-9]{6}-[0-9]{3}")))
        require(filesFrom == null || filesFrom.startsWith("/data/"))

        val args = mutableListOf(
            rsyncPath,
            "-rlt",
            "--human-readable",
            "--info=progress2,stats2",
            "--out-format=${RsyncOutputParser.ITEM_PREFIX}%i|%n%L|%l",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--delay-updates",
            "--backup",
            "--backup-dir=.rootsync-history/$backupRunId",
            "--exclude=/.rootsync-history/***",
            "--timeout=300",
            "--contimeout=30",
            "--no-perms",
            "--no-owner",
            "--no-group",
            "--password-file=$passwordFile"
        )
        filesFrom?.let { args += listOf("--files-from=$it", "--from0") }
        if (strictChecksum) args += "--checksum"
        // 单向和双向都不允许较旧来源覆盖目标端更新版本；目标端独有文件也始终保留。
        args += "--update"
        if (dryRun) args += listOf("--dry-run", "--itemize-changes")
        args += "rsync://sync-user@$host:$port/send/"
        args += destination.trimEnd('/') + "/"
        require(args.none(::isDeletionOption)) { "零删除模式禁止生成删除参数" }
        return args.joinToString(" ") { SafeInput.shellQuote(it) }
    }

    fun push(
        rsyncPath: String,
        host: String,
        port: Int,
        source: String,
        passwordFile: String,
        backupRunId: String,
        filesFrom: String?,
        dryRun: Boolean,
        strictChecksum: Boolean = false
    ): String {
        require(SafeInput.isValidIpv4(host))
        require(SafeInput.validateStoragePath(source) == null)
        require(port in 1024..65535)
        require(backupRunId.matches(Regex("[0-9]{8}-[0-9]{6}-[0-9]{3}")))
        require(filesFrom == null || filesFrom.startsWith("/data/"))

        val args = mutableListOf(
            rsyncPath,
            "-rlt",
            "--human-readable",
            "--info=progress2,stats2",
            "--out-format=${RsyncOutputParser.ITEM_PREFIX}%i|%n%L|%l",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--delay-updates",
            "--backup",
            "--backup-dir=.rootsync-history/$backupRunId",
            "--exclude=/.rootsync-history/***",
            "--timeout=300",
            "--contimeout=30",
            "--no-perms",
            "--no-owner",
            "--no-group",
            "--password-file=$passwordFile"
        )
        filesFrom?.let { args += listOf("--files-from=$it", "--from0") }
        if (strictChecksum) args += "--checksum"
        args += "--update"
        if (dryRun) args += listOf("--dry-run", "--itemize-changes")
        args += source.trimEnd('/') + "/"
        args += "rsync://sync-user@$host:$port/receive/"
        require(args.none(::isDeletionOption)) { "零删除模式禁止生成删除参数" }
        return args.joinToString(" ") { SafeInput.shellQuote(it) }
    }

    private fun verificationCommand(
        rsyncPath: String,
        host: String,
        port: Int,
        localPath: String,
        passwordFile: String,
        filesFrom: String,
        pull: Boolean
    ): String {
        require(SafeInput.isValidIpv4(host))
        require(SafeInput.validateStoragePath(localPath) == null)
        require(port in 1024..65535)
        require(filesFrom.startsWith("/data/"))
        val args = mutableListOf(
            rsyncPath,
            "-rlt",
            "--checksum",
            "--dry-run",
            "--itemize-changes",
            "--out-format=${RsyncOutputParser.ITEM_PREFIX}%i|%n%L|%l",
            "--files-from=$filesFrom",
            "--from0",
            "--exclude=/.rootsync-history/***",
            "--timeout=300",
            "--contimeout=30",
            "--no-perms",
            "--no-owner",
            "--no-group",
            "--password-file=$passwordFile"
        )
        if (pull) {
            args += "rsync://sync-user@$host:$port/send/"
            args += localPath.trimEnd('/') + "/"
        } else {
            args += localPath.trimEnd('/') + "/"
            args += "rsync://sync-user@$host:$port/receive/"
        }
        require(args.none(::isDeletionOption)) { "零删除模式禁止生成删除参数" }
        return args.joinToString(" ") { SafeInput.shellQuote(it) }
    }

    internal fun isDeletionOption(argument: String): Boolean {
        val normalized = argument.substringBefore('=').lowercase()
        return normalized == "--delete" ||
            normalized.startsWith("--delete-") ||
            normalized == "--del" ||
            normalized == "--remove-source-files" ||
            normalized == "--remove-sent-files"
    }
}

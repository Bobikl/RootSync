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
        if (path.split('/').any { it == ".." }) return "路径不能包含 .."
        if (path.endsWith("/.rsync-partial")) return "不能选择 rsync 临时目录"
        return null
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class RsyncItem(
    val itemizedChange: String,
    val relativePath: String,
    val folder: String,
    val changeLabel: String
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
        val relativePath = payload.substring(separator + 1)
            .substringBefore(" -> ")
            .removePrefix("./")
            .trim()
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
        return RsyncItem(itemized, relativePath, folder, changeLabel)
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
    fun pull(
        rsyncPath: String,
        host: String,
        port: Int,
        destination: String,
        passwordFile: String,
        backupRunId: String,
        filesFrom: String?,
        bidirectional: Boolean,
        dryRun: Boolean
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
            "--out-format=${RsyncOutputParser.ITEM_PREFIX}%i|%n%L",
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
        if (bidirectional) args += "--update"
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
        bidirectional: Boolean,
        dryRun: Boolean
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
            "--out-format=${RsyncOutputParser.ITEM_PREFIX}%i|%n%L",
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
        if (bidirectional) args += "--update"
        if (dryRun) args += listOf("--dry-run", "--itemize-changes")
        args += source.trimEnd('/') + "/"
        args += "rsync://sync-user@$host:$port/receive/"
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

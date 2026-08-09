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

object RsyncCommandBuilder {
    fun pull(
        rsyncPath: String,
        host: String,
        port: Int,
        destination: String,
        passwordFile: String,
        mirror: Boolean,
        dryRun: Boolean
    ): String {
        require(SafeInput.isValidIpv4(host))
        require(SafeInput.validateStoragePath(destination) == null)
        require(port in 1024..65535)

        val args = mutableListOf(
            rsyncPath,
            "-rlt",
            "--human-readable",
            "--info=progress2,stats2,name1",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--delay-updates",
            "--timeout=300",
            "--contimeout=30",
            "--no-perms",
            "--no-owner",
            "--no-group",
            "--password-file=$passwordFile"
        )
        if (dryRun) args += listOf("--dry-run", "--itemize-changes")
        if (mirror) args += listOf("--delete-delay", "--max-delete=100")
        args += "rsync://sync-user@$host:$port/send/"
        args += destination.trimEnd('/') + "/"
        return args.joinToString(" ") { SafeInput.shellQuote(it) }
    }

    fun push(
        rsyncPath: String,
        host: String,
        port: Int,
        source: String,
        passwordFile: String,
        mirror: Boolean,
        dryRun: Boolean
    ): String {
        require(SafeInput.isValidIpv4(host))
        require(SafeInput.validateStoragePath(source) == null)
        require(port in 1024..65535)

        val args = mutableListOf(
            rsyncPath,
            "-rlt",
            "--human-readable",
            "--info=progress2,stats2,name1",
            "--partial",
            "--partial-dir=.rsync-partial",
            "--delay-updates",
            "--timeout=300",
            "--contimeout=30",
            "--no-perms",
            "--no-owner",
            "--no-group",
            "--password-file=$passwordFile"
        )
        if (dryRun) args += listOf("--dry-run", "--itemize-changes")
        if (mirror) args += listOf("--delete-delay", "--max-delete=100")
        args += source.trimEnd('/') + "/"
        args += "rsync://sync-user@$host:$port/receive/"
        return args.joinToString(" ") { SafeInput.shellQuote(it) }
    }
}

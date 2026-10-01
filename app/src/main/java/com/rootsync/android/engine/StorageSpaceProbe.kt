package com.rootsync.android.engine

import com.rootsync.android.root.CommandResult

/** Query the exact filesystem as ROOT; never substitute a parent or another volume. */
internal object StorageSpaceProbe {
    private val record = Regex("ROOTSYNC_SPACE=([0-9]+):([0-9]+)")

    fun command(path: String): String {
        require(SafeInput.validateStoragePath(path) == null) { "接收目录路径无效" }
        return "/system/bin/stat -f -c 'ROOTSYNC_SPACE=%a:%S' -- ${SafeInput.shellQuote(path)}"
    }

    fun parse(result: CommandResult): Long? {
        if (result.exitCode != 0) return null
        val lines = result.output.filter { it.startsWith("ROOTSYNC_SPACE=") }
        val match = lines.singleOrNull()?.let { record.matchEntire(it) } ?: return null
        val blocks = match.groupValues[1].toLongOrNull() ?: return null
        val blockSize = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (blocks > Long.MAX_VALUE / blockSize) return null
        return blocks * blockSize
    }
}

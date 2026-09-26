package com.rootsync.android.engine

import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import java.security.MessageDigest

enum class PlanFileKind { FILE, DIRECTORY, SYMLINK, OTHER }

data class PlanFile(
    val path: String,
    val kind: PlanFileKind,
    val size: Long,
    val seconds: Long,
    val nanos: Int,
    val hash: String? = null,
    val linkTarget: String? = null
) {
    fun compareTime(other: PlanFile): Int =
        seconds.compareTo(other.seconds).takeIf { it != 0 } ?: nanos.compareTo(other.nanos)
}

data class PlanTree(val exists: Boolean, val strict: Boolean, val entries: List<PlanFile>) {
    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        field(exists.toString())
        field(strict.toString())
        entries.sortedBy { it.path }.forEach {
            field(it.path); field(it.kind.name); field(it.size.toString())
            field(it.seconds.toString()); field(it.nanos.toString())
            field(it.hash?.lowercase().orEmpty()); field(it.linkTarget.orEmpty())
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

enum class PlanAction(val label: String) {
    SEND("发送"), RECEIVE("接收"), SEND_METADATA("发送属性"), RECEIVE_METADATA("接收属性"),
    SKIP_NEWER("跳过：目标较新"), PRESERVE("保留目标独有项"), CONFLICT("冲突：保留双方")
}

data class PlanItem(
    val path: String,
    val action: PlanAction,
    val reason: String,
    val file: PlanFile,
    val bytes: Long = 0L
) {
    val display: String get() = action.label + " · " + path + " · " + reason
}

/** Trees and item lists are scan snapshots; callers must not mutate their backing lists. */
data class SyncPlan(
    val role: SyncRole,
    val local: PlanTree,
    val remote: PlanTree,
    val items: List<PlanItem>,
    val untilEpochMillis: Long
) {
    private data class Totals(
        val send: List<PlanItem>,
        val receive: List<PlanItem>,
        val conflicts: Int,
        val skipped: Int,
        val upload: Long,
        val download: Long
    )

    // One traversal, on demand. UI summary/count reads do not repeatedly filter 100k items.
    private val totals: Totals by lazy {
        val send = ArrayList<PlanItem>()
        val receive = ArrayList<PlanItem>()
        var conflicts = 0
        var skipped = 0
        var upload = 0L
        var download = 0L
        fun add(sum: Long, bytes: Long): Long {
            val count = bytes.coerceAtLeast(0L)
            return if (Long.MAX_VALUE - sum < count) Long.MAX_VALUE else sum + count
        }
        for (item in items) {
            when (item.action) {
                PlanAction.SEND -> { send.add(item); upload = add(upload, item.bytes) }
                PlanAction.RECEIVE -> { receive.add(item); download = add(download, item.bytes) }
                PlanAction.SEND_METADATA -> send.add(item)
                PlanAction.RECEIVE_METADATA -> receive.add(item)
                PlanAction.CONFLICT -> conflicts++
                PlanAction.SKIP_NEWER, PlanAction.PRESERVE -> skipped++
            }
        }
        Totals(send.toList(), receive.toList(), conflicts, skipped, upload, download)
    }

    val conflicts: Int get() = totals.conflicts
    val uploadBytes: Long get() = totals.upload
    val downloadBytes: Long get() = totals.download
    val sendItems: List<PlanItem> get() = totals.send
    val receiveItems: List<PlanItem> get() = totals.receive
    val skippedCount: Int get() = totals.skipped
    val summary: String get() = "发送 " + sendItems.size + " 项，接收 " + receiveItems.size + " 项，" +
        "保留/跳过 " + skippedCount + " 项，冲突 " + conflicts + " 项" +
        if (!local.exists || !remote.exists) "；接收目录缺失，正式同步时再确认创建" else ""
}

object SyncPlanner {
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun build(
        local: PlanTree,
        remote: PlanTree,
        role: SyncRole,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?,
        untilEpochMillis: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): SyncPlan {
        require(local.strict == remote.strict) { "双方比较模式不同，请重新扫描" }
        require(rangeMode == SyncRangeMode.ALL || sinceEpochMillis != null) { "缺少起始时间" }
        require(rangeMode == SyncRangeMode.ALL || requireNotNull(sinceEpochMillis) <= untilEpochMillis) {
            "起始时间晚于结束时间"
        }
        val left = checkedEntries(local)
        val right = checkedEntries(remote)
        val result = mutableListOf<PlanItem>()
        val blockedPaths = hashSetOf<String>()
        fun compareMillis(file: PlanFile, millis: Long): Int {
            // Preserve nanoseconds, handle pre-epoch times, and never multiply epoch seconds
            // (which can overflow Long for malformed/extreme timestamps).
            val seconds = Math.floorDiv(millis, 1000L)
            return file.seconds.compareTo(seconds).takeIf { it != 0 }
                ?: file.nanos.compareTo((Math.floorMod(millis, 1000L) * 1_000_000L).toInt())
        }
        fun inRange(file: PlanFile): Boolean =
            rangeMode == SyncRangeMode.ALL ||
                (compareMillis(file, requireNotNull(sinceEpochMillis)) >= 0 &&
                    compareMillis(file, untilEpochMillis) <= 0)

        fun emit(file: PlanFile, action: PlanAction, reason: String, data: Boolean = false) {
            result += PlanItem(file.path, action, reason, file,
                if (data && file.kind == PlanFileKind.FILE) file.size else 0L)
        }
        fun conflict(file: PlanFile, reason: String) {
            blockedPaths += file.path
            emit(file, PlanAction.CONFLICT, reason)
        }
        fun copy(source: PlanFile, target: PlanFile?, upload: Boolean) {
            if (!inRange(source)) return
            val action = if (upload) PlanAction.SEND else PlanAction.RECEIVE
            val metadata = if (upload) PlanAction.SEND_METADATA else PlanAction.RECEIVE_METADATA
            if (source.kind == PlanFileKind.OTHER || source.kind == PlanFileKind.SYMLINK) {
                if (target?.kind == source.kind && source.kind == PlanFileKind.SYMLINK &&
                    source.linkTarget == target.linkTarget) return
                conflict(source, "符号链接或特殊文件需要手动处理")
                return
            }
            if (target == null) {
                emit(source, action, if (source.kind == PlanFileKind.DIRECTORY) "新增目录" else "新增文件", true)
                return
            }
            val time = source.compareTime(target)
            if (time < 0) {
                emit(source, PlanAction.SKIP_NEWER, "保留目标端更新的版本")
                return
            }
            if (source.kind == PlanFileKind.DIRECTORY) {
                if (time > 0) emit(source, metadata, "仅目录时间变化")
                return
            }
            val sameData = source.size == target.size &&
                (!local.strict || source.hash.equals(target.hash, ignoreCase = true))
            if (time == 0) {
                if (!sameData) conflict(source, "修改时间相同但大小或内容不同")
                return
            }
            // Empty files have known identical data even in quick mode.
            if (sameData && (local.strict || source.size == 0L)) emit(source, metadata, "内容相同，仅更新时间")
            else emit(source, action, "来源版本较新", true)
        }
        // O(n log n) sorting, O(total path length) validation and blocking. Every parent is
        // validated to exist as a directory in its own tree and sorts before its descendants.
        val paths = (left.keys + right.keys).sorted()
        onProgress(0, paths.size)
        for ((index, path) in paths.withIndex()) {
            if (index > 0 && index % 256 == 0) onProgress(index, paths.size)
            if (path.substringBeforeLast('/', "") in blockedPaths) {
                blockedPaths += path
                continue
            }
            val a = left[path]
            val b = right[path]
            if (path == ".") continue
            // Structural safety applies even when the conflicting parent is outside the range.
            if (a != null && b != null && a.kind != b.kind) {
                conflict(a, "同一路径的文件类型不同，禁止覆盖")
                continue
            }
            when (role) {
                SyncRole.SEND_ONLY -> if (a != null) copy(a, b, true)
                    else if (b != null) emit(b, PlanAction.PRESERVE, "来源端不存在，零删除保留")
                SyncRole.RECEIVE_ONLY -> if (b != null) copy(b, a, false)
                    else if (a != null) emit(a, PlanAction.PRESERVE, "来源端不存在，零删除保留")
                SyncRole.BIDIRECTIONAL -> when {
                    a == null && b != null -> copy(b, null, false)
                    b == null && a != null -> copy(a, null, true)
                    a != null && b != null -> {
                        if (a.compareTime(b) >= 0) copy(a, b, true) else copy(b, a, false)
                    }
                }
            }
        }
        onProgress(paths.size, paths.size)
        return SyncPlan(role, local, remote, result.toList(), untilEpochMillis)
    }

    private fun checkedEntries(tree: PlanTree): Map<String, PlanFile> {
        require(tree.entries.size <= 200_000) { "目录项目超过 200000，需选择更小的同步目录" }
        require(tree.exists || tree.entries.isEmpty()) { "缺失目录的文件清单无效" }
        val map = linkedMapOf<String, PlanFile>()
        tree.entries.forEach { file ->
            val parts = file.path.split('/')
            require(file.path == "." || (file.path.isNotEmpty() && !file.path.startsWith('/') &&
                '\u0000' !in file.path && parts.size <= 257 && parts.none {
                    it.isEmpty() || it == "." || it == ".." ||
                        it == ".rootsync-history" || it == ".rsync-partial"
                })) { "文件清单包含不安全路径" }
            require(file.path != "." || file.kind == PlanFileKind.DIRECTORY) { "清单根节点必须为目录" }
            require(file.size >= 0 && file.nanos in 0..999_999_999) { "文件清单元数据无效" }
            if (file.kind == PlanFileKind.SYMLINK) {
                require(!file.linkTarget.isNullOrEmpty() && '\u0000' !in file.linkTarget) { "符号链接目标无效" }
            }
            require(map.put(file.path, file) == null) { "文件清单包含重复路径" }
            if (tree.strict && file.kind == PlanFileKind.FILE) {
                require(file.hash?.matches(sha256Pattern) == true) { "严格清单缺少内容摘要" }
            }
        }
        // Validate after indexing: PlanTree may be unsorted (unlike native manifests).
        for (path in map.keys) {
            val end = path.lastIndexOf('/')
            if (end >= 0) {
                require(map[path.substring(0, end)]?.kind == PlanFileKind.DIRECTORY) {
                    "文件清单包含缺失或非目录的父路径"
                }
            }
        }
        return map
    }
}
/** Restore touched receiver directories without changing unrelated directory times. */
fun SyncPlan.receiveDirectoryTimes(): List<PlanItem> {
    val target = local.entries.associateBy { it.path }
    val source = remote.entries.associateBy { it.path }
    val selected = receiveItems.associateBy { it.path }
    val touched = sortedSetOf<String>()
    for (item in receiveItems) {
        if (item.file.kind == PlanFileKind.DIRECTORY) touched += item.path
        var parent = item.path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            touched += parent
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return touched.mapNotNull { path ->
        val file = selected[path]?.file?.takeIf { it.kind == PlanFileKind.DIRECTORY }
            ?: target[path]?.takeIf { it.kind == PlanFileKind.DIRECTORY }
            ?: source[path]?.takeIf { it.kind == PlanFileKind.DIRECTORY }
        file?.let { PlanItem(path, PlanAction.RECEIVE_METADATA, "恢复本次触及目录的计划时间", it) }
    }
}

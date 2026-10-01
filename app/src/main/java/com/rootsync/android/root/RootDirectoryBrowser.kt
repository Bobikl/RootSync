package com.rootsync.android.root

import com.rootsync.android.engine.SafeInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

data class RootDirectoryPage(
    val path: String,
    val directories: List<String>,
    val nextOffset: Int?,
    val fromCache: Boolean = false
)

/**
 * Owns an isolated ROOT shell; never shares/cancels the sync engine's shell.
 * Browsing is limited by SafeInput to internal storage, including Android/data.
 * Pagination bounds captured output/decoding, not the shell's glob expansion.
 * Pages are live, not snapshots: refresh if directories change during pagination.
 */
class RootDirectoryBrowser : Closeable {
    private val shell = RootShell()
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val cache = RootDirectoryCache()

    suspend fun list(path: String, offset: Int = 0, forceRefresh: Boolean = false): RootDirectoryPage = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("目录浏览器已关闭")
        require(offset >= 0 && offset <= Int.MAX_VALUE - PAGE_SIZE)
        val normalized = RootDirectoryProtocol.normalize(path)
        if (forceRefresh) cache.invalidate(normalized)
        else cache.get(normalized, offset)?.let { return@withLock it }
        val result = execute(RootDirectoryProtocol.command(normalized, offset, PAGE_SIZE))
        withContext(Dispatchers.Default) {
            RootDirectoryProtocol.parse(result, offset, PAGE_SIZE)
        }.also { cache.put(normalized, offset, it) }
    }

    /** Rechecks physical containment and ROOT read/search access; optionally requires write access. */
    suspend fun validateSelection(path: String, requireWritable: Boolean = false): String = mutex.withLock {
        val normalized = RootDirectoryProtocol.normalize(path)
        val result = execute(RootDirectoryProtocol.command(normalized, 0, 0, requireWritable))
        withContext(Dispatchers.Default) {
            RootDirectoryProtocol.parse(result, 0, 0).path
        }
    }

    private suspend fun execute(command: String): CommandResult {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("目录浏览器已关闭")
        return try {
            withTimeout(30_000) {
                shell.execute(command, maxCapturedLines = PAGE_SIZE + 4)
            }.also {
                if (closed.get()) throw CancellationException("目录浏览器已关闭")
            }
        } catch (timeout: TimeoutCancellationException) {
            // Preserve parent/user cancellation; only our own timeout becomes a UI error.
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw CancellationException("目录浏览器已关闭")
            throw IllegalStateException("ROOT 目录读取超时，请重试", timeout)
        }
    }

    override fun close() {
        closed.set(true)
        cache.clear()
        shell.cancel()
    }

    companion object {
        const val STORAGE = "/storage/emulated/0/"
        const val ANDROID_DATA = "/storage/emulated/0/Android/data"
        const val PAGE_SIZE = 100
    }
}

/** ASCII framing + strict UTF-8 Base64 payloads; filenames never become shell syntax or lines. */
internal object RootDirectoryProtocol {
    fun normalize(path: String): String {
        // Do not trim whitespace: trailing spaces are valid filename bytes.
        val checked = if (path == RootDirectoryBrowser.STORAGE.dropLast(1)) "$path/" else path
        require(SafeInput.validateStoragePath(checked) == null) {
            SafeInput.validateStoragePath(checked) ?: "无效路径"
        }
        return checked.split('/').filter { it.isNotEmpty() }.joinToString("/", prefix = "/")
            .let { if (it == RootDirectoryBrowser.STORAGE.dropLast(1)) "$it/" else it }
    }

    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == RootDirectoryBrowser.STORAGE) return null
        return normalize(normalized.substringBeforeLast('/'))
    }

    fun command(path: String, offset: Int, limit: Int, requireWritable: Boolean = false): String {
        require(offset >= 0 && limit in 0..RootDirectoryBrowser.PAGE_SIZE)
        val quoted = SafeInput.shellQuote(normalize(path))
        val writeCheck = if (requireWritable) "[ -w . ] || exit 75" else ":"
        // cd -P resolves symlinks. Map the physical internal-storage root back to the
        // public /storage/emulated/0 namespace, rejecting symlinks escaping that root.
        return """
            export LC_ALL=C
            cd -P /storage/emulated/0/ 2>/dev/null || exit 71
            base=${'$'}PWD
            cd -P $quoted 2>/dev/null || exit 71
            [ -d . ] && [ -r . ] && [ -x . ] || exit 72
            $writeCheck
            physical=${'$'}PWD
            case "${'$'}physical" in
                "${'$'}base") current=/storage/emulated/0/ ;;
                "${'$'}base"/*) current="/storage/emulated/0/${'$'}{physical#"${'$'}base"/}" ;;
                *) exit 74 ;;
            esac
            printf 'RFP2\n'
            # One encoder for a whole page, not four child processes for every name.
            # NUL framing preserves Unicode, spaces and embedded line breaks.
            {
                printf '%s\000' "${'$'}current"
                n=0
                count=0
                more=0
                if [ $limit -gt 0 ]; then
                    for entry in ./* ./.[!.]* ./..?*; do
                        [ -d "${'$'}entry" ] || continue
                        if [ "${'$'}n" -lt $offset ]; then
                            n=${'$'}((n + 1))
                            continue
                        fi
                        if [ "${'$'}count" -ge $limit ]; then
                            more=1
                            break
                        fi
                        printf '%s\000' "${'$'}{entry#./}"
                        count=${'$'}((count + 1))
                    done
                fi
                printf '\000END:%s\000' "${'$'}more"
            } | /system/bin/toybox base64 -w 0 || exit 73
            printf '\n'
        """.trimIndent()
    }

    fun parse(result: CommandResult, offset: Int, limit: Int): RootDirectoryPage {
        check(result.exitCode == 0) {
            when (result.exitCode) {
                71 -> "目录不存在，或 ROOT 无法进入"
                72 -> "ROOT 无法读取或遍历此目录"
                73 -> "设备缺少可用的 toybox Base64 编码工具"
                74 -> "真实路径超出内部存储，不能选择该符号链接"
                75 -> "ROOT 无法写入此目录，接收／双向同步需要可写目录"
                else -> "ROOT 读取失败（退出码 ${result.exitCode}）"
            }
        }
        val lines = result.output
        if (lines.firstOrNull() == "RFP2") {
            check(result.totalOutputLines == 2 && lines.size == 2 && lines[1].length <= 65536) {
                "目录响应不完整或过大"
            }
            val fields = decode(lines[1]).split('\u0000')
            check(fields.size in 4..(limit + 4) && fields.last() == "" &&
                fields[fields.size - 3] == "" && fields[fields.size - 2] in listOf("END:0", "END:1")) {
                "目录分页响应不完整"
            }
            fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            val framed = listOf("RFP1", "P:${encode(fields.first())}") +
                fields.subList(1, fields.size - 3).map { "D:${encode(it)}" } + fields[fields.size - 2]
            return parse(CommandResult(0, framed), offset, limit)
        }
        check(result.totalOutputLines == lines.size &&
            lines.size in 3..(limit + 3) &&
            lines.first() == "RFP1" &&
            lines[1].startsWith("P:") &&
            lines.last() in listOf("END:0", "END:1")) {
            "目录响应不完整或格式异常，请重试"
        }
        val path = normalize(decode(lines[1].substring(2)))
        val children = lines.subList(2, lines.lastIndex).map { line ->
            check(line.startsWith("D:")) { "无效目录记录" }
            val name = decode(line.substring(2))
            check(name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\u0000' !in name) { "无效目录名称" }
            path.trimEnd('/') + "/" + name
        }
        check(children.distinct().size == children.size) { "目录响应包含重复记录" }
        val more = lines.last() == "END:1"
        check(!more || (limit > 0 && children.size == limit)) { "无效分页响应" }
        return RootDirectoryPage(path, children, if (more) Math.addExact(offset, children.size) else null)
    }

    private fun decode(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        check(Base64.getEncoder().encodeToString(bytes) == value) { "非规范 Base64 响应" }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}

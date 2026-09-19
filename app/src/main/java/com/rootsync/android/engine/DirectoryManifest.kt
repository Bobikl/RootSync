package com.rootsync.android.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ManifestKind { DIRECTORY, FILE, SYMLINK }

data class ManifestEntry(
    val relativePath: String,
    val kind: ManifestKind,
    val size: Long,
    val mtimeSeconds: Long,
    val mtimeNanos: Int,
    val symlinkTarget: String?,
    val sha256: String?
)

/**
 * Read-only native manifest, NOT the RSMETA1 directory timestamp snapshot.
 * Entries exclude "." and are sorted by unsigned UTF-8 bytes. No time filtering.
 * Root-created output is mode 0600: caller must chown to app UID/GID before read.
 * Invoke with OUTPUT in a trusted app-private runtime directory outside ROOT.
 * Unique OUTPUT per invocation is required; concurrent writers must not share it.
 * Invalid UTF-8 is rejected, never silently replaced or normalized.
 */
data class DirectoryManifest(
    val missingRoot: Boolean,
    val strictHashes: Boolean,
    val entries: List<ManifestEntry>
) {
    companion object {
        const val MAX_ENTRIES = 200_000
        const val MAX_PATH_BYTES = 16_384
        const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
        const val MAX_TEXT_BYTES = 16L * 1024 * 1024

        /** Buffered streaming decode; bounded entry list, never reads the whole file into a byte array. */
        @JvmStatic
        @Throws(IOException::class)
        fun read(file: File): DirectoryManifest {
            fun check(ok: Boolean, reason: String) {
                if (!ok) throw IOException("Invalid directory manifest: $reason")
            }
            val length = file.length()
            check(length in 24..MAX_MANIFEST_BYTES, "file size outside 24..$MAX_MANIFEST_BYTES bytes")
            return DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { input ->
                var consumed = 0L
                var textBytes = 0L
                fun bytes(count: Int): ByteArray {
                    check(count >= 0 && consumed + count <= length &&
                        consumed + count <= MAX_MANIFEST_BYTES, "truncated or oversized record")
                    consumed += count
                    return ByteArray(count).also { input.readFully(it) }
                }
                fun number(count: Int): Long {
                    val b = bytes(count)
                    var result = 0L
                    for (i in b.indices) result = result or ((b[i].toLong() and 255) shl (8 * i))
                    return result
                }
                fun utf8(b: ByteArray): String {
                    val result = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b)).toString()
                    check('\u0000' !in result, "NUL in path or link target")
                    return result
                }
                check(bytes(8).contentEquals(byteArrayOf(82, 83, 77, 65, 78, 49, 0, 0)), "magic")
                check(number(4) == 1L, "version")
                val flags = number(4)
                check(flags in 0..3, "unknown flags")
                val missing = flags and 1L != 0L
                val strict = flags and 2L != 0L
                val count = number(8)
                check(count in 0..MAX_ENTRIES.toLong(), "entry limit is $MAX_ENTRIES")
                check(!missing || count == 0L, "missing root has entries")
                check(count <= (length - 24) / 31, "impossible record count")
                val entries = ArrayList<ManifestEntry>(count.toInt())
                // Parent existence/type validation, also prevents a remote symlink-parent tree.
                val kinds = HashMap<String, ManifestKind>()
                var previous = byteArrayOf()
                repeat(count.toInt()) {
                    val kind = when (number(1)) {
                        1L -> ManifestKind.DIRECTORY
                        2L -> ManifestKind.FILE
                        3L -> ManifestKind.SYMLINK
                        else -> throw IOException("Invalid directory manifest: unknown kind")
                    }
                    val size = number(8)
                    val seconds = number(8)
                    val nanos = number(4)
                    val pathLength = number(4)
                    val targetLength = number(4)
                    val hashLength = number(1)
                    check(size >= 0, "negative size")
                    check(nanos in 0..999_999_999L, "nanoseconds")
                    check(pathLength in 1..MAX_PATH_BYTES.toLong(), "path exceeds $MAX_PATH_BYTES bytes")
                    check(targetLength in 0..MAX_PATH_BYTES.toLong(), "link target exceeds $MAX_PATH_BYTES bytes")
                    check((kind == ManifestKind.SYMLINK) == (targetLength > 0), "link target/kind mismatch")
                    check(hashLength == if (strict && kind == ManifestKind.FILE) 32L else 0L, "hash strictness mismatch")
                    textBytes += pathLength + targetLength
                    check(textBytes <= MAX_TEXT_BYTES, "text memory budget exceeds $MAX_TEXT_BYTES bytes")
                    val pathBytes = bytes(pathLength.toInt())
                    var compare = 0
                    for (i in 0 until minOf(previous.size, pathBytes.size)) {
                        compare = (previous[i].toInt() and 255) - (pathBytes[i].toInt() and 255)
                        if (compare != 0) break
                    }
                    if (compare == 0) compare = previous.size - pathBytes.size
                    check(compare < 0, "duplicate or unsorted path")
                    previous = pathBytes
                    val path = utf8(pathBytes)
                    check(!path.startsWith('/'), "absolute path")
                    // POSIX protocol: backslash and colon are literal filename characters.
                    val parts = path.split('/')
                    check(parts.size <= 257, "directory depth exceeds 256")
                    check(parts.none { it.isEmpty() || it == "." || it == ".." ||
                        it == ".rsync-partial" || it == ".rootsync-history" }, "unsafe or excluded path")
                    val parentEnd = path.lastIndexOf('/')
                    if (parentEnd >= 0) {
                        check(kinds[path.substring(0, parentEnd)] == ManifestKind.DIRECTORY,
                            "missing or non-directory parent")
                    }
                    val target = if (targetLength > 0) utf8(bytes(targetLength.toInt())) else null
                    val hash = if (hashLength == 32L) {
                        val hex = "0123456789abcdef"
                        buildString(64) {
                            for (b in bytes(32)) {
                                val value = b.toInt() and 255
                                append(hex[value ushr 4]); append(hex[value and 15])
                            }
                        }
                    } else null
                    kinds[path] = kind
                    entries.add(ManifestEntry(path, kind, size, seconds, nanos.toInt(), target, hash))
                }
                check(consumed == length && input.read() == -1, "trailing data or file changed")
                DirectoryManifest(missing, strict, entries)
            }
        }
    }
}

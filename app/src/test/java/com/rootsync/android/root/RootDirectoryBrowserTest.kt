package com.rootsync.android.root

import com.rootsync.android.engine.SafeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class RootDirectoryBrowserTest {
    private val storage = RootDirectoryBrowser.STORAGE
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun response(vararg names: String, more: Boolean = false): CommandResult =
        CommandResult(0, listOf("RFP1", "P:${encoded(storage)}") +
            names.map { "D:${encoded(it)}" } + "END:${if (more) 1 else 0}")

    @Test fun normalizesSeparatorsButPreservesFilenameWhitespace() {
        assertEquals(storage, RootDirectoryProtocol.normalize(storage.dropLast(1)))
        assertEquals(storage + "中文 /目录 ", RootDirectoryProtocol.normalize(storage + "中文 //目录 /"))
        assertEquals(storage, RootDirectoryProtocol.parent(storage + "中文 "))
        assertNull(RootDirectoryProtocol.parent(storage))
    }

    @Test fun reusesStorageAndReservedPathRestrictions() {
        listOf("/data/local/tmp", storage + "../a", storage + "./a",
            storage + ".rsync-partial/a", storage + ".rootsync-history",
            storage + "a\nb", storage + "a\rb", storage + "a\u0000b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { RootDirectoryProtocol.normalize(path) }
        }
    }

    @Test fun shellQuotesUntrustedPaths() {
        val path = storage + "中文 空格/'\$HOME;\$(id)`id`*?[x] "
        val command = RootDirectoryProtocol.command(path, 0, 100)
        assertTrue(command.contains("cd -P ${SafeInput.shellQuote(path)} 2>/dev/null"))
        assertFalse(command.contains("ls "))
        assertTrue(command.contains("toybox base64"))
        assertTrue(command.contains("exit 74"))
    }

    @Test fun decodesUnicodeAndShellMetacharactersWithoutLosingBytes() {
        val names = arrayOf("中文 空格", "'\$HOME;\$(id)*?[x]", "-n", "尾部空格 ", ".隐藏", "换\n行", "tab\t名字")
        val page = RootDirectoryProtocol.parse(response(*names), 0, 100)
        assertEquals(names.map { storage + it }, page.directories)
        assertNull(page.nextOffset)
        // Newline names are transmitted losslessly, then disabled by the existing policy in the UI.
        assertTrue(SafeInput.validateStoragePath(page.directories[5]) != null)
    }

    @Test fun supportsBoundedPagesAndEmptyValidationResponse() {
        val page = RootDirectoryProtocol.parse(response("a", "b", more = true), 100, 2)
        assertEquals(102, page.nextOffset)
        assertEquals(emptyList<String>(), RootDirectoryProtocol.parse(response(), 0, 0).directories)
        assertThrows(IllegalStateException::class.java) {
            RootDirectoryProtocol.parse(response("a", more = true), 0, 2)
        }
    }

    @Test fun rejectsTruncationNoiseAndMissingTerminator() {
        val valid = response("a")
        val bad = listOf(
            valid.copy(totalOutputLines = 99),
            valid.copy(output = valid.output.dropLast(1)),
            valid.copy(output = listOf("su diagnostic") + valid.output),
            valid.copy(output = valid.output + "extra")
        )
        bad.forEach { result ->
            assertThrows(IllegalStateException::class.java) { RootDirectoryProtocol.parse(result, 0, 100) }
        }
    }

    @Test fun rejectsTraversalDuplicatesAndInvalidUtf8() {
        listOf(".", "..", "a/b", "a\u0000b").forEach { name ->
            assertThrows(IllegalStateException::class.java) {
                RootDirectoryProtocol.parse(response(name), 0, 100)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            RootDirectoryProtocol.parse(response("a", "a"), 0, 100)
        }
        val invalidUtf8 = CommandResult(0, listOf("RFP1", "P:${encoded(storage)}", "D:/w==", "END:0"))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            RootDirectoryProtocol.parse(invalidUtf8, 0, 100)
        }
    }

    @Test fun rejectsMalformedBase64AndEscapingPhysicalPath() {
        val invalidBase64 = CommandResult(0, listOf("RFP1", "P:@@@", "END:0"))
        assertThrows(IllegalArgumentException::class.java) {
            RootDirectoryProtocol.parse(invalidBase64, 0, 0)
        }
        val escaped = CommandResult(0, listOf("RFP1", "P:${encoded("/data/local/tmp")}", "END:0"))
        assertThrows(IllegalArgumentException::class.java) {
            RootDirectoryProtocol.parse(escaped, 0, 0)
        }
    }

    @Test fun requiresWriteAccessOnlyWhenRequested() {
        val readOnly = RootDirectoryProtocol.command(storage, 0, 0)
        val writable = RootDirectoryProtocol.command(storage, 0, 0, requireWritable = true)
        assertTrue(readOnly.contains("[ -r . ]"))
        assertTrue(writable.contains("[ -r . ]"))
        assertFalse(readOnly.contains("[ -w . ]"))
        assertTrue(writable.contains("[ -w . ] || exit 75"))
        val error = assertThrows(IllegalStateException::class.java) {
            RootDirectoryProtocol.parse(CommandResult(75, emptyList()), 0, 0)
        }
        assertTrue(error.message.orEmpty().contains("可写目录"))
    }

    @Test fun rejectsRootErrorsAndInvalidLimits() {
        for (exit in listOf(1, 71, 72, 73, 74, 75)) {
            assertThrows(IllegalStateException::class.java) {
                RootDirectoryProtocol.parse(CommandResult(exit, emptyList()), 0, 100)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootDirectoryProtocol.command(storage, -1, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootDirectoryProtocol.command(storage, 0, 101)
        }
    }
}

package com.rootsync.android.engine
import com.rootsync.android.root.CommandResult
import org.junit.Assert.*
import org.junit.Test

class StorageSpaceProbeTest {
    private fun parse(vararg lines: String, exit: Int = 0) = StorageSpaceProbe.parse(CommandResult(exit, lines.toList()))
    @Test fun usesAvailableBlocksNotFreeOrTotal() {
        assertEquals(83_593_310_208L, parse("ROOTSYNC_SPACE=20408523:4096"))
    }
    @Test fun zeroSpaceIsValidNotAnError() { assertEquals(0L, parse("ROOTSYNC_SPACE=0:4096")) }
    @Test fun failuresCannotBeMistakenForSpace() {
        assertNull(parse("ROOTSYNC_SPACE=5:4096", exit = 1))
        assertNull(parse("permission denied"))
        assertNull(parse("ROOTSYNC_SPACE=1:2", "ROOTSYNC_SPACE=1:2"))
    }
    @Test fun rejectsNegativeZeroBlockSizeOverflowAndGarbage() {
        for (line in listOf("-1:4096", "1:0", "9223372036854775807:2", "1.5:4096", "1:4096 extra"))
            assertNull(parse("ROOTSYNC_SPACE=$line"))
    }
    @Test fun suBannerDoesNotCorruptRecord() {
        assertEquals(4096L, parse("manager banner", "ROOTSYNC_SPACE=1:4096"))
    }
    @Test fun quotesExactPathAndNeverUsesParentVolume() {
        val path = "/storage/emulated/0/中文 /a'\$x;"
        assertTrue(StorageSpaceProbe.command(path).endsWith(SafeInput.shellQuote(path)))
        assertFalse(StorageSpaceProbe.command(path).contains("dirname"))
        assertThrows(IllegalArgumentException::class.java) { StorageSpaceProbe.command("/data/local/tmp") }
    }
}

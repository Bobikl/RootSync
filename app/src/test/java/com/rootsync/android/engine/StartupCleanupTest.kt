package com.rootsync.android.engine

import org.junit.Assert.*
import org.junit.Test

class StartupCleanupTest {
    @Test fun onlyUsesTrackedPidsAndChecksRuntimeOwnershipTwice() {
        val command = StartupCleanup.command("/data/user/0/com.rootsync.android.debug/files/runtime")
        assertTrue(command.contains("for name in scan transfer rsyncd lifecycle-watchdog"))
        assertFalse(command.contains("/proc/*"))
        assertFalse(command.contains("grep"))
        assertFalse(command.contains("pkill"))
        assertEquals(3, Regex("/cmdline").findAll(command).count())
        assertTrue(command.contains("kill -INT"))
        assertTrue(command.contains("kill -KILL"))
    }
    @Test fun quotesPath() {
        assertTrue(StartupCleanup.command("/data/user/0/中文 app/runtime").contains("'/data/user/0/中文 app/runtime'"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsSharedStorage() {
        StartupCleanup.command("/storage/emulated/0/runtime")
    }
}

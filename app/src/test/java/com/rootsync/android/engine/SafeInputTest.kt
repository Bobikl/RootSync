package com.rootsync.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeInputTest {
    @Test
    fun acceptsPrivateLanIpv4() {
        assertTrue(SafeInput.isValidIpv4("192.168.1.20"))
        assertFalse(SafeInput.isValidIpv4("192.168.1.999"))
        assertFalse(SafeInput.isValidIpv4("phone.local"))
    }

    @Test
    fun rejectsStorageEscape() {
        assertNull(SafeInput.validateStoragePath("/storage/emulated/0/Android/data/example"))
        assertNotNull(SafeInput.validateStoragePath("/storage/emulated/0/a/../b"))
        assertNotNull(SafeInput.validateStoragePath("/data/local/tmp"))
    }

    @Test
    fun quotesSingleQuoteForShell() {
        assertTrue(SafeInput.shellQuote("a'b").contains("'\\''"))
    }

    @Test
    fun buildsSeparatedSendAndReceiveModules() {
        val pull = RsyncCommandBuilder.pull(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.20",
            port = 8873,
            destination = "/storage/emulated/0/receive",
            passwordFile = "/data/user/0/app/password",
            mirror = true,
            dryRun = true
        )
        val push = RsyncCommandBuilder.push(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.30",
            port = 8873,
            source = "/storage/emulated/0/send",
            passwordFile = "/data/user/0/app/password",
            mirror = false,
            dryRun = false
        )
        assertTrue(pull.contains("/send/"))
        assertTrue(pull.contains("--delete-delay"))
        assertTrue(pull.contains("--dry-run"))
        assertTrue(push.contains("/receive/"))
        assertFalse(push.contains("--delete-delay"))
    }
}

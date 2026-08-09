package com.rootsync.android.engine

import com.rootsync.android.domain.SyncRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeInputTest {
    @Test
    fun keepsBothSidesOfDirectionComplementary() {
        assertEquals(SyncRole.RECEIVE_ONLY, SyncRole.SEND_ONLY.opposite())
        assertEquals(SyncRole.SEND_ONLY, SyncRole.RECEIVE_ONLY.opposite())
    }

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
            backupRunId = "20260809-210000-000",
            dryRun = true
        )
        val push = RsyncCommandBuilder.push(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.30",
            port = 8873,
            source = "/storage/emulated/0/send",
            passwordFile = "/data/user/0/app/password",
            backupRunId = "20260809-210000-000",
            dryRun = false
        )
        assertTrue(pull.contains("/send/"))
        assertTrue(pull.contains("--dry-run"))
        assertTrue(push.contains("/receive/"))
        assertTrue(pull.contains("--backup"))
        assertTrue(push.contains("--backup"))
        assertTrue(pull.contains(".rootsync-history/20260809-210000-000"))
        assertTrue(push.contains(".rootsync-history/20260809-210000-000"))
        assertFalse(pull.contains("--delete"))
        assertFalse(push.contains("--delete"))
        assertFalse(pull.contains("--remove-source-files"))
        assertFalse(push.contains("--remove-source-files"))
    }

    @Test
    fun identifiesEverySourceOrDestinationDeletionOption() {
        assertTrue(RsyncCommandBuilder.isDeletionOption("--delete"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--delete-delay"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--remove-source-files"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--remove-sent-files"))
        assertFalse(RsyncCommandBuilder.isDeletionOption("--backup"))
    }
}

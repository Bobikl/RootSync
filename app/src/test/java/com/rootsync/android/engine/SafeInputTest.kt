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
        assertEquals(SyncRole.BIDIRECTIONAL, SyncRole.BIDIRECTIONAL.opposite())
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
            filesFrom = null,
            bidirectional = false,
            dryRun = true
        )
        val push = RsyncCommandBuilder.push(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.30",
            port = 8873,
            source = "/storage/emulated/0/send",
            passwordFile = "/data/user/0/app/password",
            backupRunId = "20260809-210000-000",
            filesFrom = null,
            bidirectional = false,
            dryRun = false
        )
        assertTrue(pull.contains("/send/"))
        assertTrue(pull.contains("--dry-run"))
        assertTrue(push.contains("/receive/"))
        assertTrue(pull.contains("--backup"))
        assertTrue(push.contains("--backup"))
        assertTrue(pull.contains("--partial-dir=.rsync-partial"))
        assertTrue(push.contains("--partial-dir=.rsync-partial"))
        assertTrue(pull.contains("ROOTSYNC_ITEM:%i|%n%L"))
        assertTrue(push.contains("ROOTSYNC_ITEM:%i|%n%L"))
        assertTrue(pull.contains(".rootsync-history/20260809-210000-000"))
        assertTrue(push.contains(".rootsync-history/20260809-210000-000"))
        assertFalse(pull.contains("--delete"))
        assertFalse(push.contains("--delete"))
        assertFalse(pull.contains("--remove-source-files"))
        assertFalse(push.contains("--remove-source-files"))
    }

    @Test
    fun buildsTimeFilteredBidirectionalCommandWithoutDelete() {
        val command = RsyncCommandBuilder.pull(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.20",
            port = 8873,
            destination = "/storage/emulated/0/sync",
            passwordFile = "/data/user/0/app/password",
            backupRunId = "20260809-210000-000",
            filesFrom = "/data/user/0/app/remote.files",
            bidirectional = true,
            dryRun = false
        )
        assertTrue(command.contains("--files-from=/data/user/0/app/remote.files"))
        assertTrue(command.contains("--from0"))
        assertTrue(command.contains("--update"))
        assertFalse(command.contains("--checksum"))
        assertFalse(command.contains("--delete"))
        assertFalse(command.contains("--remove-source-files"))
    }

    @Test
    fun strictContentComparisonAddsChecksumWithoutDelete() {
        val command = RsyncCommandBuilder.push(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.30",
            port = 8873,
            source = "/storage/emulated/0/send",
            passwordFile = "/data/user/0/app/password",
            backupRunId = "20260809-210000-000",
            filesFrom = null,
            bidirectional = false,
            dryRun = true,
            strictChecksum = true
        )
        assertTrue(command.contains("--checksum"))
        assertTrue(command.contains("--dry-run"))
        assertFalse(command.contains("--delete"))
        assertFalse(command.contains("--remove-source-files"))
    }

    @Test
    fun identifiesEverySourceOrDestinationDeletionOption() {
        assertTrue(RsyncCommandBuilder.isDeletionOption("--delete"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--delete-delay"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--remove-source-files"))
        assertTrue(RsyncCommandBuilder.isDeletionOption("--remove-sent-files"))
        assertFalse(RsyncCommandBuilder.isDeletionOption("--backup"))
    }

    @Test
    fun buildsZeroWriteChecksumVerificationCommands() {
        val pull = RsyncCommandBuilder.verifyPull(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.20",
            port = 8873,
            destination = "/storage/emulated/0/receive",
            passwordFile = "/data/user/0/app/password",
            filesFrom = "/data/user/0/app/integrity.files"
        )
        val push = RsyncCommandBuilder.verifyPush(
            rsyncPath = "/data/app/librsync.so",
            host = "192.168.1.30",
            port = 8873,
            source = "/storage/emulated/0/send",
            passwordFile = "/data/user/0/app/password",
            filesFrom = "/data/user/0/app/integrity.files"
        )
        listOf(pull, push).forEach { command ->
            assertTrue(command.contains("--checksum"))
            assertTrue(command.contains("--dry-run"))
            assertTrue(command.contains("--files-from="))
            assertTrue(command.contains("--from0"))
            assertFalse(command.contains("--delete"))
            assertFalse(command.contains("--remove-source-files"))
            assertFalse(command.contains("--backup"))
        }
    }

    @Test
    fun parsesChangedFoldersFromStableRsyncOutput() {
        val file = RsyncOutputParser.parseItem(
            "ROOTSYNC_ITEM:>f+++++++++|season/episode/video.m4s"
        )
        val directory = RsyncOutputParser.parseItem(
            "ROOTSYNC_ITEM:cd+++++++++|season/episode/"
        )
        assertEquals("season/episode", file?.folder)
        assertEquals("新增", file?.changeLabel)
        assertEquals("season/episode", directory?.folder)
        assertNull(RsyncOutputParser.parseItem("1,024 25% 1.2MB/s 0:00:01"))
    }

    @Test
    fun parsesItemWhenRsyncPrefixesTheDiagnosticLine() {
        val item = RsyncOutputParser.parseItem(
            "rsync: info: ROOTSYNC_ITEM:>f.st......|season/episode/video.m4s|1048576"
        )
        assertEquals("season/episode/video.m4s", item?.relativePath)
        assertEquals("season/episode", item?.folder)
        assertEquals(1_048_576L, item?.sizeBytes)
    }

    @Test
    fun parsesHumanReadableTransferredBytes() {
        assertEquals(
            12_000_000L,
            RsyncOutputParser.parseTransferredBytes("  12.00M  25%   4.00MB/s    0:00:03")
        )
        assertEquals(
            1_024L,
            RsyncOutputParser.parseTransferredBytes("1,024  50%   1.00kB/s    0:00:01")
        )
        assertEquals(
            325_310_000_000L,
            RsyncOutputParser.parseTransferredBytes("325.31G  99%  80.61MB/s    1:07:00")
        )
        assertNull(RsyncOutputParser.parseTransferredBytes("ROOTSYNC_ITEM:>f+++++++++|a|10"))
    }

    @Test
    fun keepsOnlyCurrentRsyncDaemonLaunchAndSurfacesBindFailure() {
        val lines = RsyncServerLogParser.currentLaunchLines(
            listOf(
                "ROOTSYNC_START_old",
                "old permission denied",
                "ROOTSYNC_START_current",
                "rsyncd version 3.4.4 starting",
                "failed to bind socket: Address already in use",
                "rsync error: error in socket IO (code 10)"
            )
        )
        val summary = RsyncServerLogParser.summarize(lines)
        assertFalse(lines.any { it.contains("old permission") })
        assertTrue(summary?.contains("Address already in use") == true)
        assertTrue(summary?.contains("socket IO") == true)
    }
}

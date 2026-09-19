package com.rootsync.android.engine

import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    private fun f(path: String, size: Long = 10, time: Long = 2, hash: String? = null) =
        PlanFile(path, PlanFileKind.FILE, size, time, 0, hash)
    private fun tree(vararg entries: PlanFile, strict: Boolean = false) = PlanTree(true, strict, entries.toList())
    private fun plan(a: PlanTree, b: PlanTree, role: SyncRole = SyncRole.BIDIRECTIONAL) =
        SyncPlanner.build(a, b, role, SyncRangeMode.ALL, null, 10_000)

    @Test fun equalTimestampDifferentSizesIsOneConflictNotTwoTransfers() {
        val result = plan(tree(f("same", 1)), tree(f("same", 2)))
        assertEquals(1, result.conflicts)
        assertTrue(result.sendItems.isEmpty())
        assertTrue(result.receiveItems.isEmpty())
    }
    @Test fun strictModeFindsEqualSizeEqualTimeContentConflict() {
        val result = plan(tree(f("same", hash = "a".repeat(64)), strict = true),
            tree(f("same", hash = "b".repeat(64)), strict = true))
        assertEquals(1, result.conflicts)
    }
    @Test fun quickModeDoesNotClaimToDetectHiddenContentDifferences() {
        assertTrue(plan(tree(f("same")), tree(f("same"))).items.isEmpty())
    }
    @Test fun eachNewOrNewerFileHasExactlyOneDirection() {
        val result = plan(tree(f("local"), f("newer", time = 4)), tree(f("remote"), f("newer", time = 3)))
        assertEquals(setOf("local", "newer"), result.sendItems.map { it.path }.toSet())
        assertEquals(listOf("remote"), result.receiveItems.map { it.path })
        assertEquals(30L, result.uploadBytes + result.downloadBytes)
    }
    @Test fun targetNewerAndTargetOnlyArePreservedInOneWaySync() {
        val result = plan(tree(f("same", time = 1)), tree(f("same", time = 3), f("unique")), SyncRole.SEND_ONLY)
        assertEquals(2, result.skippedCount)
        assertEquals(0L, result.uploadBytes)
    }
    @Test fun typeConflictBlocksDescendantsFromFollowingTargetFileOrLink() {
        val dir = PlanFile("dir", PlanFileKind.DIRECTORY, 0, 2, 0)
        val result = plan(tree(dir, f("dir/child")), tree(f("dir")))
        assertEquals(1, result.conflicts)
        assertEquals(1, result.items.size)
    }
    @Test fun missingDestinationIsComparedAsEmptyWithoutCreatingAnything() {
        val result = plan(tree(f("中文 空格| -> file")), PlanTree(false, false, emptyList()), SyncRole.SEND_ONLY)
        assertEquals(1, result.sendItems.size)
        assertFalse(result.remote.exists)
    }
    @Test fun timeRangeAppliesToSourceAndDoesNotHideNewerTarget() {
        val result = SyncPlanner.build(tree(f("x", time = 2), f("old", time = 1)),
            tree(f("x", time = 9)), SyncRole.SEND_ONLY, SyncRangeMode.SINCE, 2_000, 3_000)
        assertEquals(listOf(PlanAction.SKIP_NEWER), result.items.map { it.action })
    }
    @Test fun strictSameContentOnlySchedulesMetadataWithoutChargingFullFileSize() {
        val h = "c".repeat(64)
        val result = plan(tree(f("x", 100, 4, h), strict = true), tree(f("x", 100, 3, h), strict = true))
        assertEquals(PlanAction.SEND_METADATA, result.items.single().action)
        assertEquals(0L, result.uploadBytes)
    }
    @Test fun refusesEscapingAndDuplicatePaths() {
        assertThrows(IllegalArgumentException::class.java) { plan(tree(f("../secret")), tree()) }
        assertThrows(IllegalArgumentException::class.java) { plan(tree(f("x"), f("x")), tree()) }
    }

    private fun dir(path: String, time: Long = 2) =
        PlanFile(path, PlanFileKind.DIRECTORY, 0, time, 0)

    private fun ranged(a: PlanTree, b: PlanTree, since: Long, until: Long,
                       role: SyncRole = SyncRole.SEND_ONLY) =
        SyncPlanner.build(a, b, role, SyncRangeMode.SINCE, since, until)

    @Test fun rangeIncludesExactEndpointsButNotLaterNanoseconds() {
        val entries = tree(
            f("before").copy(seconds = 1, nanos = 999_999_999),
            f("lower").copy(nanos = 0),
            f("upper").copy(nanos = 1_000_000),
            f("after").copy(nanos = 1_000_001)
        )
        assertEquals(setOf("lower", "upper"),
            ranged(entries, tree(), 2_000, 2_001).sendItems.map { it.path }.toSet())
    }

    @Test fun negativeEpochRangeUsesFloorDivisionAndExactNanos() {
        val entries = tree(
            f("before").copy(seconds = -2, nanos = 998_999_999),
            f("lower").copy(seconds = -2, nanos = 999_000_000),
            f("upper").copy(seconds = -1, nanos = 0),
            f("after").copy(seconds = -1, nanos = 1)
        )
        assertEquals(setOf("lower", "upper"),
            ranged(entries, tree(), -1_001, -1_000).sendItems.map { it.path }.toSet())
    }

    @Test fun extremeSecondsCannotOverflowIntoTimeRange() {
        val entries = tree(f("low", time = Long.MIN_VALUE), f("high", time = Long.MAX_VALUE))
        assertTrue(ranged(entries, tree(), Long.MIN_VALUE, Long.MAX_VALUE).items.isEmpty())
    }

    @Test fun rejectsMissingAndReversedTimeRange() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncPlanner.build(tree(), tree(), SyncRole.SEND_ONLY, SyncRangeMode.SINCE, null, 0)
        }
        assertThrows(IllegalArgumentException::class.java) { ranged(tree(), tree(), 10, 9) }
        // ALL has no lower bound and includes old/future timestamps deliberately.
        assertEquals(2, plan(tree(f("old", time = -100), f("future", time = 100)),
            tree(), SyncRole.SEND_ONLY).sendItems.size)
    }

    @Test fun bidirectionalDoesNotSendOlderInRangeOverNewerOutOfRange() {
        val result = ranged(tree(f("x", time = 2)), tree(f("x", time = 4)),
            2_000, 3_000, SyncRole.BIDIRECTIONAL)
        assertTrue(result.items.isEmpty())
    }

    @Test fun outOfRangeTypeConflictBlocksNestedChildrenButNotPrefixSibling() {
        val source = tree(dir("dir", 1), dir("dir/sub", 1), f("dir/sub/child"),
            f("dir-neighbor"), f("dir2"))
        val target = tree(f("dir", time = 1))
        val result = ranged(source, target, 2_000, 3_000)
        assertEquals(listOf("dir"), result.items.filter { it.action == PlanAction.CONFLICT }.map { it.path })
        assertEquals(setOf("dir-neighbor", "dir2"), result.sendItems.map { it.path }.toSet())
    }

    @Test fun typeConflictBlockingWorksForEveryRoleAndLinkDestination() {
        val source = tree(dir("x"), dir("x/sub"), f("x/sub/file"))
        val target = tree(PlanFile("x", PlanFileKind.SYMLINK, 1, 2, 0, linkTarget = "/outside"))
        for (role in SyncRole.values()) {
            val result = plan(source, target, role)
            assertEquals(1, result.conflicts)
            assertEquals(1, result.items.size)
            assertTrue(result.sendItems.isEmpty())
            assertTrue(result.receiveItems.isEmpty())
        }
    }

    @Test fun olderOrOutOfRangeDirectoryDoesNotSuppressEligibleChild() {
        val source = tree(dir("d", 1), f("d/child", time = 2))
        val target = tree(dir("d", 9))
        assertEquals(listOf("d/child"), ranged(source, target, 2_000, 3_000).sendItems.map { it.path })
        val all = plan(source, target, SyncRole.SEND_ONLY)
        assertEquals(1, all.skippedCount)
        assertEquals(listOf("d/child"), all.sendItems.map { it.path })
    }

    @Test fun receiveOnlyPreservesLocalOnlyAndSkipsOlderRemote() {
        val result = plan(tree(f("same", time = 3), f("local")),
            tree(f("same", time = 1)), SyncRole.RECEIVE_ONLY)
        assertEquals(2, result.skippedCount)
        assertTrue(result.receiveItems.isEmpty())
        assertEquals(0L, result.downloadBytes)
    }

    @Test fun quickEmptyFilesScheduleOnlyMetadataInBothDirections() {
        val a = tree(f("upload", 0, 4), f("download", 0, 1))
        val b = tree(f("upload", 0, 1), f("download", 0, 4))
        val result = plan(a, b)
        assertEquals(PlanAction.SEND_METADATA, result.sendItems.single().action)
        assertEquals(PlanAction.RECEIVE_METADATA, result.receiveItems.single().action)
        assertEquals(0L, result.uploadBytes)
        assertEquals(0L, result.downloadBytes)
        // Missing empty file still needs creation, not a metadata-only update.
        assertEquals(PlanAction.SEND, plan(tree(f("new", 0)), tree()).items.single().action)
    }

    @Test fun quickNonemptyNewerFileStillTransfersEvenWithSameSize() {
        val result = plan(tree(f("x", 10, 3)), tree(f("x", 10, 2)))
        assertEquals(PlanAction.SEND, result.items.single().action)
        assertEquals(10L, result.uploadBytes)
    }

    @Test fun strictHashesCompareCaseInsensitivelyAndFingerprintsAgree() {
        val upper = tree(f("x", hash = "AB".repeat(32)), strict = true)
        val lower = tree(f("x", hash = "ab".repeat(32)), strict = true)
        assertTrue(plan(upper, lower).items.isEmpty())
        assertEquals(upper.fingerprint, lower.fingerprint)
        assertEquals(PlanAction.RECEIVE_METADATA, plan(upper,
            tree(f("x", time = 3, hash = "ab".repeat(32)), strict = true)).items.single().action)
    }

    @Test fun rejectsChildrenOfFilesLinksAndMissingParentsRegardlessOfOrder() {
        for (entries in listOf(
            tree(f("x/child"), f("x")),
            tree(f("x/child"), PlanFile("x", PlanFileKind.SYMLINK, 1, 2, 0, linkTarget = "/elsewhere")),
            tree(f("missing/child"))
        )) {
            assertThrows(IllegalArgumentException::class.java) { plan(entries, tree()) }
        }
        // Unsorted but structurally complete manifests remain accepted.
        assertEquals(2, plan(tree(f("d/child"), dir("d")), tree()).sendItems.size)
    }

    @Test fun rejectsInvalidRootAndMissingHashOrLinkTarget() {
        assertThrows(IllegalArgumentException::class.java) { plan(tree(f(".")), tree()) }
        assertThrows(IllegalArgumentException::class.java) {
            plan(tree(f("x"), strict = true), tree(strict = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(tree(PlanFile("x", PlanFileKind.SYMLINK, 1, 2, 0)), tree())
        }
    }

    @Test fun summaryAndPartitionsReuseSingleStatisticsTraversal() {
        val source = listOf(
            PlanItem("send", PlanAction.SEND, "", f("send"), 10),
            PlanItem("sm", PlanAction.SEND_METADATA, "", f("sm"), 100),
            PlanItem("receive", PlanAction.RECEIVE, "", f("receive"), 20),
            PlanItem("rm", PlanAction.RECEIVE_METADATA, "", f("rm"), 100),
            PlanItem("skip", PlanAction.SKIP_NEWER, "", f("skip")),
            PlanItem("keep", PlanAction.PRESERVE, "", f("keep")),
            PlanItem("conflict", PlanAction.CONFLICT, "", f("conflict"))
        )
        var reads = 0
        val monitored = object : AbstractList<PlanItem>() {
            override val size: Int get() = source.size
            override fun get(index: Int): PlanItem { reads++; return source[index] }
        }
        val result = SyncPlan(SyncRole.BIDIRECTIONAL, tree(), tree(), monitored, 0)
        assertEquals("发送 2 项，接收 2 项，保留/跳过 2 项，冲突 1 项", result.summary)
        val initialReads = reads
        assertEquals(source.size, initialReads)
        repeat(1_000) {
            assertEquals(10L, result.uploadBytes)
            assertEquals(20L, result.downloadBytes)
            assertEquals(1, result.conflicts)
            assertEquals(2, result.skippedCount)
            assertSame(result.sendItems, result.sendItems)
            assertSame(result.receiveItems, result.receiveItems)
            assertTrue(result.summary.isNotEmpty())
        }
        assertEquals(initialReads, reads)
        assertEquals(0, result.copy(items = emptyList()).conflicts)
    }

    @Test fun byteTotalsSaturateRatherThanWrap() {
        val result = plan(tree(f("a", Long.MAX_VALUE, 3), f("b", Long.MAX_VALUE, 3)), tree())
        assertEquals(Long.MAX_VALUE, result.uploadBytes)
    }

    @Test(timeout = 30_000) fun hundredThousandEntriesAndRepeatedSummaryStayPractical() {
        val hash = "a".repeat(64)
        val local = PlanTree(true, true, List(100_000) { f("file$it", 10, 3, hash) })
        val remote = PlanTree(true, true, List(100_000) { f("file$it", 10, 2, hash) })
        val result = plan(local, remote)
        assertEquals(100_000, result.sendItems.size)
        assertEquals(0L, result.uploadBytes)
        assertEquals(0, result.conflicts)
        repeat(1_000) { assertTrue(result.summary.startsWith("发送 100000 项")) }
    }

    @Test fun manifestAdapterPreservesEveryFieldAndDoesNotFilterByTime() {
        val hash = "d".repeat(64)
        val manifest = DirectoryManifest(false, true, listOf(
            ManifestEntry("中文 dir", ManifestKind.DIRECTORY, 0, -2, 123, null, null),
            ManifestEntry("中文 dir/文件", ManifestKind.FILE, 0, 999, 987_654_321, null, hash),
            ManifestEntry("link", ManifestKind.SYMLINK, 8, 4, 5, "../target", null)
        ))
        val result = manifest.toPlanTree()
        assertTrue(result.exists)
        assertTrue(result.strict)
        assertEquals(listOf(
            PlanFile("中文 dir", PlanFileKind.DIRECTORY, 0, -2, 123),
            PlanFile("中文 dir/文件", PlanFileKind.FILE, 0, 999, 987_654_321, hash),
            PlanFile("link", PlanFileKind.SYMLINK, 8, 4, 5, linkTarget = "../target")
        ), result.entries)
        val missing = DirectoryManifest(true, false, emptyList()).toPlanTree()
        assertFalse(missing.exists)
        assertFalse(missing.strict)
        assertTrue(missing.entries.isEmpty())
    }

    @Test fun pullPreservesNewerLocalAncestorTimeForSubsequentPush() {
        val localDir = PlanFile("dir", PlanFileKind.DIRECTORY, 0, 50, 123)
        val remoteDir = localDir.copy(seconds = 30)
        val result = plan(tree(localDir, f("dir/file", time = 1)),
            tree(remoteDir, f("dir/file", time = 2)))
        val restore = result.receiveDirectoryTimes()
        assertEquals(listOf("dir"), restore.map { it.path })
        assertEquals(50L, restore.single().file.seconds)
        assertEquals(123, restore.single().file.nanos)
    }
    @Test fun pullRestoresOnlyTouchedDirectories() {
        val dir = PlanFile("dir", PlanFileKind.DIRECTORY, 0, 2, 0)
        val unrelated = dir.copy(path = "unrelated")
        val result = plan(tree(dir, unrelated, f("dir/file", time = 1)),
            tree(dir.copy(seconds = 4), unrelated, f("dir/file", time = 3)), SyncRole.RECEIVE_ONLY)
        assertEquals(listOf("dir"), result.receiveDirectoryTimes().map { it.path })
        assertEquals(4L, result.receiveDirectoryTimes().single().file.seconds)
    }
    @Test fun newParentOutsideRangeDoesNotCopyUnselectedChildren() {
        val dir = PlanFile("dir", PlanFileKind.DIRECTORY, 0, 1, 0)
        val result = SyncPlanner.build(tree(), tree(dir, f("dir/new", time = 3), f("dir/old", time = 1)),
            SyncRole.RECEIVE_ONLY, SyncRangeMode.SINCE, 2_000, 4_000)
        assertEquals(listOf("dir/new"), result.receiveItems.map { it.path })
        assertEquals(1L, result.receiveDirectoryTimes().single().file.seconds)
    }
}

package com.rootsync.android.domain

import org.junit.Assert.*
import org.junit.Test

class StrategyLogicTest {
    @Test fun rangeSinceAndComparisonConvergeFromEitherSide() {
        val b = PeerProfile("b", "A", "A", "192.168.1.1", secret = "secret", role = SyncRole.RECEIVE_ONLY)
        val fromA = StrategyUpdate("A", "A", "192.168.1.1", "secret", SyncRole.SEND_ONLY,
            SyncRangeMode.SINCE, 1234, revision = StrategyRevision(9, "A"), strictContentCheck = true)
        val changed = StrategyLogic.apply(b, fromA)
        assertEquals(SyncRole.RECEIVE_ONLY, changed.role)
        assertEquals(SyncRangeMode.SINCE, changed.rangeMode)
        assertEquals(1234L, changed.sinceEpochMillis)
        assertTrue(changed.strictContentCheck)
        val back = StrategyUpdate("B", "B", "192.168.1.2", "secret", SyncRole.RECEIVE_ONLY,
            SyncRangeMode.ALL, null, revision = StrategyRevision(10, "B"), strictContentCheck = false)
        val a = StrategyLogic.apply(profile.copy(strictContentCheck = true), back)
        assertEquals(SyncRole.SEND_ONLY, a.role)
        assertEquals(SyncRangeMode.ALL, a.rangeMode)
        assertFalse(a.strictContentCheck)
    }
    @Test fun equalRevisionWithDifferentComparisonCannotBeAcknowledged() {
        val update = update().copy(strictContentCheck = true)
        val applied = StrategyLogic.apply(profile, update)
        assertEquals(StrategyLogic.Decision.DUPLICATE, StrategyLogic.receive(applied, update))
        assertEquals(StrategyLogic.Decision.INVALID, StrategyLogic.receive(applied, update.copy(strictContentCheck = false)))
    }
    @Test fun preparationRequiresSameRangeDateAndComparison() {
        val p = profile.copy(rangeMode = SyncRangeMode.SINCE, sinceEpochMillis = 100, strictContentCheck = true)
        val q = SyncPrepareRequest("r", "B", "B", "192.168.1.2", "secret", SyncRole.RECEIVE_ONLY,
            SyncRangeMode.SINCE, 100, 1000, false, p.strategyRevision, true)
        assertTrue(StrategyLogic.matchesRequest(p, q))
        assertFalse(StrategyLogic.matchesRequest(p, q.copy(strictChecksum = false)))
        assertFalse(StrategyLogic.matchesRequest(p, q.copy(sinceEpochMillis = 101)))
        assertFalse(StrategyLogic.matchesRequest(p, q.copy(rangeMode = SyncRangeMode.ALL)))
    }
    @Test fun strictConflictUsesRevisionOrderingNotLastArrival() {
        val newer = update(counter = 5).copy(strictContentCheck = true)
        val applied = StrategyLogic.apply(profile, newer)
        assertEquals(StrategyLogic.Decision.STALE, StrategyLogic.receive(applied, update(counter = 4)))
        assertEquals(profile.sourcePath, applied.sourcePath)
        assertEquals(profile.destinationPath, applied.destinationPath)
    }

    private val profile = PeerProfile("profile", "B", "设备", "192.168.1.2", secret = "secret",
        controlToken = "control", role = SyncRole.SEND_ONLY, sourcePath = "/source",
        destinationPath = "/destination", strategyRevision = StrategyRevision(2, "A"))
    private fun update(role: SyncRole = SyncRole.RECEIVE_ONLY, counter: Long = 3,
        writer: String = "B") = StrategyUpdate("B", "设备", "192.168.1.2", "secret",
        role, SyncRangeMode.ALL, null, "request", StrategyRevision(counter, writer))

    @Test fun complementaryRoles() {
        SyncRole.entries.forEach { assertEquals(it, it.opposite().opposite()) }
        assertEquals(SyncRole.RECEIVE_ONLY, SyncRole.SEND_ONLY.opposite())
        assertEquals(SyncRole.BIDIRECTIONAL, SyncRole.BIDIRECTIONAL.opposite())
    }
    @Test fun lamportOrdersBeforeWriter() {
        assertTrue(StrategyRevision(3, "A") > StrategyRevision(2, "Z"))
        assertTrue(StrategyRevision(3, "B") > StrategyRevision(3, "A"))
    }
    @Test fun conflictConvergesRegardlessOfArrivalOrder() {
        val low = update(SyncRole.RECEIVE_ONLY, 3, "A")
        val high = update(SyncRole.BIDIRECTIONAL, 3, "B")
        val lowFirst = StrategyLogic.apply(StrategyLogic.apply(profile, low), high)
        val highFirst = StrategyLogic.apply(profile, high)
        assertEquals(highFirst, lowFirst)
        assertEquals(StrategyLogic.Decision.STALE, StrategyLogic.receive(highFirst, low))
    }
    @Test fun remoteUpdatePreservesLocalPathsAndToken() {
        val changed = StrategyLogic.apply(profile, update(SyncRole.BIDIRECTIONAL))
        assertEquals("/source", changed.sourcePath)
        assertEquals("/destination", changed.destinationPath)
        assertEquals("control", changed.controlToken)
    }
    @Test fun duplicateIsIdempotent() {
        val update = update()
        val applied = StrategyLogic.apply(profile, update)
        assertEquals(StrategyLogic.Decision.DUPLICATE, StrategyLogic.receive(applied, update))
        assertEquals(applied, StrategyLogic.apply(applied, update))
    }
    @Test fun equalRevisionCannotChangeContent() {
        assertEquals(StrategyLogic.Decision.INVALID,
            StrategyLogic.receive(profile, update(SyncRole.BIDIRECTIONAL, 2, "A")))
    }
    @Test fun allRangeIgnoresRememberedSinceValue() {
        val remembered = profile.copy(sinceEpochMillis = 123L)
        assertEquals(StrategyLogic.Decision.DUPLICATE,
            StrategyLogic.receive(remembered, update(counter = 2, writer = "A")))
    }
    @Test fun newerRemoteSupersedesOlderQueuedRole() {
        val queued = profile.copy(queuedRole = SyncRole.BIDIRECTIONAL,
            queuedRoleRevision = StrategyRevision(3, "A"))
        val changed = StrategyLogic.apply(queued, update(counter = 3, writer = "B"))
        assertNull(changed.queuedRole)
        assertNull(changed.queuedRoleRevision)
    }
    @Test fun newerQueuedLocalRoleSurvivesOlderRemote() {
        val queued = profile.copy(queuedRole = SyncRole.BIDIRECTIONAL,
            queuedRoleRevision = StrategyRevision(4, "A"))
        val changed = StrategyLogic.apply(queued, update(counter = 3, writer = "B"))
        assertEquals(SyncRole.BIDIRECTIONAL, changed.queuedRole)
        assertEquals(StrategyRevision(4, "A"), changed.queuedRoleRevision)
    }
    @Test fun staleCannotRollBack() {
        assertEquals(StrategyLogic.Decision.STALE, StrategyLogic.receive(profile, update(counter = 1)))
    }
    @Test fun acknowledgementsRequireDeviceAndExactRevision() {
        val revision = StrategyRevision(3, "B")
        assertTrue(StrategyLogic.acceptsAck("B", revision, "B", revision))
        assertFalse(StrategyLogic.acceptsAck("B", revision, "C", revision))
        assertFalse(StrategyLogic.acceptsAck("B", revision, "B", StrategyRevision(2, "B")))
        assertFalse(StrategyLogic.acceptsAck("B", revision, "B", StrategyRevision(3, "A")))
    }
    @Test fun pendingAndQueuedBlockTaskStart() {
        fun state(p: PeerProfile) = SyncUiState(profiles = listOf(p), selectedProfileId = p.id,
            capabilities = DeviceCapabilities(rootGranted = true, rsyncPath = "/rsync"),
            onlineDeviceIds = setOf("B"))
        assertTrue(state(profile).selectedStrategyPending)
        val confirmed = profile.copy(confirmedStrategyRevision = profile.strategyRevision)
        assertTrue(state(confirmed).canStartTransfer)
        assertFalse(state(confirmed.copy(queuedRole = SyncRole.RECEIVE_ONLY)).canStartTransfer)
        assertFalse(state(confirmed.copy(queuedStrategy = update())).canStartTransfer)
        assertFalse(state(confirmed).copy(incompatibleDeviceIds = setOf("B")).canStartTransfer)
    }
    @Test fun invalidRevisionAndUntrustedDeviceAreRejected() {
        assertEquals(StrategyLogic.Decision.INVALID, StrategyLogic.receive(profile, update(counter = 0)))
        assertEquals(StrategyLogic.Decision.INVALID, StrategyLogic.receive(profile, update().copy(deviceId = "C")))
    }
}

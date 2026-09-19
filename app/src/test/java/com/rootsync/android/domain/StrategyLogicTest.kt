package com.rootsync.android.domain

import org.junit.Assert.*
import org.junit.Test

class StrategyLogicTest {
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

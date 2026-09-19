package com.rootsync.android.domain
import org.junit.Assert.*
import org.junit.Test
class PreparationLedgerTest {
    @Test fun duplicateRequestsAreClaimedOnlyOnce() {
        val ledger = PreparationLedger()
        assertTrue(ledger.claim("A", "request"))
        assertFalse(ledger.claim("A", "request"))
        assertTrue(ledger.claim("B", "request"))
    }
    @Test fun cancellationBeforeDeliveryPreventsStartingWork() {
        val ledger = PreparationLedger()
        ledger.cancel("A", "request")
        assertFalse(ledger.claim("A", "request"))
        assertFalse(ledger.waiting("A", "request", "late"))
        assertFalse(ledger.complete("A", "request", "late"))
        assertEquals(PreparationLedger.Stage.CANCELLED, ledger.get("A", "request")?.stage)
    }
    @Test fun completedResponseCannotRegressToWaiting() {
        val ledger = PreparationLedger()
        ledger.claim("A", "request")
        assertTrue(ledger.waiting("A", "request", "waiting"))
        assertTrue(ledger.complete("A", "request", "ready"))
        assertFalse(ledger.waiting("A", "request", "late"))
        assertFalse(ledger.claim("A", "request"))
        assertEquals("ready", ledger.get("A", "request")?.response)
    }
    @Test fun cancellationSupersedesReadyAndUnpublishedRelease() {
        val ledger = PreparationLedger()
        ledger.claim("A", "request")
        ledger.complete("A", "request", "ready")
        ledger.cancel("A", "request")
        ledger.releaseUnpublished("A", "request")
        assertEquals(PreparationLedger.Stage.CANCELLED, ledger.get("A", "request")?.stage)
        assertNull(ledger.get("A", "request")?.response)
    }
    @Test fun failedDeliveryReleasesOnlyUnpublishedClaim() {
        val ledger = PreparationLedger()
        ledger.claim("A", "request")
        ledger.releaseUnpublished("A", "request")
        assertTrue(ledger.claim("A", "request"))
        ledger.waiting("A", "request", "waiting")
        ledger.releaseUnpublished("A", "request")
        assertFalse(ledger.claim("A", "request"))
    }
    @Test fun longRunningPreparationKeepsItsHeartbeat() {
        var time = 0L
        val ledger = PreparationLedger({ time }, 100)
        ledger.claim("A", "request")
        repeat(10) {
            time += 90
            assertTrue(ledger.waiting("A", "request", "waiting"))
            assertFalse(ledger.claim("A", "request"))
        }
        time += 101
        assertNull(ledger.get("A", "request"))
    }
}

package com.rootsync.android.discovery
import org.junit.Assert.*
import org.junit.Test
class TrustedReconnectGateTest {
    @Test fun normalPresenceNeverRestartsConfirmedTrust() {
        val g = TrustedReconnectGate()
        assertTrue(g.shouldReconnect("a", "host", 8873, "token", 0))
        g.confirmed("a", "host", 8873, "token", 1)
        for (i in 1..100) assertFalse(g.shouldReconnect("a", "host", 8873, "token", i * 15_000L))
    }
    @Test fun retriesAreThrottledWithoutSlidingOnEveryAnnouncement() {
        val g = TrustedReconnectGate()
        assertTrue(g.shouldReconnect("a", "h", 1, "t", 0))
        assertFalse(g.shouldReconnect("a", "h", 1, "t", 9_000))
        assertTrue(g.shouldReconnect("a", "h", 1, "t", 10_000))
    }
    @Test fun endpointCredentialAndOfflineChangesRequireHandshake() {
        val g = TrustedReconnectGate()
        g.confirmed("a", "h", 1, "t", 0)
        assertTrue(g.shouldReconnect("a", "new", 1, "t", 1))
        g.confirmed("a", "new", 1, "t", 2)
        assertTrue(g.shouldReconnect("a", "new", 2, "t", 3))
        g.confirmed("a", "new", 2, "t", 4)
        assertTrue(g.shouldReconnect("a", "new", 2, "rotated", 5))
        g.confirmed("a", "new", 2, "rotated", 6)
        assertTrue(g.shouldReconnect("a", "new", 2, "rotated", 100_000))
    }
    @Test fun peerStatesAreIndependent() {
        val g = TrustedReconnectGate()
        g.confirmed("a", "h", 1, "t", 0)
        assertTrue(g.shouldReconnect("b", "h", 1, "t", 1))
        assertFalse(g.shouldReconnect("a", "h", 1, "t", 1))
    }
}

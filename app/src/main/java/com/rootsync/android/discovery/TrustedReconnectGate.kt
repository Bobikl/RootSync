package com.rootsync.android.discovery

/** Presence is not a disconnect. Only retry unconfirmed or changed endpoints. */
internal class TrustedReconnectGate {
    private data class Peer(val host: String, val port: Int, val token: String,
        var seen: Long, var attempted: Long? = null, var confirmed: Boolean = false)
    private val peers = mutableMapOf<String, Peer>()

    @Synchronized fun shouldReconnect(id: String, host: String, port: Int, token: String, now: Long): Boolean {
        val previous = peers[id]
        val peer = if (previous == null || previous.host != host || previous.port != port ||
            previous.token != token || now - previous.seen > 90_000L) {
            Peer(host, port, token, now).also { peers[id] = it }
        } else previous
        peer.seen = now
        if (peer.confirmed) return false
        if (peer.attempted?.let { now - it < 10_000L } == true) return false
        peer.attempted = now
        return true
    }

    @Synchronized fun confirmed(id: String, host: String, port: Int, token: String, now: Long) {
        peers[id] = Peer(host, port, token, now, confirmed = true)
    }
}

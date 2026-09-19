package com.rootsync.android.domain

/**
 * Authenticated prepare requests are keyed by BOTH sender and request ID.
 * Callers must authenticate before using this ledger. A terminal entry is never
 * reopened by a UDP retry, a late WAITING, or a late successful scan result.
 */
class PreparationLedger(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retentionMillis: Long = 25L * 60 * 60 * 1000
) {
    data class Key(val deviceId: String, val requestId: String)
    enum class Stage { ACTIVE, COMPLETED, CANCELLED }
    data class Entry(val stage: Stage, val response: String? = null, val updatedAt: Long)
    private val entries = mutableMapOf<Key, Entry>()

    @Synchronized fun get(deviceId: String, requestId: String): Entry? {
        expire()
        return entries[Key(deviceId, requestId)]
    }

    @Synchronized fun claim(deviceId: String, requestId: String): Boolean {
        require(deviceId.isNotBlank() && requestId.isNotBlank())
        expire()
        val key = Key(deviceId, requestId)
        if (key in entries) return false
        entries[key] = Entry(Stage.ACTIVE, updatedAt = nowMillis())
        return true
    }

    @Synchronized fun waiting(deviceId: String, requestId: String, response: String): Boolean {
        val key = Key(deviceId, requestId)
        if (entries[key]?.stage != Stage.ACTIVE) return false
        entries[key] = Entry(Stage.ACTIVE, response, nowMillis())
        return true
    }

    @Synchronized fun complete(deviceId: String, requestId: String, response: String): Boolean {
        val key = Key(deviceId, requestId)
        if (entries[key]?.stage != Stage.ACTIVE) return false
        entries[key] = Entry(Stage.COMPLETED, response, nowMillis())
        return true
    }

    @Synchronized fun cancel(deviceId: String, requestId: String) {
        require(deviceId.isNotBlank() && requestId.isNotBlank())
        expire()
        entries[Key(deviceId, requestId)] = Entry(Stage.CANCELLED, updatedAt = nowMillis())
    }

    /** Undo only an unpublished claim; never erase a concurrent cancellation. */
    @Synchronized fun releaseUnpublished(deviceId: String, requestId: String) {
        val key = Key(deviceId, requestId)
        val entry = entries[key]
        if (entry?.stage == Stage.ACTIVE && entry.response == null) entries.remove(key)
    }

    @Synchronized fun expire() {
        val cutoff = nowMillis() - retentionMillis
        entries.entries.removeAll { it.value.updatedAt < cutoff }
    }
}

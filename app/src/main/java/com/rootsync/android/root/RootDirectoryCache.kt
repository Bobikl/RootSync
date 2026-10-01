package com.rootsync.android.root

/** Short-lived display cache only. Selection always performs a fresh ROOT validation. */
internal class RootDirectoryCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val time: Long, val page: RootDirectoryPage)
    private val entries = LinkedHashMap<Pair<String, Int>, Entry>(16, 0.75f, true)
    @Synchronized fun get(path: String, offset: Int): RootDirectoryPage? {
        val key = path to offset
        val entry = entries[key] ?: return null
        if (now() - entry.time !in 0..15_000L) { entries.remove(key); return null }
        return entry.page.copy(fromCache = true)
    }
    @Synchronized fun put(path: String, offset: Int, page: RootDirectoryPage) {
        entries[path to offset] = Entry(now(), page)
        while (entries.size > 16) entries.remove(entries.keys.first())
    }
    @Synchronized fun invalidate(path: String) { entries.keys.removeAll { it.first == path } }
    @Synchronized fun clear() { entries.clear() }
}

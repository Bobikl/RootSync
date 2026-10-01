package com.rootsync.android.root
import org.junit.Assert.*
import org.junit.Test
class RootDirectoryCacheTest {
    private val path = "/storage/emulated/0/"
    @Test fun cacheExpiresAndMarksDisplayOnly() {
        var now = 0L
        val cache = RootDirectoryCache { now }
        cache.put(path, 0, RootDirectoryPage(path, listOf(path + "a"), null))
        assertTrue(cache.get(path, 0)!!.fromCache)
        now = 15_001
        assertNull(cache.get(path, 0))
    }
    @Test fun refreshInvalidatesAllPagesForPath() {
        val cache = RootDirectoryCache { 0 }
        cache.put(path, 0, RootDirectoryPage(path, emptyList(), 100))
        cache.put(path, 100, RootDirectoryPage(path, emptyList(), null))
        cache.invalidate(path)
        assertNull(cache.get(path, 0)); assertNull(cache.get(path, 100))
    }
    @Test fun cacheIsBoundedAndClearedOnClose() {
        val cache = RootDirectoryCache { 0 }
        for (n in 0..16) cache.put(path + n, 0, RootDirectoryPage(path + n, emptyList(), null))
        assertNull(cache.get(path + 0, 0))
        assertNotNull(cache.get(path + 16, 0))
        cache.clear(); assertNull(cache.get(path + 16, 0))
    }
}

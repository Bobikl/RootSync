package com.rootsync.android.domain
import org.junit.Assert.*
import org.junit.Test
class ScanProgressTest {
    @Test fun scanNeverInventsPercent() {
        val p = requireNotNull(ScanProgress.parse("MANIFEST_PROGRESS=800 HASH_BYTES=123000000"))
        assertNull(p.fraction)
        assertTrue(p.detail.contains("800"))
        assertTrue(p.detail.contains("123 MB"))
    }
    @Test fun acceptsOldScannerCounter() {
        assertNotNull(ScanProgress.parse("MANIFEST_PROGRESS=256"))
    }
    @Test fun comparisonUsesRealDenominatorAndHandlesEmptyTree() {
        assertEquals(0.25f, requireNotNull(ScanProgress.parse("PLAN_PROGRESS=25/100")).fraction!!, 0f)
        assertEquals(1f, requireNotNull(ScanProgress.parse("PLAN_PROGRESS=0/0")).fraction!!, 0f)
    }
    @Test fun stagesClearPercent() {
        val p = requireNotNull(ScanProgress.parse("PREVIEW_STAGE=保存完整差异计划"))
        assertNull(p.fraction)
        assertEquals("保存完整差异计划", p.detail)
    }
    @Test fun malformedCountersAreIgnored() {
        for (line in listOf("PLAN_PROGRESS=9/1", "PLAN_PROGRESS=-1/2", "MANIFEST_PROGRESS=-2",
                "MANIFEST_PROGRESS=999999999999999999999999999", "arbitrary log"))
            assertNull(ScanProgress.parse(line))
    }
}

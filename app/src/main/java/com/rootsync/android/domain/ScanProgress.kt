package com.rootsync.android.domain

/** Only actual scanner/planner output drives counters; elapsed time never invents progress. */
data class ScanProgress(val detail: String, val fraction: Float? = null) {
    companion object {
        private val manifest = Regex("MANIFEST_PROGRESS=(\\d+)(?: HASH_BYTES=(\\d+))?")
        private val plan = Regex("PLAN_PROGRESS=(\\d+)/(\\d+)")
        fun parse(line: String): ScanProgress? {
            if (line.startsWith("PREVIEW_STAGE=")) return ScanProgress(line.substringAfter('='))
            manifest.matchEntire(line)?.let {
                val count = it.groupValues[1].toLongOrNull() ?: return null
                val bytes = it.groupValues[2].toLongOrNull()
                return ScanProgress("目录扫描：已读取 $count 项" +
                    (bytes?.let { value -> " · 已校验 ${value / 1_000_000} MB" } ?: "") + "（总量未知）")
            }
            plan.matchEntire(line)?.let {
                val done = it.groupValues[1].toLongOrNull() ?: return null
                val total = it.groupValues[2].toLongOrNull() ?: return null
                if (done > total) return null
                return ScanProgress("比较阶段：已比较 $done / $total 项",
                    if (total == 0L) 1f else done.toFloat() / total)
            }
            return null
        }
    }
}

package com.rootsync.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rootsync.android.engine.PlanAction
import com.rootsync.android.engine.PlanItem
import com.rootsync.android.engine.SyncPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val PLAN_PAGE_SIZE = 100

// SyncPlan is a data class with potentially hundreds of thousands of entries.
// Compose keys must not calculate its structural hash on the main thread.
private class PlanIdentity(private val plan: SyncPlan?) {
    override fun equals(other: Any?): Boolean = other is PlanIdentity && other.plan === plan
    override fun hashCode(): Int = System.identityHashCode(plan)
}

/**
 * Complete plan comes only from SyncPlan.items, never from the capped transfer log.
 * All O(n) scans run off the UI thread; only one 100-item result page is retained.
 * A new plan resets the dialog/filter/page rather than presenting stale results.
 */
@Composable
fun SyncPlanPanel(plan: SyncPlan?, scanning: Boolean, statusText: String? = null) {
    key(PlanIdentity(plan)) {
        var overview by remember { mutableStateOf<PlanOverview?>(null) }
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(PlanIdentity(plan)) {
            overview = plan?.let { withContext(Dispatchers.Default) { summarizePlan(it) } }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("完整同步计划", style = MaterialTheme.typography.titleLarge)
                statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在扫描；完成后的完整计划在此查看，动态摘要不代表完整清单。")
                } else if (plan == null) {
                    Text("暂无完整计划，请先执行差异预览。下方动态记录不能替代完整计划。")
                } else {
                    Text("计划角色：本机 ${plan.role.label} · 对端 ${plan.role.opposite().label}")
                    val summary = overview
                    if (summary == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在统计完整计划…")
                    } else {
                        PlanSummary(summary)
                        if (!plan.local.exists || !plan.remote.exists) {
                            Text("接收目录缺失，正式同步时仍需确认创建。")
                        }
                        Button(onClick = { open = true }) { Text("查看全部 ${plan.items.size} 项") }
                    }
                }
                Text("上传／下载方向均相对于本机；计划字节数不是已传输流量。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (open && plan != null && !scanning) {
            PlanDetails(plan, overview, onDismiss = { open = false })
        }
    }
}

private data class PlanOverview(
    val upload: Long,
    val download: Long,
    val conflicts: Int,
    val skipped: Int,
    val count: Int
)

private suspend fun summarizePlan(plan: SyncPlan): PlanOverview {
    var upload = 0L
    var download = 0L
    var conflicts = 0
    var skipped = 0
    plan.items.forEachIndexed { index, item ->
        if (index % 256 == 0) currentCoroutineContext().ensureActive()
        when (item.action) {
            PlanAction.SEND -> upload = saturatedBytes(upload, item.bytes)
            PlanAction.RECEIVE -> download = saturatedBytes(download, item.bytes)
            PlanAction.CONFLICT -> conflicts++
            PlanAction.SKIP_NEWER, PlanAction.PRESERVE -> skipped++
            else -> Unit
        }
    }
    return PlanOverview(upload, download, conflicts, skipped, plan.items.size)
}

private fun saturatedBytes(total: Long, value: Long): Long {
    val bytes = value.coerceAtLeast(0)
    return if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
}

@Composable
private fun PlanSummary(summary: PlanOverview) {
    Text("全计划：${summary.count} 项 · 冲突 ${summary.conflicts} · 保留／跳过 ${summary.skipped}")
    Text("预计上传 ${summary.upload} B · 预计下载 ${summary.download} B")
}

private data class PlanSlice(
    val keyword: String,
    val action: PlanAction?,
    val page: Int,
    val matched: Int,
    val items: List<PlanItem>
) {
    val pages: Int get() = ((matched + PLAN_PAGE_SIZE - 1) / PLAN_PAGE_SIZE).coerceAtLeast(1)
}

@Composable
private fun PlanDetails(plan: SyncPlan, overview: PlanOverview?, onDismiss: () -> Unit) {
    var keyword by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<PlanAction?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var slice by remember { mutableStateOf<PlanSlice?>(null) }
    val scroll = rememberLazyListState()

    LaunchedEffect(PlanIdentity(plan), keyword, action, page) {
        val requestedKeyword = keyword
        val requestedAction = action
        val requestedPage = page
        slice = null
        if (requestedKeyword.isNotEmpty()) delay(250)
        slice = withContext(Dispatchers.Default) {
            val visible = ArrayList<PlanItem>(PLAN_PAGE_SIZE)
            var matched = 0
            val first = requestedPage * PLAN_PAGE_SIZE
            plan.items.forEachIndexed { index, item ->
                if (index % 256 == 0) currentCoroutineContext().ensureActive()
                if ((requestedAction == null || item.action == requestedAction) &&
                    (requestedKeyword.isEmpty() || item.path.contains(requestedKeyword, ignoreCase = true) ||
                        item.reason.contains(requestedKeyword, ignoreCase = true) ||
                        item.action.label.contains(requestedKeyword, ignoreCase = true))) {
                    if (matched >= first && visible.size < PLAN_PAGE_SIZE) visible.add(item)
                    matched++
                }
            }
            PlanSlice(requestedKeyword, requestedAction, requestedPage, matched, visible)
        }
        scroll.scrollToItem(0)
    }
    // Never show an old page under newly edited filters while the effect is being scheduled.
    val ready = slice?.takeIf { it.keyword == keyword && it.action == action && it.page == page }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp).imePadding(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("完整计划 · 每页 100 项", style = MaterialTheme.typography.titleLarge)
                overview?.let { PlanSummary(it) }
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it; page = 0 },
                    label = { Text("搜索路径、原因或动作") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = action == null,
                        onClick = { action = null; page = 0 },
                        label = { Text("全部动作") }
                    )
                    PlanAction.entries.forEach { candidate ->
                        FilterChip(
                            selected = action == candidate,
                            onClick = { action = candidate; page = 0 },
                            label = { Text(candidate.label) }
                        )
                    }
                }
                if (ready == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (ready == null) "正在筛选全量 ${plan.items.size} 项…"
                    else "匹配 ${ready.matched} / 全量 ${plan.items.size} 项 · 第 ${page + 1}/${ready.pages} 页",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = scroll,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (ready != null && ready.items.isEmpty()) {
                        item {
                            Text(if (plan.items.isEmpty()) "完整计划为空，没有计划动作。" else "没有匹配项，请调整筛选。")
                        }
                    }
                    itemsIndexed(ready?.items.orEmpty()) { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${page * PLAN_PAGE_SIZE + index + 1}. ${item.action.label} · ${item.bytes} B",
                                color = if (item.action == PlanAction.CONFLICT) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            // No maxLines: full relative paths and reasons remain inspectable.
                            Text(item.path)
                            Text(item.reason, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = ready != null && page > 0) { Text("上一页") }
                    TextButton(
                        onClick = { page++ },
                        enabled = ready != null && page + 1 < ready.pages
                    ) { Text("下一页") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

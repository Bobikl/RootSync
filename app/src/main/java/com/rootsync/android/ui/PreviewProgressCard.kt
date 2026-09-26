package com.rootsync.android.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rootsync.android.domain.SyncUiState
import kotlinx.coroutines.delay

@Composable
internal fun PreviewProgressCard(state: SyncUiState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.previewStartedMillis) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val elapsed = ((now - state.previewStartedMillis) / 1000).coerceAtLeast(0)
    val age = ((now - state.scanUpdatedMillis) / 1000).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("差异预览进行中 · 已用时 ${elapsed} 秒", style = MaterialTheme.typography.titleMedium)
            Text(state.scanDetail)
            val fraction = state.previewFraction
            if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("比较阶段 ${(fraction * 100).toInt()}%（非整体预览百分比）")
            }
            Text(if (age >= 15) "${age} 秒未收到新进度，可能正在处理大文件或等待响应；可取消预览。"
                 else "最近状态更新：${age} 秒前 · 扫描总量未知时不显示虚假百分比",
                 style = MaterialTheme.typography.bodySmall)
        }
    }
}

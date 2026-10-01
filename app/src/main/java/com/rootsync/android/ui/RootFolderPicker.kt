package com.rootsync.android.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rootsync.android.engine.SafeInput
import com.rootsync.android.root.RootDirectoryBrowser
import com.rootsync.android.root.RootDirectoryPage
import com.rootsync.android.root.RootDirectoryProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Show conditionally from the caller and remove on either callback.
 * onSelected supplies a canonical, ROOT-readable internal-storage path.
 * requireWritable adds a ROOT write-access check at selection; no probe files are created.
 */
@Composable
fun RootFolderPicker(
    initialPath: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
    requireWritable: Boolean = false
) {
    val context = LocalContext.current.applicationContext
    val browser = remember { RootDirectoryBrowser() }
    val recentsStore = remember(context) { RootFolderRecents(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val start = remember { initialPath.ifEmpty { RootDirectoryBrowser.STORAGE } }
    var input by remember { mutableStateOf(start) }
    var page by remember { mutableStateOf<RootDirectoryPage?>(null) }
    var pageOffset by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryPath by remember { mutableStateOf(start) }
    var retryOffset by remember { mutableStateOf(0) }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRecents by remember { mutableStateOf(false) }

    DisposableEffect(browser) {
        onDispose { ended = true; browser.close() }
    }

    fun dismiss() {
        if (!ended) {
            ended = true
            browser.close()
            onDismiss()
        }
    }

    fun navigate(path: String, offset: Int = 0, forceRefresh: Boolean = false) {
        if (busy || ended) return
        busy = true
        error = null
        retryPath = path
        retryOffset = offset
        scope.launch {
            try {
                val loaded = browser.list(path, offset, forceRefresh)
                if (!ended) {
                    page = loaded
                    pageOffset = offset
                    input = loaded.path
                    showRecents = false
                    recents = withContext(Dispatchers.IO) { recentsStore.visit(loaded.path) }
                    listState.scrollToItem(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!ended) error = failure.message ?: "读取目录失败"
            } finally {
                busy = false
            }
        }
    }

    fun select() {
        val current = page ?: return
        if (busy || ended || error != null) return
        busy = true
        scope.launch {
            val selected = try {
                val validated = browser.validateSelection(current.path, requireWritable)
                // A symlink/mount may have changed since listing. Require another navigation.
                check(validated == current.path) { "目录真实路径已改变，请重新打开后选择" }
                withContext(Dispatchers.IO) { recentsStore.visit(validated) }
                validated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!ended) error = failure.message ?: "目录校验失败"
                null
            } finally {
                busy = false
            }
            // Do not catch exceptions from the caller's callback as filesystem errors.
            if (selected != null && !ended) {
                ended = true
                browser.close()
                onSelected(selected)
            }
        }
    }

    LaunchedEffect(browser) {
        recents = withContext(Dispatchers.IO) { recentsStore.read() }
        navigate(start)
    }

    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp).imePadding(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ROOT 文件夹选择器", style = MaterialTheme.typography.titleLarge)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            if (requireWritable) "仅显示目录；选择时检查 ROOT 可读、可写权限（接收／双向）。"
                            else "仅显示目录；选择时检查 ROOT 可读权限（仅发送），不要求可写。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("绝对路径") },
                            singleLine = true,
                            enabled = !busy && !ended,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TextButton(
                                onClick = { navigate(input) },
                                enabled = !busy && !ended
                            ) { Text("转到路径") }
                            TextButton(
                                onClick = {
                                    page?.path?.let(RootDirectoryProtocol::parent)?.let { navigate(it) }
                                },
                                enabled = !busy && !ended && page?.path?.let {
                                    RootDirectoryProtocol.parent(it) != null
                                } == true
                            ) { Text("上级") }
                        }
                    }
                    item {
                        Row {
                            TextButton(
                                onClick = { navigate(RootDirectoryBrowser.STORAGE) },
                                enabled = !busy && !ended
                            ) { Text("内部存储") }
                            TextButton(
                                onClick = { navigate(RootDirectoryBrowser.ANDROID_DATA) },
                                enabled = !busy && !ended
                            ) { Text("Android/data") }
                            TextButton(
                                onClick = { showRecents = !showRecents },
                                enabled = !busy && !ended
                            ) { Text("最近") }
                        }
                    }
                    if (showRecents) {
                        item { Text("最近访问（最多 10 个）", style = MaterialTheme.typography.titleSmall) }
                        if (recents.isEmpty()) item { Text("暂无记录") }
                        items(recents, key = { "recent:$it" }) { path ->
                            TextButton(onClick = { navigate(path) }, enabled = !busy && !ended) {
                                Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text("当前目录：${page?.path ?: "尚未打开"}", modifier = Modifier.padding(top = 8.dp))
                        if (input != page?.path && page != null) {
                            Text("输入框尚未转到的路径不会被选中。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (error != null) {
                        item {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = { navigate(retryPath, retryOffset, forceRefresh = true) },
                                enabled = !busy && !ended
                            ) { Text("重新读取") }
                        }
                    }
                    val current = page
                    if (current != null) {
                        if (current.directories.isEmpty() && !busy) {
                            item { Text(if (pageOffset == 0) "此目录下没有子目录" else "本页已无目录，请刷新") }
                        }
                        items(current.directories, key = { "dir:$it" }) { child ->
                            val blocked = SafeInput.validateStoragePath(child)
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !busy && !ended && blocked == null) { navigate(child) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    child.substringAfterLast('/').replace("\r", "\\r").replace("\n", "\\n"),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (blocked != null) Text(
                                    blocked,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        item {
                            Text(
                                (if (current.fromCache) "短时缓存 · " else "") + "每页最多 ${RootDirectoryBrowser.PAGE_SIZE} 个目录；目录变化时请刷新。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row {
                                TextButton(
                                    onClick = { navigate(current.path, (pageOffset - RootDirectoryBrowser.PAGE_SIZE).coerceAtLeast(0)) },
                                    enabled = pageOffset > 0 && !busy && !ended
                                ) { Text("上一页") }
                                TextButton(
                                    onClick = { current.nextOffset?.let { navigate(current.path, it) } },
                                    enabled = current.nextOffset != null && !busy && !ended
                                ) { Text("下一页") }
                                TextButton(
                                    onClick = { navigate(current.path, forceRefresh = true) },
                                    enabled = !busy && !ended
                                ) { Text("刷新") }
                            }
                        }
                    }
                }
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("正在通过 ROOT 读取／校验…")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::dismiss, enabled = !ended) { Text("取消") }
                    Button(
                        onClick = ::select,
                        enabled = page != null && !busy && !ended && error == null
                    ) { Text("选择当前目录") }
                }
            }
        }
    }
}

private class RootFolderRecents(context: Context) {
    private val preferences by lazy {
        context.getSharedPreferences("root_folder_picker", Context.MODE_PRIVATE)
    }

    fun read(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("recent_directories", "[]") ?: "[]")
        (0 until minOf(array.length(), 10)).mapNotNull { index ->
            runCatching { RootDirectoryProtocol.normalize(array.getString(index)) }.getOrNull()
        }.distinct()
    }.getOrDefault(emptyList())

    fun visit(path: String): List<String> {
        val paths = (listOf(path) + read()).distinct().take(10)
        // Preference failure must not make an otherwise readable directory unselectable.
        runCatching {
            preferences.edit().putString("recent_directories", JSONArray(paths).toString()).apply()
        }
        return paths
    }
}

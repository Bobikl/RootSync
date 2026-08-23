package com.rootsync.android.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.rootsync.android.domain.CapabilityCheck
import com.rootsync.android.domain.CheckState
import com.rootsync.android.domain.DiscoveredDevice
import com.rootsync.android.domain.PeerProfile
import com.rootsync.android.domain.SyncActivityType
import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.SyncUiState
import com.rootsync.android.domain.TransferStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootSyncApp(viewModel: SyncViewModel) {
    RootSyncTheme {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var page by remember { mutableIntStateOf(0) }
        val activity = LocalActivity.current

        DisposableEffect(activity, state.isBusy, state.isPreviewing, state.remoteActivity) {
            val localTransferRunning = state.isBusy && !state.isPreviewing &&
                state.transferRecord?.status == TransferStatus.RUNNING
            val remoteTransferRunning = state.remoteActivity?.let { remote ->
                !remote.finished && remote.type == SyncActivityType.TRANSFER
            } == true
            val keepScreenOn = localTransferRunning || remoteTransferRunning
            if (keepScreenOn) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        state.pendingPairRequest?.let { request ->
            AlertDialog(
                onDismissRequest = { viewModel.answerPair(false) },
                title = { Text("允许设备连接？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(request.name, fontWeight = FontWeight.SemiBold)
                        Text("${request.host}:${request.port}")
                        Text(
                            "对方策略：${request.role.label} · ${request.rangeMode.label}；" +
                                "本机会自动设为${request.role.opposite().label}。"
                        )
                        Text(
                            "允许后会保存为独立设备策略，并交换本机 rsync 配对密钥。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.answerPair(true) }) { Text("允许连接") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.answerPair(false) }) { Text("拒绝") }
                }
            )
        }

        state.pendingDirectoryCreation?.let { prompt ->
            AlertDialog(
                onDismissRequest = { viewModel.answerDirectoryCreation(false) },
                title = { Text("创建接收文件夹？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${prompt.deviceName} 准备${if (prompt.isPreview) "扫描差异" else "发送数据"}，" +
                                "但本机接收文件夹不存在。"
                        )
                        Text(
                            prompt.path,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "选择创建后才会继续；拒绝不会创建目录，也不会写入任何文件。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.answerDirectoryCreation(true) }) {
                        Text("创建并继续")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.answerDirectoryCreation(false) }) {
                        Text("拒绝")
                    }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("RootSync", fontWeight = FontWeight.SemiBold)
                            Text(
                                state.remoteActivity?.let { activity ->
                                    if (activity.finished && activity.type == SyncActivityType.PREVIEW) {
                                        "${activity.name} 差异扫描已结束"
                                    } else if (activity.finished) {
                                        "${activity.name} 同步任务已结束"
                                    } else if (activity.type == SyncActivityType.PREVIEW) {
                                        "${activity.name} 正在扫描差异文件夹"
                                    } else {
                                        "${activity.name} 正在与本机同步"
                                    }
                                } ?: state.phase,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf("⇄" to "同步", "◉" to "服务", "≡" to "日志").forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = { Text(item.first, fontSize = 20.sp) },
                            label = { Text(item.second) }
                        )
                    }
                }
            }
        ) { padding ->
            when (page) {
                0 -> SyncPage(state, viewModel, padding)
                1 -> ServerPage(state, viewModel, padding)
                else -> LogsPage(state, viewModel, padding)
            }
        }
    }
}

@Composable
private fun SyncPage(state: SyncUiState, viewModel: SyncViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val configurationEnabled = !state.isBusy && !state.isCheckingPeerOnline
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanLan() else viewModel.onLanPermissionDenied()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.execute() }
    val executeWithNotification = {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.execute()
        }
    }
    val startLanScan = {
        if (
            Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            viewModel.scanLan()
        }
    }
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 两端都要获得局域网权限，才能在首次打开时互相发现并接收配对请求。
            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusHero(state, viewModel::refreshCapabilities, refreshEnabled = configurationEnabled)

        state.remoteActivity?.let { activity ->
            SectionCard(
                title = if (activity.finished && activity.type == SyncActivityType.PREVIEW) {
                    "${activity.name} 差异扫描已结束"
                } else if (activity.finished) {
                    "${activity.name} 同步任务已结束"
                } else if (activity.type == SyncActivityType.PREVIEW) {
                    "${activity.name} 正在扫描差异文件夹"
                } else {
                    "${activity.name} 正在与本机同步"
                },
                subtitle = if (activity.finished) {
                    "完成状态会保留 8 秒，详细过程可在日志页查看。"
                } else {
                    "这是对方设备发起的任务，本机正在提供受限 rsync 服务。"
                },
                emphasized = true
            ) {
                if (!activity.finished) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    if (activity.finished && activity.type == SyncActivityType.PREVIEW) {
                        "对方已结束目录比较，不再直接隐藏提示。"
                    } else if (activity.finished) {
                        "对方已结束本次同步任务。"
                    } else if (activity.type == SyncActivityType.PREVIEW) {
                        "对方正在比较双方目录，扫描完成后此提示会自动消失。"
                    } else {
                        "对方正在按已保存策略传输文件；零删除保护保持开启。"
                    },
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        SectionCard(
            title = "设备策略",
            subtitle = "每台设备独立保存方向、地址和目录；所有传输强制零删除"
        ) {
            if (state.profiles.isEmpty()) {
                Text("还没有保存的设备，可手动填写或从局域网扫描配对。")
            } else {
                state.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == state.selectedProfileId,
                        online = profile.deviceId in state.onlineDeviceIds,
                        enabled = configurationEnabled,
                        onClick = { viewModel.selectProfile(profile.id) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::newProfile,
                    enabled = configurationEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("新建设备")
                }
                OutlinedButton(
                    onClick = viewModel::deleteSelectedProfile,
                    enabled = configurationEnabled && state.selectedProfileId != null,
                    modifier = Modifier.weight(1f)
                ) { Text("删除当前") }
            }
        }

        SectionCard(
            title = "自动扫描局域网",
            subtitle = "使用 Android NSD/mDNS + UDP 8874 双通道发现"
        ) {
            Button(
                onClick = startLanScan,
                enabled = configurationEnabled && !state.isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  正在扫描…")
                } else Text("自动扫描局域网")
            }
            if (state.discoveredDevices.isEmpty()) {
                Text(
                    if (state.isScanning) "等待设备回应…" else "暂未发现设备；请确保两端位于同一 Wi-Fi 并打开 RootSync。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.discoveredDevices.forEach { device ->
                    DiscoveredDeviceRow(
                        device = device,
                        trusted = state.profiles.any { it.deviceId == device.deviceId },
                        enabled = configurationEnabled,
                        onPair = { viewModel.requestPair(device.deviceId) }
                    )
                }
            }
        }

        SectionCard(title = "连接设置", subtitle = "当前：${state.profileName.ifBlank { "未命名设备" }}") {
            OutlinedTextField(
                value = state.profileName,
                onValueChange = viewModel::setProfileName,
                enabled = configurationEnabled,
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.remoteHost,
                    onValueChange = viewModel::setRemoteHost,
                    enabled = configurationEnabled,
                    label = { Text("远端 IP") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.portText,
                    onValueChange = viewModel::setPort,
                    enabled = configurationEnabled,
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.weight(0.55f)
                )
            }
            OutlinedTextField(
                value = state.remoteSecret,
                onValueChange = viewModel::setRemoteSecret,
                enabled = configurationEnabled,
                label = { Text("远端配对密钥") },
                placeholder = { Text("扫描配对后自动填写") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionCard(
            title = "同步策略",
            subtitle = "先选方向，再选同步全部内容或指定时间至今"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SyncRole.entries.forEach { role ->
                    FilterChip(
                        selected = state.role == role,
                        onClick = { viewModel.setRole(role) },
                        enabled = configurationEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                when (role) {
                                    SyncRole.SEND_ONLY ->
                                        "发送给${state.profileName.ifBlank { "远端" }}（对方接收）"
                                    SyncRole.RECEIVE_ONLY ->
                                        "从${state.profileName.ifBlank { "远端" }}接收"
                                    SyncRole.BIDIRECTIONAL ->
                                        "与${state.profileName.ifBlank { "远端" }}双向同步"
                                }
                            )
                        }
                    )
                }
            }
            Text("同步范围", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncRangeMode.entries.forEach { rangeMode ->
                    FilterChip(
                        selected = state.rangeMode == rangeMode,
                        onClick = { viewModel.setRangeMode(rangeMode) },
                        enabled = configurationEnabled,
                        label = { Text(rangeMode.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (state.rangeMode == SyncRangeMode.SINCE) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("起始时间", style = MaterialTheme.typography.labelMedium)
                            Text(
                                formatSyncTime(state.sinceEpochMillis),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "包含该时间至点击同步时发生变化的文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        OutlinedButton(onClick = {
                            showDateTimePicker(
                                context = context,
                                initialMillis = state.sinceEpochMillis
                                    ?: System.currentTimeMillis() - 24L * 60L * 60L * 1000L,
                                onPicked = viewModel::setSinceEpochMillis
                            )
                        }, enabled = configurationEnabled) { Text("选择") }
                    }
                }
            }
            Text(
                "该范围同时适用于只发送、只接收和双向同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("零删除保护已强制开启", fontWeight = FontWeight.SemiBold)
                    Text(
                        "不会删除来源端或目标端文件；目标端独有内容始终保留，覆盖前版本保存到 .rootsync-history。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Text(
                when (state.role) {
                    SyncRole.SEND_ONLY ->
                        "数据方向：本机 → ${state.profileName.ifBlank { "远端" }}；预览时会自动请求远端准备服务。"
                    SyncRole.RECEIVE_ONLY ->
                        "数据方向：${state.profileName.ifBlank { "远端" }} → 本机；本机执行时主动拉取。"
                    SyncRole.BIDIRECTIONAL ->
                        "先接收再发送；较新修改时间优先，并用校验和确认差异。覆盖前版本保留在 .rootsync-history。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "本机目录") {
            when (state.role) {
                SyncRole.SEND_ONLY -> OutlinedTextField(
                    value = state.sourcePath,
                    onValueChange = viewModel::setSourcePath,
                    enabled = configurationEnabled,
                    label = { Text("本机发送源目录") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                SyncRole.RECEIVE_ONLY -> OutlinedTextField(
                    value = state.destinationPath,
                    onValueChange = viewModel::setDestinationPath,
                    enabled = configurationEnabled,
                    label = { Text("本机接收目录（不存在会自动创建）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                SyncRole.BIDIRECTIONAL -> OutlinedTextField(
                    value = state.sourcePath,
                    onValueChange = viewModel::setBidirectionalPath,
                    enabled = configurationEnabled,
                    label = { Text("本机双向同步目录") },
                    supportingText = { Text("同一目录同时用于发送和接收") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedButton(
                onClick = viewModel::saveCurrentProfile,
                enabled = configurationEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存当前设备策略")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = viewModel::preview,
                enabled = state.canStartTransfer,
                modifier = Modifier.weight(1f)
            ) { Text("差异预览") }
            Button(
                onClick = if (state.localTransferActive) {
                    if (state.isPreviewing) viewModel::cancel else viewModel::pauseTransfer
                } else executeWithNotification,
                enabled = state.localTransferActive || state.canStartTransfer,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when {
                        state.isCheckingPeerOnline -> "正在确认在线"
                        state.localTransferActive && state.isPreviewing -> "取消预览"
                        state.localTransferActive -> "暂停传输"
                        state.isBusy -> "对方任务进行中"
                        state.transferRecord?.status == TransferStatus.PAUSED -> "继续传输"
                        else -> "执行${state.role.label}"
                    }
                )
            }
        }
        if (!state.isSelectedPeerOnline && state.selectedPairedDeviceId != null) {
            Text(
                "所选设备当前离线，差异预览、执行和继续传输已禁用。设备重新回应实时探测后才能开始。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        AnimatedVisibility(state.isBusy || state.progress != null) {
            SectionCard(title = state.phase) {
                if (state.progress != null) {
                    val trafficProgress = state.progress.coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransferAmount(
                            label = "同步总量",
                            value = if (state.totalSyncBytes > 0L) {
                                formatDataSize(state.totalSyncBytes)
                            } else if (state.isBusy) {
                                "计算中"
                            } else {
                                "0 B"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TransferAmount(
                            label = "已上传",
                            value = formatDataSize(state.uploadedBytes),
                            modifier = Modifier.weight(1f)
                        )
                        TransferAmount(
                            label = "已下载",
                            value = formatDataSize(state.downloadedBytes),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { trafficProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(trafficProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    state.estimatedCompletionTime?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        SectionCard(
            title = state.transferPanelTitle,
            subtitle = "已识别 ${state.transferItemCount} 个差异/传输文件或目录"
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    state.previewStatusText?.let { status ->
                        Text(
                            status,
                            color = if (status.startsWith("扫描失败") || status.startsWith("无法")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                    state.currentTransferFolder?.let { folder ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "当前：$folder",
                                modifier = Modifier.padding(10.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (state.transferFolders.isEmpty()) {
                        if (state.previewStatusText == null) {
                            Text(
                                if (state.isBusy) "正在扫描文件夹，请稍候…"
                                else "点击差异预览后，变化文件和目录会显示在这里。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        state.transferFolders.forEach { folder ->
                            Text("• $folder", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state.transferFoldersTruncated) {
                        Text(
                            "项目较多，仅显示前 400 个文件或目录。",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        state.transferRecord?.takeIf { it.status != TransferStatus.RUNNING }?.let { record ->
            SectionCard(
                title = "传输记录 · ${record.status.label}",
                subtitle = "${record.peerName} · ${record.role.label}",
                emphasized = record.status == TransferStatus.PAUSED
            ) {
                LinearProgressIndicator(
                    progress = { record.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("记录进度 ${(record.progress * 100).toInt()}%")
                if (record.message.isNotBlank()) {
                    Text(record.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (record.status == TransferStatus.PAUSED && !state.isBusy) {
                    Button(onClick = executeWithNotification, modifier = Modifier.fillMaxWidth()) {
                        Text("继续传输")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::deleteTransferRecord,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这条传输记录")
                }
            }
        }

        state.lastResult?.let { result ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(result, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TransferAmount(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

private fun formatDataSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var index = 0
    while (value >= 1000.0 && index < units.lastIndex) {
        value /= 1000.0
        index += 1
    }
    return if (index == 0) "${value.toLong()} ${units[index]}"
    else String.format(Locale.US, "%.2f %s", value, units[index])
}

@Composable
private fun ProfileRow(
    profile: PeerProfile,
    selected: Boolean,
    online: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    profile.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (online) Color(0xFF2E7D32) else Color(0xFFC62828),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(11.dp)
                ) {}
            }
            Text(
                "${profile.host}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when (profile.role) {
                    SyncRole.SEND_ONLY -> "本机发送 → ${profile.name}接收"
                    SyncRole.RECEIVE_ONLY -> "${profile.name}发送 → 本机接收"
                    SyncRole.BIDIRECTIONAL -> "本机 ⇄ ${profile.name}"
                } + " · ${profile.rangeMode.label} · 零删除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    device: DiscoveredDevice,
    trusted: Boolean,
    enabled: Boolean,
    onPair: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Medium)
                Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onPair, enabled = enabled) { Text(if (trusted) "已连接" else "配对") }
        }
    }
}

@Composable
private fun ServerPage(state: SyncUiState, viewModel: SyncViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard(
            title = if (state.serverRunning) "服务端正在运行" else "本机 rsync 服务",
            subtitle = "send 模块只读、receive 模块只写；不开放根文件系统",
            emphasized = state.serverRunning
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(state.deviceName) })
                AssistChip(onClick = {}, label = { Text("IP  ${state.localIp}") })
                AssistChip(onClick = {}, label = { Text("端口  ${state.serverPortText}") })
            }
            OutlinedTextField(
                value = state.serverPortText,
                onValueChange = viewModel::setServerPort,
                enabled = !state.serverRunning,
                label = { Text("本机监听端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.sourcePath,
                onValueChange = viewModel::setSourcePath,
                enabled = !state.serverRunning,
                label = { Text("send：本机发送源目录") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.destinationPath,
                onValueChange = viewModel::setDestinationPath,
                enabled = !state.serverRunning,
                label = { Text("receive：本机接收目录（自动创建）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.serverSecret,
                onValueChange = viewModel::setServerSecret,
                enabled = !state.serverRunning,
                label = { Text("本机密钥（可自定义）") },
                supportingText = { Text("至少 6 位；局域网内两端一致即可") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("RootSync secret", state.serverSecret))
                }) { Text("复制密钥") }
                TextButton(onClick = viewModel::regenerateSecret, enabled = !state.serverRunning) {
                    Text("重新生成")
                }
            }
            Button(
                onClick = if (state.serverRunning) viewModel::stopServer else viewModel::startServer,
                enabled = state.serverRunning || state.canOperate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.serverRunning) "停止服务端" else "生成快照并启动服务端")
            }
            Text(
                "服务启动后 rsync daemon 独立运行；已配对设备可按各自策略连接。本页关闭不会自动停止服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "设备能力", subtitle = "ROOT 弹窗由当前 ROOT 管理器提供") {
            state.capabilities.checks.forEachIndexed { index, check ->
                CapabilityRow(check)
                if (index != state.capabilities.checks.lastIndex) HorizontalDivider()
            }
            OutlinedButton(
                onClick = viewModel::refreshCapabilities,
                enabled = !state.isChecking && !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isChecking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else Text("重新检查")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LogsPage(state: SyncUiState, viewModel: SyncViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let(viewModel::exportDiagnosticLog)
    }
    val exportFileName = remember {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(System.currentTimeMillis())
        "RootSync-diagnostic-$timestamp.txt"
    }
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("实时日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("界面保留 400 行；详细日志跨重启持久保存", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = viewModel::clearLogs) { Text("清空界面") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch(exportFileName) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出详细日志")
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("RootSync ADB command", viewModel.diagnosticAdbCommand())
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制 ADB 命令")
                }
            }
            Text(
                "持久日志：${viewModel.diagnosticLogPath()}（当前与上一段合计最多约 8 MB）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        if (state.logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                reverseLayout = true
            ) {
                items(state.logs.asReversed()) { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(entry.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        Text(
                            entry.level,
                            color = logColor(entry.level),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.size(width = 44.dp, height = 20.dp)
                        )
                        Text(entry.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHero(state: SyncUiState, onRefresh: () -> Unit, refreshEnabled: Boolean = true) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            state.isChecking -> "正在检查设备"
                            state.capabilities.rootGranted && state.capabilities.rsyncPath != null -> "已准备好同步"
                            state.capabilities.rootGranted -> "内置 rsync 不可执行"
                            else -> "需要 ROOT 授权"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Android 15/16 · arm64 · rsync 3.4.4",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
                if (state.isChecking) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else TextButton(onClick = onRefresh, enabled = refreshEnabled) { Text("刷新") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("ROOT", state.capabilities.rootGranted)
                StatusPill("rsync", state.capabilities.rsyncPath != null)
                StatusPill("mtime", state.capabilities.syncMetaReady)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, ready: Boolean) {
    Surface(
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            (if (ready) "✓ " else "— ") + text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun CapabilityRow(check: CapabilityCheck) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            when (check.state) {
                CheckState.PASS -> "✓"
                CheckState.WARNING -> "!"
                CheckState.FAIL -> "×"
                CheckState.CHECKING -> "…"
            },
            color = when (check.state) {
                CheckState.PASS -> Color(0xFF2E7D32)
                CheckState.WARNING -> Color(0xFFB26A00)
                CheckState.FAIL -> MaterialTheme.colorScheme.error
                CheckState.CHECKING -> MaterialTheme.colorScheme.primary
            },
            fontWeight = FontWeight.Bold
        )
        Column(Modifier.weight(1f)) {
            Text(check.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

private fun formatSyncTime(epochMillis: Long?): String {
    if (epochMillis == null) return "请选择"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(epochMillis)
}

private fun showDateTimePicker(
    context: Context,
    initialMillis: Long,
    onPicked: (Long) -> Unit
) {
    val initial = Calendar.getInstance().apply { timeInMillis = initialMillis }
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(selected.timeInMillis.coerceAtMost(System.currentTimeMillis()))
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                true
            ).show()
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH)
    )
    dateDialog.datePicker.maxDate = System.currentTimeMillis()
    dateDialog.show()
}

@Composable
private fun logColor(level: String): Color = when (level) {
    "ERROR" -> MaterialTheme.colorScheme.error
    "WARN" -> Color(0xFFB26A00)
    "OK" -> Color(0xFF2E7D32)
    "RSYNC", "LAN" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

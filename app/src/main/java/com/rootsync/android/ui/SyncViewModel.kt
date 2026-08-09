package com.rootsync.android.ui

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rootsync.android.discovery.LanDiscoveryManager
import com.rootsync.android.domain.LogEntry
import com.rootsync.android.domain.PairAccepted
import com.rootsync.android.domain.PairRequest
import com.rootsync.android.domain.PeerProfile
import com.rootsync.android.domain.StrategyUpdate
import com.rootsync.android.domain.SyncPrepareRequest
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.SyncUiState
import com.rootsync.android.domain.TransferRecord
import com.rootsync.android.domain.TransferStatus
import com.rootsync.android.domain.TrustedPeerUpdate
import com.rootsync.android.engine.RootSyncEngine
import com.rootsync.android.engine.RsyncItem
import com.rootsync.android.engine.SafeInput
import com.rootsync.android.service.TransferForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = RootSyncEngine(application)
    private val preferences = application.getSharedPreferences("rootsync", 0)
    private val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit { putString("deviceId", it) }
    }
    private val deviceName = resolveDeviceName(application)
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()
    @Volatile private var pauseRequested = false
    @Volatile private var lastTransferPersistMillis = 0L
    private val discovery = LanDiscoveryManager(
        context = application,
        deviceId = deviceId,
        deviceName = deviceName,
        localPort = { SafeInput.parsePort(_state.value.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT },
        localSecret = { _state.value.serverSecret },
        isTrustedDevice = { remoteId ->
            _state.value.profiles.any { it.deviceId == remoteId && !it.deviceId.startsWith("manual:") }
        },
        onLog = { message -> appendLog("LAN", message) }
    )

    init {
        _state.value.transferRecord?.takeIf { it.status == TransferStatus.RUNNING }?.let { record ->
            _state.update {
                it.copy(
                    transferRecord = record.copy(
                        status = TransferStatus.PAUSED,
                        updatedAtMillis = System.currentTimeMillis(),
                        message = "应用上次退出时传输中断，可点击继续传输"
                    ),
                    phase = "检测到未完成传输",
                    progress = record.progress,
                    lastResult = "上次传输已安全转为暂停，可继续"
                )
            }
            persistTransferRecord()
            TransferForegroundService.stop(application)
            viewModelScope.launch { engine.pauseTransfer() }
        }
        // 新安装首次生成的密钥也立即落盘，避免进程在能力检查前退出后重新生成。
        saveConfig()
        discovery.start()
        viewModelScope.launch {
            discovery.devices.collect { devices ->
                _state.update { it.copy(discoveredDevices = devices) }
            }
        }
        viewModelScope.launch {
            discovery.pairRequests.collect { request ->
                _state.update { it.copy(pendingPairRequest = request) }
                appendLog("LAN", "${request.name}（${request.host}）请求配对")
            }
        }
        viewModelScope.launch {
            discovery.pairAccepted.collect { accepted ->
                upsertPairedDevice(accepted)
                appendLog("OK", "已与 ${accepted.name} 完成局域网配对")
                _state.update { it.copy(lastResult = "已连接 ${accepted.name}") }
            }
        }
        viewModelScope.launch {
            discovery.strategyUpdates.collect { update -> applyRemoteStrategy(update) }
        }
        viewModelScope.launch {
            discovery.syncPrepareRequests.collect { request -> handleSyncPrepareRequest(request) }
        }
        viewModelScope.launch {
            discovery.trustedPeerUpdates.collect { update -> applyTrustedPeerUpdate(update) }
        }
        refreshCapabilities()
    }

    private fun loadState(): SyncUiState {
        val profiles = decodeProfiles(preferences.getString("profiles", null))
        val selectedId = preferences.getString("selectedProfileId", null)
        val selected = profiles.firstOrNull { it.id == selectedId }
        val storedSource = migrateBiliPath(
            preferences.getString("sourcePath", SyncUiState.DEFAULT_BILI_PATH)
                ?: SyncUiState.DEFAULT_BILI_PATH
        )
        val storedDestination = migrateBiliPath(
            preferences.getString("destinationPath", SyncUiState.DEFAULT_BILI_PATH)
                ?: SyncUiState.DEFAULT_BILI_PATH
        )
        val fallbackRole = runCatching {
            SyncRole.valueOf(preferences.getString("role", SyncRole.RECEIVE_ONLY.name)!!)
        }.getOrDefault(SyncRole.RECEIVE_ONLY)
        return SyncUiState(
            deviceName = deviceName,
            profileName = selected?.name ?: preferences.getString("profileName", "手动设备").orEmpty(),
            remoteHost = selected?.host ?: preferences.getString("remoteHost", "").orEmpty(),
            portText = (selected?.port?.toString()
                ?: preferences.getString("port", SyncUiState.DEFAULT_RSYNC_PORT.toString())).orEmpty(),
            serverPortText = preferences.getString(
                "serverPort",
                SyncUiState.DEFAULT_RSYNC_PORT.toString()
            ).orEmpty(),
            sourcePath = selected?.sourcePath ?: storedSource,
            destinationPath = selected?.destinationPath ?: storedDestination,
            serverSecret = preferences.getString("serverSecret", null)
                ?: preferences.getString("secret", null)
                ?: engine.generateSecret(),
            remoteSecret = selected?.secret ?: preferences.getString("remoteSecret", "").orEmpty(),
            role = selected?.role ?: fallbackRole,
            profiles = profiles,
            selectedProfileId = selected?.id,
            transferRecord = decodeTransferRecord(preferences.getString("transferRecord", null)),
            localIp = engine.localIpv4()
        )
    }

    fun refreshCapabilities() {
        viewModelScope.launch {
            _state.update { it.copy(isChecking = true, phase = "正在请求 ROOT 并检查能力") }
            appendLog("INFO", "开始 ROOT、内置 rsync 与目录检查")
            val before = _state.value
            val capabilities = engine.probe(before.sourcePath, before.destinationPath)
            _state.update { current ->
                val detected = capabilities.detectedMediaPath
                val canReplaceSource = current.sourcePath == SyncUiState.DEFAULT_BILI_PATH ||
                    current.sourcePath == SyncUiState.LEGACY_BILI_PATH
                val canReplaceDestination = current.destinationPath == SyncUiState.DEFAULT_BILI_PATH ||
                    current.destinationPath == SyncUiState.LEGACY_BILI_PATH
                current.copy(
                    isChecking = false,
                    capabilities = capabilities,
                    sourcePath = if (detected != null && canReplaceSource) detected else current.sourcePath,
                    destinationPath = if (detected != null && canReplaceDestination) detected else current.destinationPath,
                    localIp = engine.localIpv4(),
                    phase = if (capabilities.rootGranted) "能力检查完成" else "ROOT 不可用"
                ).syncSelectedProfile()
            }
            saveConfig()
            capabilities.checks.forEach { appendLog("CHECK", "${it.name}：${it.detail}") }
        }
    }

    fun setProfileName(value: String) = updateConfig {
        copy(profileName = value.take(32), previewReady = false)
    }
    fun setRemoteHost(value: String) = updateConfig {
        copy(remoteHost = value.trim(), previewReady = false)
    }
    fun setPort(value: String) = updateConfig {
        copy(portText = value.filter(Char::isDigit).take(5), previewReady = false)
    }
    fun setServerPort(value: String) = updateConfig {
        copy(serverPortText = value.filter(Char::isDigit).take(5), previewReady = false)
    }
    fun setSourcePath(value: String) = updateConfig { copy(sourcePath = value, previewReady = false) }
    fun setDestinationPath(value: String) = updateConfig { copy(destinationPath = value, previewReady = false) }
    fun setRemoteSecret(value: String) = updateConfig {
        copy(remoteSecret = value.trim().take(128), previewReady = false)
    }
    fun setServerSecret(value: String) = updateConfig {
        copy(
            serverSecret = value.filterNot { it.isISOControl() }.trim().take(64),
            previewReady = false
        )
    }
    fun setRole(value: SyncRole) {
        updateConfig { copy(role = value, previewReady = false) }
        publishCurrentStrategy()
    }

    private fun updateConfig(block: SyncUiState.() -> SyncUiState) {
        _state.update { it.block().syncSelectedProfile() }
        saveConfig()
    }

    private fun SyncUiState.syncSelectedProfile(): SyncUiState {
        val selected = selectedProfileId ?: return this
        val updated = profiles.map { profile ->
            if (profile.id != selected) profile else profile.copy(
                name = profileName.ifBlank { profile.name },
                host = remoteHost,
                port = SafeInput.parsePort(portText) ?: profile.port,
                secret = remoteSecret,
                role = role,
                sourcePath = sourcePath,
                destinationPath = destinationPath
            )
        }
        return copy(profiles = updated)
    }

    fun saveCurrentProfile() {
        val current = _state.value
        val port = SafeInput.parsePort(current.portText)
        when {
            !SafeInput.isValidIpv4(current.remoteHost) -> appendLog("ERROR", "请输入有效的远端 IPv4 地址")
            port == null -> appendLog("ERROR", "端口必须位于 1024–65535")
            current.remoteSecret.length < SyncUiState.MIN_SECRET_LENGTH ->
                appendLog("ERROR", "密钥至少需要 ${SyncUiState.MIN_SECRET_LENGTH} 位")
            else -> {
                val id = current.selectedProfileId ?: UUID.randomUUID().toString()
                val existing = current.profiles.firstOrNull { it.id == id }
                val profile = PeerProfile(
                    id = id,
                    deviceId = existing?.deviceId ?: "manual:${current.remoteHost}:$port",
                    name = current.profileName.ifBlank { "${current.remoteHost}:$port" },
                    host = current.remoteHost,
                    port = port,
                    secret = current.remoteSecret,
                    role = current.role,
                    sourcePath = current.sourcePath,
                    destinationPath = current.destinationPath
                )
                _state.update {
                    it.copy(
                        profiles = it.profiles.filterNot { item -> item.id == id } + profile,
                        selectedProfileId = id,
                        profileName = profile.name,
                        lastResult = "已保存 ${profile.name} 的独立同步策略"
                    )
                }
                saveConfig()
                appendLog("OK", "已保存设备策略：${profile.name} / ${profile.role.label}")
                publishCurrentStrategy()
            }
        }
    }

    fun selectProfile(id: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                selectedProfileId = profile.id,
                profileName = profile.name,
                remoteHost = profile.host,
                portText = profile.port.toString(),
                remoteSecret = profile.secret,
                role = profile.role,
                sourcePath = profile.sourcePath,
                destinationPath = profile.destinationPath,
                previewReady = false,
                lastResult = "已切换到 ${profile.name}"
            )
        }
        saveConfig()
    }

    fun newProfile() {
        _state.update {
            it.copy(
                selectedProfileId = null,
                profileName = "新设备",
                remoteHost = "",
                remoteSecret = "",
                role = SyncRole.RECEIVE_ONLY,
                previewReady = false,
                lastResult = null
            )
        }
        saveConfig()
    }

    fun deleteSelectedProfile() {
        val id = _state.value.selectedProfileId ?: return
        val removed = _state.value.profiles.firstOrNull { it.id == id } ?: return
        _state.update {
            val removesTransfer = it.transferRecord?.let { record ->
                record.profileId == removed.id || record.deviceId == removed.deviceId
            } == true
            it.copy(
                profiles = it.profiles.filterNot { profile -> profile.id == id },
                selectedProfileId = null,
                profileName = "新设备",
                remoteHost = "",
                remoteSecret = "",
                previewReady = false,
                transferRecord = if (removesTransfer) null else it.transferRecord,
                lastResult = "已删除 ${removed.name}"
            )
        }
        saveConfig()
    }

    fun scanLan() {
        if (_state.value.isScanning) return
        _state.update { it.copy(isScanning = true) }
        discovery.scan()
        viewModelScope.launch {
            delay(LanDiscoveryManager.SCAN_WINDOW_MS)
            _state.update { it.copy(isScanning = false) }
        }
    }

    fun onLanPermissionDenied() {
        appendLog("ERROR", "未授予“附近设备”权限，Android 16 可能会阻止局域网扫描")
        _state.update { it.copy(isScanning = false, lastResult = "请允许附近设备权限后重新扫描") }
    }

    fun requestPair(deviceId: String) {
        val device = _state.value.discoveredDevices.firstOrNull { it.deviceId == deviceId } ?: return
        val current = _state.value
        val trusted = current.profiles.firstOrNull { it.deviceId == deviceId }
        if (trusted != null) {
            selectProfile(trusted.id)
            applyTrustedPeerUpdate(
                TrustedPeerUpdate(device.deviceId, device.name, device.host, device.port)
            )
            discovery.reconnectTrusted(device)
            _state.update { it.copy(lastResult = "正在恢复与 ${device.name} 的已保存连接") }
            return
        }
        discovery.requestPair(device, current.role)
        _state.update { it.copy(lastResult = "等待 ${device.name} 确认连接") }
    }

    fun answerPair(allow: Boolean) {
        val request = _state.value.pendingPairRequest ?: return
        discovery.answerPair(request, allow)
        if (allow) upsertPairedDevice(request.toAccepted())
        _state.update {
            it.copy(
                pendingPairRequest = null,
                lastResult = if (allow) "已允许 ${request.name} 连接" else "已拒绝连接"
            )
        }
    }

    private fun PairRequest.toAccepted() = PairAccepted(
        deviceId = deviceId,
        name = name,
        host = host,
        port = port,
        secret = secret,
        role = role.opposite()
    )

    private fun upsertPairedDevice(device: PairAccepted) {
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == device.deviceId }
        val profile = existing?.copy(
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
            role = device.role
        ) ?: PeerProfile(
            id = UUID.randomUUID().toString(),
            deviceId = device.deviceId,
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
            role = device.role,
            sourcePath = current.sourcePath,
            destinationPath = current.destinationPath
        )
        _state.update {
            it.copy(
                profiles = it.profiles.filterNot { item -> item.deviceId == device.deviceId } + profile,
                selectedProfileId = profile.id,
                profileName = profile.name,
                remoteHost = profile.host,
                portText = profile.port.toString(),
                remoteSecret = profile.secret,
                role = profile.role,
                sourcePath = profile.sourcePath,
                destinationPath = profile.destinationPath,
                previewReady = false
            )
        }
        saveConfig()
    }

    private fun applyTrustedPeerUpdate(update: TrustedPeerUpdate) {
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == update.deviceId } ?: return
        val rotatedSecret = update.secret?.takeIf { it.length >= SyncUiState.MIN_SECRET_LENGTH }
        val updated = existing.copy(
            name = update.name,
            host = update.host,
            port = update.port,
            secret = rotatedSecret ?: existing.secret
        )
        _state.update { state ->
            val selected = state.selectedProfileId == existing.id
            state.copy(
                profiles = state.profiles.map { if (it.id == existing.id) updated else it },
                profileName = if (selected) updated.name else state.profileName,
                remoteHost = if (selected) updated.host else state.remoteHost,
                portText = if (selected) updated.port.toString() else state.portText,
                remoteSecret = if (selected) updated.secret else state.remoteSecret,
                selectedProfileId = if (selected) updated.id else state.selectedProfileId,
                lastResult = if (rotatedSecret != null) "已恢复与 ${updated.name} 的信任连接" else state.lastResult
            )
        }
        saveConfig()
    }

    private fun publishCurrentStrategy() {
        val current = _state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        if (profile.deviceId.startsWith("manual:")) return
        discovery.sendStrategy(profile.host, current.role)
    }

    private fun applyRemoteStrategy(update: StrategyUpdate) {
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == update.deviceId }
        if (existing == null) {
            appendLog("WARN", "已忽略 ${update.name} 的未认证策略更新")
            return
        }
        if (existing.secret != update.secret) {
            appendLog("LAN", "${update.name} 的配对密钥已自动轮换，保留原信任关系")
        }
        val localRole = update.role.opposite()
        val updatedProfile = existing.copy(
            name = update.name,
            host = update.host,
            secret = update.secret,
            role = localRole
        )
        _state.update { state ->
            val selected = state.selectedProfileId == existing.id
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id == existing.id) updatedProfile else profile
                },
                profileName = if (selected) updatedProfile.name else state.profileName,
                remoteHost = if (selected) updatedProfile.host else state.remoteHost,
                remoteSecret = if (selected) updatedProfile.secret else state.remoteSecret,
                role = if (selected) localRole else state.role,
                previewReady = if (selected) false else state.previewReady,
                lastResult = "${update.name} 已设为${update.role.label}；本机自动切换为${localRole.label}"
            )
        }
        saveConfig()
        appendLog(
            "LAN",
            "零删除策略已同步：${update.name} ${update.role.label}，本机 ${localRole.label}"
        )
    }

    private suspend fun handleSyncPrepareRequest(request: SyncPrepareRequest) {
        val initial = _state.value
        val profile = initial.profiles.firstOrNull { it.deviceId == request.deviceId }
        val port = SafeInput.parsePort(initial.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT
        if (profile == null) {
            appendLog("WARN", "已拒绝 ${request.name} 的未认证同步准备请求")
            discovery.answerSyncPreparation(request, false, "设备未配对或已主动删除", port)
            return
        }
        if (profile.secret != request.secret) {
            appendLog("LAN", "${request.name} 的密钥发生变化，按已保存设备 ID 自动恢复信任")
        }
        if (initial.isBusy) {
            discovery.answerSyncPreparation(request, false, "远端设备正在执行其他任务", port)
            return
        }

        applyRemoteStrategy(
            StrategyUpdate(
                request.deviceId,
                request.name,
                request.host,
                request.secret,
                request.role
            )
        )
        val localRole = request.role.opposite()
        val activeProfile = _state.value.profiles.first { it.deviceId == request.deviceId }
        val rsync = _state.value.capabilities.rsyncPath
        if (rsync == null) {
            discovery.answerSyncPreparation(request, false, "远端 ROOT/rsync 能力尚未就绪", port)
            return
        }

        _state.update { it.copy(isBusy = true, phase = "正在为 ${request.name} 准备服务端") }
        val result = try {
            engine.startServer(
                rsyncPath = rsync,
                sourcePath = activeProfile.sourcePath,
                destinationPath = activeProfile.destinationPath,
                port = port,
                secret = _state.value.serverSecret,
                onLog = ::streamLog,
                mode = localRole
            )
        } catch (error: Exception) {
            com.rootsync.android.engine.EngineResult(
                false,
                error.message ?: error::class.java.simpleName
            )
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
        _state.update {
            it.copy(
                serverRunning = result.success,
                phase = result.summary,
                lastResult = result.summary
            )
        }
        appendLog(if (result.success) "OK" else "ERROR", "${request.name}：${result.summary}")
        discovery.answerSyncPreparation(request, result.success, result.summary, port)
    }

    private fun saveConfig() {
        val value = _state.value
        preferences.edit {
            putString("profileName", value.profileName)
            putString("remoteHost", value.remoteHost)
            putString("port", value.portText)
            putString("serverPort", value.serverPortText)
            putString("sourcePath", value.sourcePath)
            putString("destinationPath", value.destinationPath)
            putString("serverSecret", value.serverSecret)
            putString("remoteSecret", value.remoteSecret)
            putString("role", value.role.name)
            putString("profiles", encodeProfiles(value.profiles))
            putString("selectedProfileId", value.selectedProfileId)
            value.transferRecord?.let { putString("transferRecord", encodeTransferRecord(it)) }
                ?: remove("transferRecord")
        }
    }

    private fun persistTransferRecord() {
        preferences.edit {
            _state.value.transferRecord?.let { putString("transferRecord", encodeTransferRecord(it)) }
                ?: remove("transferRecord")
        }
    }

    fun regenerateSecret() {
        _state.update { it.copy(serverSecret = engine.generateSecret(), previewReady = false) }
        saveConfig()
        appendLog("INFO", "已生成新的本机配对密钥，需要重启服务端")
    }

    fun startServer() {
        val current = _state.value
        val port = SafeInput.parsePort(current.serverPortText)
        val rsync = current.capabilities.rsyncPath
        val sourceError = SafeInput.validateStoragePath(current.sourcePath)
        val destinationError = SafeInput.validateStoragePath(current.destinationPath)
        when {
            rsync == null -> appendLog("ERROR", "内置 rsync 不可执行")
            port == null -> appendLog("ERROR", "端口必须位于 1024–65535")
            sourceError != null -> appendLog("ERROR", sourceError)
            destinationError != null -> appendLog("ERROR", destinationError)
            else -> launchBusy("正在启动服务端") {
                val result = engine.startServer(
                    rsync,
                    current.sourcePath,
                    current.destinationPath,
                    port,
                    current.serverSecret,
                    ::streamLog
                )
                _state.update {
                    it.copy(
                        serverRunning = result.success,
                        lastResult = result.summary,
                        phase = result.summary
                    )
                }
                appendLog(if (result.success) "OK" else "ERROR", result.summary)
            }
        }
    }

    fun stopServer() {
        launchBusy("正在停止服务端") {
            val result = engine.stopServer(::streamLog)
            _state.update {
                it.copy(serverRunning = false, lastResult = result.summary, phase = result.summary)
            }
            appendLog(if (result.success) "OK" else "ERROR", result.summary)
        }
    }

    fun preview() = runTransfer(dryRun = true)

    fun execute() {
        if (_state.value.transferRecord?.status == TransferStatus.PAUSED) resumeTransfer()
        else runTransfer(dryRun = false)
    }

    fun resumeTransfer() {
        val record = _state.value.transferRecord?.takeIf { it.status == TransferStatus.PAUSED } ?: return
        val profile = _state.value.profiles.firstOrNull {
            it.id == record.profileId || it.deviceId == record.deviceId
        }
        _state.update {
            it.copy(
                selectedProfileId = profile?.id ?: record.profileId,
                profileName = profile?.name ?: record.peerName,
                remoteHost = profile?.host ?: record.host,
                portText = (profile?.port ?: record.port).toString(),
                remoteSecret = profile?.secret ?: record.secret,
                role = record.role,
                sourcePath = record.sourcePath,
                destinationPath = record.destinationPath,
                lastResult = "正在继续上次未完成传输"
            )
        }
        saveConfig()
        runTransfer(dryRun = false, resumeRecord = record)
    }

    private fun runTransfer(dryRun: Boolean, resumeRecord: TransferRecord? = null) {
        val current = _state.value
        val rsync = current.capabilities.rsyncPath
        val port = SafeInput.parsePort(current.portText)
        val localPath = if (current.role == SyncRole.SEND_ONLY) current.sourcePath else current.destinationPath
        val validation = when {
            rsync == null -> "内置 rsync 不可执行"
            !SafeInput.isValidIpv4(current.remoteHost) -> "请输入有效的远端 IPv4 地址"
            port == null -> "端口必须位于 1024–65535"
            current.remoteSecret.length < SyncUiState.MIN_SECRET_LENGTH -> "请完成配对或输入远端密钥"
            else -> SafeInput.validateStoragePath(localPath)
        }
        if (validation != null || rsync == null || port == null) {
            appendLog("ERROR", validation ?: "配置无效")
            _state.update { it.copy(lastResult = validation) }
            return
        }

        val action = if (current.role == SyncRole.SEND_ONLY) "只发送" else "只接收"
        launchBusy(if (dryRun) "正在预览${action}差异" else "正在执行$action") {
            pauseRequested = false
            val now = System.currentTimeMillis()
            val activeRecord = if (dryRun) null else resumeRecord?.copy(
                host = current.remoteHost,
                port = port,
                secret = current.remoteSecret,
                status = TransferStatus.RUNNING,
                updatedAtMillis = now,
                message = "正在继续传输"
            ) ?: TransferRecord(
                id = UUID.randomUUID().toString(),
                profileId = current.selectedProfileId,
                deviceId = current.profiles.firstOrNull { it.id == current.selectedProfileId }?.deviceId,
                peerName = current.profileName.ifBlank { current.remoteHost },
                host = current.remoteHost,
                port = port,
                secret = current.remoteSecret,
                role = current.role,
                sourcePath = current.sourcePath,
                destinationPath = current.destinationPath,
                status = TransferStatus.RUNNING,
                message = "正在建立连接"
            )
            _state.update {
                it.copy(
                    progress = if (dryRun) 0f else activeRecord?.progress ?: 0f,
                    estimatedCompletionTime = null,
                    isPreviewing = dryRun,
                    transferPanelTitle = if (dryRun) "差异文件夹" else if (current.role == SyncRole.SEND_ONLY) {
                        "正在上传的文件夹"
                    } else {
                        "正在接收的文件夹"
                    },
                    transferFolders = emptyList(),
                    currentTransferFolder = null,
                    transferItemCount = 0,
                    transferFoldersTruncated = false,
                    transferRecord = if (dryRun) it.transferRecord else activeRecord,
                    lastResult = null
                )
            }
            if (!dryRun) {
                persistTransferRecord()
                updateTransferNotification(action, "正在准备远端服务", null, 0f)
            }
            val endpoint = ensureRemoteServerReady(current, port)
            if (endpoint == null) {
                if (!dryRun) markTransferPaused("远端暂时不可用，传输已自动暂停")
                else _state.update { it.copy(isPreviewing = false) }
                return@launchBusy
            }
            val transferStartedAt = System.currentTimeMillis()
            if (!dryRun) updateTransferNotification(action, "已连接，正在扫描文件", null, 0f)
            val onProgress: (Float) -> Unit = { progress ->
                updateTransferProgress(progress, transferStartedAt, dryRun, action)
            }
            val onItem: (RsyncItem) -> Unit = { item ->
                updateTransferItem(item, dryRun, current.role)
            }
            val result = if (current.role == SyncRole.SEND_ONLY) {
                engine.push(
                    rsyncPath = rsync,
                    host = endpoint.host,
                    port = endpoint.port,
                    sourcePath = current.sourcePath,
                    secret = endpoint.secret,
                    dryRun = dryRun,
                    onLog = ::streamLog,
                    onProgress = onProgress,
                    onItem = onItem
                )
            } else {
                engine.pull(
                    rsyncPath = rsync,
                    host = endpoint.host,
                    port = endpoint.port,
                    destinationPath = current.destinationPath,
                    secret = endpoint.secret,
                    dryRun = dryRun,
                    onLog = ::streamLog,
                    onProgress = onProgress,
                    onItem = onItem
                )
            }
            if (!dryRun && pauseRequested) {
                markTransferPaused("用户已暂停；临时分片保留，可继续传输")
                pauseRequested = false
                return@launchBusy
            }
            _state.update {
                val record = it.transferRecord
                it.copy(
                    previewReady = dryRun && result.success,
                    progress = if (!dryRun && result.success) 1f else it.progress,
                    estimatedCompletionTime = if (!dryRun && result.success) "已完成" else it.estimatedCompletionTime,
                    isPreviewing = false,
                    transferRecord = if (!dryRun && record != null) {
                        record.copy(
                            status = if (result.success) TransferStatus.COMPLETED else TransferStatus.PAUSED,
                            progress = if (result.success) 1f else record.progress,
                            updatedAtMillis = System.currentTimeMillis(),
                            message = if (result.success) result.summary else "${result.summary}；已自动暂停"
                        )
                    } else record,
                    lastResult = result.summary,
                    phase = if (!dryRun && !result.success) "连接中断，已自动暂停" else result.summary
                )
            }
            if (!dryRun) {
                persistTransferRecord()
                TransferForegroundService.stop(getApplication())
            }
            appendLog(if (result.success) "OK" else "ERROR", result.summary)
        }
    }

    fun pauseTransfer() {
        if (!_state.value.isBusy || _state.value.isPreviewing) {
            cancel()
            return
        }
        pauseRequested = true
        viewModelScope.launch {
            val result = engine.pauseTransfer()
            markTransferPaused(result.summary)
            appendLog(if (result.success) "INFO" else "ERROR", result.summary)
        }
    }

    fun deleteTransferRecord() {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                transferRecord = null,
                progress = null,
                estimatedCompletionTime = null,
                lastResult = "传输记录已删除；已下载的分片和用户文件未删除"
            )
        }
        persistTransferRecord()
    }

    private fun updateTransferItem(item: RsyncItem, dryRun: Boolean, role: SyncRole) {
        _state.update { state ->
            val display = if (dryRun) "${item.changeLabel} · ${item.folder}" else item.folder
            val alreadyShown = display in state.transferFolders
            val canAppend = !alreadyShown && state.transferFolders.size < MAX_VISIBLE_TRANSFER_FOLDERS
            state.copy(
                transferFolders = if (canAppend) state.transferFolders + display else state.transferFolders,
                currentTransferFolder = item.folder,
                transferItemCount = state.transferItemCount + 1,
                transferFoldersTruncated = state.transferFoldersTruncated || (!alreadyShown && !canAppend),
                transferPanelTitle = if (dryRun) "差异文件夹" else if (role == SyncRole.SEND_ONLY) {
                    "正在上传的文件夹"
                } else {
                    "正在接收的文件夹"
                }
            )
        }
    }

    private fun updateTransferProgress(
        progress: Float,
        startedAtMillis: Long,
        dryRun: Boolean,
        action: String
    ) {
        val normalized = progress.coerceIn(0f, 1f)
        val eta = if (dryRun) null else estimateCompletion(startedAtMillis, normalized)
        _state.update { state ->
            state.copy(
                progress = normalized,
                estimatedCompletionTime = eta,
                transferRecord = state.transferRecord?.let { record ->
                    if (dryRun) record else record.copy(
                        progress = normalized,
                        updatedAtMillis = System.currentTimeMillis(),
                        message = state.currentTransferFolder?.let { "正在处理 $it" } ?: "正在传输"
                    )
                }
            )
        }
        if (!dryRun) {
            val now = System.currentTimeMillis()
            if (now - lastTransferPersistMillis >= 1_000L || normalized >= 1f) {
                lastTransferPersistMillis = now
                persistTransferRecord()
                val state = _state.value
                updateTransferNotification(
                    action,
                    state.currentTransferFolder?.let { "正在处理：$it" } ?: "正在传输文件",
                    eta,
                    normalized
                )
            }
        }
    }

    private fun estimateCompletion(startedAtMillis: Long, progress: Float): String? {
        if (progress < 0.01f || progress >= 1f) return if (progress >= 1f) "已完成" else null
        val elapsedMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(1_000L)
        val remainingSeconds = ((elapsedMillis / 1000.0) * (1.0 - progress) / progress)
            .toLong().coerceAtLeast(1L)
        if (remainingSeconds > 30L * 24L * 60L * 60L) return null
        val finish = LocalDateTime.now().plusSeconds(remainingSeconds)
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        return "预计 $finish 完成（剩余 ${formatDuration(remainingSeconds)}）"
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}小时${seconds % 3600 / 60}分"
        seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }

    private fun updateTransferNotification(
        action: String,
        detail: String,
        eta: String?,
        progress: Float?
    ) = TransferForegroundService.update(
        context = getApplication(),
        title = "RootSync 正在$action",
        detail = detail,
        eta = eta,
        progress = progress
    )

    private fun markTransferPaused(message: String) {
        _state.update { state ->
            state.copy(
                isPreviewing = false,
                phase = "传输已暂停",
                lastResult = message,
                transferRecord = state.transferRecord?.copy(
                    status = TransferStatus.PAUSED,
                    updatedAtMillis = System.currentTimeMillis(),
                    message = message
                )
            )
        }
        persistTransferRecord()
        TransferForegroundService.stop(getApplication())
    }

    private suspend fun ensureRemoteServerReady(
        current: SyncUiState,
        configuredPort: Int
    ): RemoteEndpoint? {
        val reachableBeforePrepare = engine.isServerReachable(current.remoteHost, configuredPort)
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile == null || profile.deviceId.startsWith("manual:")) {
            if (reachableBeforePrepare) {
                appendLog("LAN", "远端 rsync 端口已就绪：${current.remoteHost}:$configuredPort")
                return RemoteEndpoint(current.remoteHost, configuredPort, current.remoteSecret)
            }
            val message =
                "无法连接远端 rsync 服务（错误 10）。请在远端服务页启动服务端，并核对 IP 与端口"
            appendLog("ERROR", message)
            _state.update { it.copy(lastResult = message, phase = "远端服务未启动") }
            return null
        }

        appendLog(
            "LAN",
            "正在请求 ${profile.name} 按本次${current.role.label}方向准备正确的发送/接收模块"
        )
        _state.update { it.copy(phase = "等待 ${profile.name} 准备服务端") }
        val prepared = discovery.requestSyncPreparation(
            host = current.remoteHost,
            role = current.role
        )
        if (prepared == null) {
            if (reachableBeforePrepare) {
                appendLog("WARN", "${profile.name} 未响应控制消息，继续使用其后台 rsync 服务")
                return RemoteEndpoint(current.remoteHost, configuredPort, current.remoteSecret)
            }
            val message = "${profile.name} 未响应服务准备请求；请保持远端 RootSync 打开"
            appendLog("ERROR", message)
            _state.update { it.copy(lastResult = message, phase = "远端无响应") }
            return null
        }
        if (!prepared.ready) {
            val message = "${profile.name} 无法启动服务端：${prepared.message}"
            appendLog("ERROR", message)
            _state.update { it.copy(lastResult = message, phase = "远端服务准备失败") }
            return null
        }

        val preparedPort = prepared.port.takeIf { it in 1024..65535 } ?: configuredPort
        val preparedSecret = prepared.secret.takeIf {
            it.length >= SyncUiState.MIN_SECRET_LENGTH
        } ?: current.remoteSecret
        _state.update { state ->
            state.copy(
                portText = preparedPort.toString(),
                remoteSecret = preparedSecret,
                profiles = state.profiles.map { item ->
                    if (item.id == profile.id) {
                        item.copy(port = preparedPort, secret = preparedSecret, host = prepared.host)
                    } else item
                }
            )
        }
        saveConfig()
        delay(300)
        if (!engine.isServerReachable(prepared.host, preparedPort, timeoutMillis = 2_500)) {
            val message = "${profile.name} 报告服务已启动，但端口 $preparedPort 仍无法连接"
            appendLog("ERROR", message)
            _state.update { it.copy(lastResult = message, phase = "远端端口不可达") }
            return null
        }
        appendLog("OK", "${profile.name} 已自动准备 rsync 服务")
        return RemoteEndpoint(prepared.host, preparedPort, preparedSecret)
    }

    fun cancel() {
        engine.cancel()
        _state.update {
            it.copy(
                isBusy = false,
                isPreviewing = false,
                phase = "任务已取消",
                lastResult = "任务已取消"
            )
        }
        appendLog("WARN", "用户取消了当前任务")
    }

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    private fun launchBusy(phase: String, block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, phase = phase) }
            try {
                block()
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                appendLog("ERROR", message)
                if (_state.value.transferRecord?.status == TransferStatus.RUNNING &&
                    !_state.value.isPreviewing
                ) {
                    markTransferPaused("$message；传输已自动暂停")
                } else {
                    _state.update { it.copy(lastResult = message, phase = "操作失败", isPreviewing = false) }
                }
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }

    private fun streamLog(message: String) = appendLog("RSYNC", message)

    private fun appendLog(level: String, message: String) {
        if (message.isBlank()) return
        val entry = LogEntry(
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            level = level,
            message = message
        )
        _state.update { it.copy(logs = (it.logs + entry).takeLast(400)) }
    }

    private fun encodeProfiles(profiles: List<PeerProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id)
                put("deviceId", profile.deviceId)
                put("name", profile.name)
                put("host", profile.host)
                put("port", profile.port)
                put("secret", profile.secret)
                put("role", profile.role.name)
                put("sourcePath", profile.sourcePath)
                put("destinationPath", profile.destinationPath)
            })
        }
    }.toString()

    private fun decodeProfiles(raw: String?): List<PeerProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val role = runCatching { SyncRole.valueOf(item.getString("role")) }
                        .getOrDefault(SyncRole.RECEIVE_ONLY)
                    add(
                        PeerProfile(
                            id = item.getString("id"),
                            deviceId = item.optString("deviceId", "manual:${item.getString("host")}"),
                            name = item.optString("name", item.getString("host")),
                            host = item.getString("host"),
                            port = item.optInt("port", SyncUiState.DEFAULT_RSYNC_PORT),
                            secret = item.optString("secret"),
                            role = role,
                            sourcePath = migrateBiliPath(
                                item.optString("sourcePath", SyncUiState.DEFAULT_BILI_PATH)
                            ),
                            destinationPath = migrateBiliPath(
                                item.optString("destinationPath", SyncUiState.DEFAULT_BILI_PATH)
                            )
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun encodeTransferRecord(record: TransferRecord): String = JSONObject().apply {
        put("id", record.id)
        put("profileId", record.profileId)
        put("deviceId", record.deviceId)
        put("peerName", record.peerName)
        put("host", record.host)
        put("port", record.port)
        put("secret", record.secret)
        put("role", record.role.name)
        put("sourcePath", record.sourcePath)
        put("destinationPath", record.destinationPath)
        put("status", record.status.name)
        put("progress", record.progress.toDouble())
        put("createdAtMillis", record.createdAtMillis)
        put("updatedAtMillis", record.updatedAtMillis)
        put("message", record.message)
    }.toString()

    private fun decodeTransferRecord(raw: String?): TransferRecord? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val item = JSONObject(raw)
            TransferRecord(
                id = item.getString("id"),
                profileId = item.optString("profileId").takeIf { it.isNotBlank() && it != "null" },
                deviceId = item.optString("deviceId").takeIf { it.isNotBlank() && it != "null" },
                peerName = item.optString("peerName", "远端设备"),
                host = item.getString("host"),
                port = item.optInt("port", SyncUiState.DEFAULT_RSYNC_PORT),
                secret = item.optString("secret"),
                role = runCatching { SyncRole.valueOf(item.getString("role")) }
                    .getOrDefault(SyncRole.RECEIVE_ONLY),
                sourcePath = item.optString("sourcePath", SyncUiState.DEFAULT_BILI_PATH),
                destinationPath = item.optString("destinationPath", SyncUiState.DEFAULT_BILI_PATH),
                status = runCatching { TransferStatus.valueOf(item.getString("status")) }
                    .getOrDefault(TransferStatus.PAUSED),
                progress = item.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
                updatedAtMillis = item.optLong("updatedAtMillis", System.currentTimeMillis()),
                message = item.optString("message")
            )
        }.getOrNull()
    }

    private fun migrateBiliPath(path: String): String =
        if (path == SyncUiState.LEGACY_BILI_PATH) SyncUiState.DEFAULT_BILI_PATH else path

    private fun resolveDeviceName(application: Application): String {
        val globalName = runCatching {
            Settings.Global.getString(application.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim().orEmpty()
        val secureName = runCatching {
            Settings.Secure.getString(application.contentResolver, "bluetooth_name")
        }.getOrNull()?.trim().orEmpty()
        return globalName.ifBlank { secureName }.ifBlank { Build.MODEL }.ifBlank { "Android 设备" }
    }

    private companion object {
        const val MAX_VISIBLE_TRANSFER_FOLDERS = 400
    }

    private data class RemoteEndpoint(val host: String, val port: Int, val secret: String)
}

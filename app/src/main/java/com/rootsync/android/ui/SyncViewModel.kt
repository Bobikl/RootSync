package com.rootsync.android.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rootsync.android.BuildConfig
import com.rootsync.android.diagnostics.DiagnosticLogger
import com.rootsync.android.discovery.LanDiscoveryManager
import com.rootsync.android.domain.DirectoryCreationPrompt
import com.rootsync.android.domain.LogEntry
import com.rootsync.android.domain.PairAccepted
import com.rootsync.android.domain.PairRequest
import com.rootsync.android.domain.PeerProfile
import com.rootsync.android.domain.RemoteSyncActivity
import com.rootsync.android.domain.RemoteStorageCheckRequest
import com.rootsync.android.domain.StrategyUpdate
import com.rootsync.android.domain.SyncActivityType
import com.rootsync.android.domain.SyncActivityUpdate
import com.rootsync.android.domain.SyncPrepareRequest
import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.SyncUiState
import com.rootsync.android.domain.TransferRecord
import com.rootsync.android.domain.TransferStatus
import com.rootsync.android.domain.TrustedPeerUpdate
import com.rootsync.android.engine.DestinationDirectoryState
import com.rootsync.android.engine.RootSyncEngine
import com.rootsync.android.engine.EngineResult
import com.rootsync.android.engine.RsyncItem
import com.rootsync.android.engine.SafeInput
import com.rootsync.android.service.TransferForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = RootSyncEngine(application)
    private val preferences = application.getSharedPreferences("rootsync", 0)
    private val diagnosticLogger = DiagnosticLogger(application)
    private val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit { putString("deviceId", it) }
    }
    private val deviceName = resolveDeviceName(application)
    private val _state = MutableStateFlow(loadState())
    private val controlToken = preferences.getString("controlToken", null)
        ?.takeIf { it.length >= MIN_CONTROL_TOKEN_LENGTH }
        ?: legacyControlToken(_state.value.serverSecret).also { token ->
            preferences.edit { putString("controlToken", token) }
        }
    val state: StateFlow<SyncUiState> = _state.asStateFlow()
    @Volatile private var pauseRequested = false
    @Volatile private var lastTransferPersistMillis = 0L
    private val transferSpeedSamples = ArrayDeque<TransferSpeedSample>()
    private var localTransferItemCount = 0
    private var lastTransferItemUiMillis = 0L
    private var pendingUploadedBytes = 0L
    private var pendingDownloadedBytes = 0L
    private var lastTransferBytesUiMillis = 0L
    private var previewUploadBytes = 0L
    private var previewDownloadBytes = 0L
    private var remoteSessionDeviceId: String? = null
    private var remoteSessionStartedAtMillis = 0L
    private var remoteSessionOwnsServer = false
    private var remoteSessionStopping = false
    private var nsdPortRefreshJob: Job? = null
    @Volatile private var pendingDirectoryDecision: CompletableDeferred<Boolean>? = null
    private val operationMutex = Mutex()
    private val remotePrepareMutex = Mutex()
    private val discovery = LanDiscoveryManager(
        context = application,
        deviceId = deviceId,
        deviceName = deviceName,
        localPort = { SafeInput.parsePort(_state.value.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT },
        localSecret = { _state.value.serverSecret },
        localControlToken = { controlToken },
        trustedControlToken = { remoteId ->
            _state.value.profiles.firstOrNull {
                it.deviceId == remoteId && !it.deviceId.startsWith("manual:")
            }?.controlToken?.takeIf { it.length >= MIN_CONTROL_TOKEN_LENGTH }
        },
        onLog = { message -> appendLog("LAN", message) }
    )

    init {
        diagnosticLogger.append(
            "SESSION",
            "应用启动 version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "android=${Build.VERSION.RELEASE}/${Build.VERSION.SDK_INT} model=${Build.MANUFACTURER} ${Build.MODEL}"
        )
        diagnosticLogger.append("STATE", diagnosticStateHeader())
        if (preferences.contains(TransferForegroundService.PREF_PENDING_ACTION_MESSAGE)) {
            preferences.edit { remove(TransferForegroundService.PREF_PENDING_ACTION_MESSAGE) }
        }
        viewModelScope.launch {
            val cleanup = engine.cleanupStaleRuntimeProcesses(::streamLog)
            appendLog(if (cleanup.success) "INFO" else "ERROR", cleanup.summary)
        }
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
        }
        // 新安装首次生成的密钥也立即落盘，避免进程在能力检查前退出后重新生成。
        saveConfig()
        discovery.start()
        viewModelScope.launch {
            discovery.devices.collect { devices ->
                _state.update {
                    it.copy(
                        discoveredDevices = devices,
                        onlineDeviceIds = devices.mapTo(linkedSetOf()) { device -> device.deviceId }
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshProfilePresence()
                delay(PROFILE_PRESENCE_INTERVAL_MS)
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
            discovery.syncPrepareRequests.collect { request -> dispatchSyncPrepareRequest(request) }
        }
        viewModelScope.launch {
            discovery.remoteStorageCheckRequests.collect { request -> handleRemoteStorageCheckRequest(request) }
        }
        viewModelScope.launch {
            discovery.syncActivityUpdates.collect { update -> applySyncActivity(update) }
        }
        viewModelScope.launch {
            discovery.trustedPeerUpdates.collect { update -> applyTrustedPeerUpdate(update) }
        }
        refreshCapabilities()
    }

    private fun loadState(): SyncUiState {
        val profilesRaw = preferences.getString("profiles", null)
        val decodedProfiles = decodeProfiles(profilesRaw)
        val profiles = if (decodedProfiles.isNotEmpty() || profilesRaw.isNullOrBlank() ||
            profilesRaw.trim() == "[]"
        ) {
            decodedProfiles
        } else {
            decodeProfiles(preferences.getString("profilesBackup", null)).also { restored ->
                if (restored.isNotEmpty()) {
                    diagnosticLogger.append("WARN", "主设备配置无法恢复，已使用上一份配置备份")
                }
            }
        }
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
        val fallbackRangeMode = runCatching {
            SyncRangeMode.valueOf(preferences.getString("rangeMode", SyncRangeMode.ALL.name)!!)
        }.getOrDefault(SyncRangeMode.ALL)
        val fallbackSince = preferences.getLong("sinceEpochMillis", -1L).takeIf { it > 0L }
        val resolvedRole = selected?.role ?: fallbackRole
        val resolvedSource = selected?.sourcePath ?: storedSource
        val resolvedDestination = selected?.destinationPath ?: storedDestination
        val legacyRecord = decodeTransferRecord(preferences.getString("transferRecord", null))
        val storedRecords = decodeTransferRecords(preferences.getString("transferRecords", null))
        val transferRecords = legacyRecord?.let { mergeTransferRecord(storedRecords, it) } ?: storedRecords
        val selectedRecord = if (selected != null) {
            transferRecords.filter { recordMatchesProfile(it, selected) }.maxByOrNull { it.updatedAtMillis }
        } else legacyRecord
        val pendingActionMessage = preferences.getString(
            TransferForegroundService.PREF_PENDING_ACTION_MESSAGE,
            null
        )
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
            sourcePath = resolvedSource,
            destinationPath = if (resolvedRole == SyncRole.BIDIRECTIONAL) resolvedSource else resolvedDestination,
            serverSecret = preferences.getString("serverSecret", null)
                ?: preferences.getString("secret", null)
                ?: engine.generateSecret(),
            remoteSecret = selected?.secret ?: preferences.getString("remoteSecret", "").orEmpty(),
            role = resolvedRole,
            rangeMode = selected?.rangeMode ?: fallbackRangeMode,
            strictContentCheck = preferences.getBoolean("strictContentCheck", false),
            sinceEpochMillis = if (selected != null) selected.sinceEpochMillis else fallbackSince,
            profiles = profiles,
            selectedProfileId = selected?.id,
            hasUnsavedProfileChanges = selected == null &&
                preferences.getString("remoteHost", "").orEmpty().isNotBlank(),
            transferRecord = selectedRecord,
            transferRecords = transferRecords,
            localIp = engine.localIpv4(),
            phase = if (pendingActionMessage.isNullOrBlank()) "等待检查" else "需要用户操作",
            lastResult = pendingActionMessage
        )
    }

    fun refreshCapabilities() {
        launchBusy("正在请求 ROOT 并检查能力") {
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
                val updated = current.copy(
                    isChecking = false,
                    capabilities = capabilities,
                    sourcePath = if (detected != null && canReplaceSource) detected else current.sourcePath,
                    destinationPath = if (detected != null && canReplaceDestination) detected else current.destinationPath,
                    localIp = engine.localIpv4(),
                    phase = if (capabilities.rootGranted) "能力检查完成" else "ROOT 不可用"
                )
                updated.copy(
                    hasUnsavedProfileChanges = current.hasUnsavedProfileChanges ||
                        (current.selectedProfileId != null &&
                            (updated.sourcePath != current.sourcePath ||
                                updated.destinationPath != current.destinationPath))
                )
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
    fun setServerPort(value: String) {
        val normalized = value.filter(Char::isDigit).take(5)
        updateConfig(profileDraft = false) { copy(serverPortText = normalized, previewReady = false) }
        nsdPortRefreshJob?.cancel()
        nsdPortRefreshJob = viewModelScope.launch {
            delay(NSD_PORT_DEBOUNCE_MS)
            if (_state.value.serverPortText == normalized && SafeInput.parsePort(normalized) != null) {
                discovery.refreshNsdRegistration()
                appendLog("LAN", "NSD 已更新本机服务端口为 $normalized")
            }
        }
    }
    fun setSourcePath(value: String) = updateConfig {
        copy(
            sourcePath = value,
            destinationPath = if (role == SyncRole.BIDIRECTIONAL) value else destinationPath,
            previewReady = false
        )
    }
    fun setDestinationPath(value: String) = updateConfig { copy(destinationPath = value, previewReady = false) }
    fun setBidirectionalPath(value: String) = updateConfig {
        copy(sourcePath = value, destinationPath = value, previewReady = false)
    }
    fun setRemoteSecret(value: String) = updateConfig {
        copy(remoteSecret = value.trim().take(128), previewReady = false)
    }
    fun setServerSecret(value: String) = updateConfig(profileDraft = false) {
        copy(
            serverSecret = value.filterNot { it.isISOControl() }.trim().take(64),
            previewReady = false
        )
    }
    fun setRole(value: SyncRole) {
        updateConfig {
            copy(
                role = value,
                destinationPath = if (value == SyncRole.BIDIRECTIONAL) sourcePath else destinationPath,
                previewReady = false
            )
        }
    }
    fun setRangeMode(value: SyncRangeMode) {
        updateConfig {
            copy(
                rangeMode = value,
                sinceEpochMillis = if (value == SyncRangeMode.SINCE) {
                    sinceEpochMillis ?: System.currentTimeMillis() - DEFAULT_RANGE_MILLIS
                } else sinceEpochMillis,
                previewReady = false
            )
        }
    }
    fun setSinceEpochMillis(value: Long) {
        updateConfig { copy(sinceEpochMillis = value.coerceAtLeast(1L), previewReady = false) }
    }

    fun setStrictContentCheck(enabled: Boolean) = updateConfig(profileDraft = false) {
        copy(strictContentCheck = enabled, previewReady = false)
    }

    private fun updateConfig(
        profileDraft: Boolean = true,
        block: SyncUiState.() -> SyncUiState
    ) {
        _state.update {
            it.block().let { updated ->
                if (profileDraft) updated.copy(hasUnsavedProfileChanges = true) else updated
            }
        }
        saveConfig()
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
                    rangeMode = current.rangeMode,
                    sinceEpochMillis = current.sinceEpochMillis,
                    sourcePath = current.sourcePath,
                    destinationPath = current.destinationPath
                )
                _state.update {
                    it.copy(
                        profiles = it.profiles.filterNot { item -> item.id == id } + profile,
                        selectedProfileId = id,
                        profileName = profile.name,
                        hasUnsavedProfileChanges = false,
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
            val profileRecord = it.transferRecords
                .filter { record -> recordMatchesProfile(record, profile) }
                .maxByOrNull { record -> record.updatedAtMillis }
            it.copy(
                selectedProfileId = profile.id,
                hasUnsavedProfileChanges = false,
                profileName = profile.name,
                remoteHost = profile.host,
                portText = profile.port.toString(),
                remoteSecret = profile.secret,
                role = profile.role,
                rangeMode = profile.rangeMode,
                sinceEpochMillis = profile.sinceEpochMillis,
                sourcePath = profile.sourcePath,
                destinationPath = if (profile.role == SyncRole.BIDIRECTIONAL) {
                    profile.sourcePath
                } else profile.destinationPath,
                transferRecord = profileRecord,
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
                hasUnsavedProfileChanges = false,
                profileName = "新设备",
                remoteHost = "",
                remoteSecret = "",
                role = SyncRole.RECEIVE_ONLY,
                rangeMode = SyncRangeMode.ALL,
                sinceEpochMillis = null,
                transferRecord = null,
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
                hasUnsavedProfileChanges = false,
                profileName = "新设备",
                remoteHost = "",
                remoteSecret = "",
                previewReady = false,
                transferRecord = if (removesTransfer) null else it.transferRecord,
                transferRecords = it.transferRecords.filterNot { record ->
                    record.profileId == removed.id || record.deviceId == removed.deviceId
                },
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
        discovery.requestPair(
            device = device,
            role = current.role,
            rangeMode = current.rangeMode,
            sinceEpochMillis = current.sinceEpochMillis
        )
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
        controlToken = controlToken,
        role = role.opposite(),
        rangeMode = rangeMode,
        sinceEpochMillis = sinceEpochMillis
    )

    private fun upsertPairedDevice(device: PairAccepted) {
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == device.deviceId }
        val profile = existing?.copy(
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
            controlToken = device.controlToken,
            role = device.role,
            rangeMode = device.rangeMode,
            sinceEpochMillis = device.sinceEpochMillis,
            destinationPath = if (device.role == SyncRole.BIDIRECTIONAL) {
                existing.sourcePath
            } else existing.destinationPath
        ) ?: PeerProfile(
            id = UUID.randomUUID().toString(),
            deviceId = device.deviceId,
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
            controlToken = device.controlToken,
            role = device.role,
            rangeMode = device.rangeMode,
            sinceEpochMillis = device.sinceEpochMillis,
            sourcePath = current.sourcePath,
            destinationPath = if (device.role == SyncRole.BIDIRECTIONAL) {
                current.sourcePath
            } else current.destinationPath
        )
        _state.update {
            val profileRecord = it.transferRecords
                .filter { record -> recordMatchesProfile(record, profile) }
                .maxByOrNull { record -> record.updatedAtMillis }
            it.copy(
                profiles = it.profiles.filterNot { item -> item.deviceId == device.deviceId } + profile,
                selectedProfileId = profile.id,
                profileName = profile.name,
                remoteHost = profile.host,
                portText = profile.port.toString(),
                remoteSecret = profile.secret,
                role = profile.role,
                rangeMode = profile.rangeMode,
                sinceEpochMillis = profile.sinceEpochMillis,
                sourcePath = profile.sourcePath,
                destinationPath = profile.destinationPath,
                transferRecord = profileRecord,
                previewReady = false,
                hasUnsavedProfileChanges = false
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
        discovery.sendStrategy(
            host = profile.host,
            role = current.role,
            rangeMode = current.rangeMode,
            sinceEpochMillis = current.sinceEpochMillis
        )
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
            role = localRole,
            rangeMode = update.rangeMode,
            sinceEpochMillis = update.sinceEpochMillis,
            destinationPath = if (localRole == SyncRole.BIDIRECTIONAL) {
                existing.sourcePath
            } else existing.destinationPath
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
                rangeMode = if (selected) updatedProfile.rangeMode else state.rangeMode,
                sinceEpochMillis = if (selected) updatedProfile.sinceEpochMillis else state.sinceEpochMillis,
                destinationPath = if (selected && localRole == SyncRole.BIDIRECTIONAL) {
                    updatedProfile.sourcePath
                } else state.destinationPath,
                previewReady = if (selected) false else state.previewReady,
                hasUnsavedProfileChanges = if (selected) false else state.hasUnsavedProfileChanges,
                lastResult = "${update.name} 已设为${update.role.label}；本机自动切换为${localRole.label}"
            )
        }
        saveConfig()
        appendLog(
            "LAN",
            "零删除策略已同步：${update.name} ${update.role.label} / ${update.rangeMode.label}，本机 ${localRole.label}"
        )
    }

    private suspend fun handleSyncPrepareRequest(request: SyncPrepareRequest) {
        appendLog(
            "DIAG",
            "收到服务准备请求 device=${request.name}/${request.deviceId} host=${request.host} " +
                "role=${request.role.name} range=${request.rangeMode.name} preview=${request.isPreview}"
        )
        val initial = _state.value
        val profile = initial.profiles.firstOrNull { it.deviceId == request.deviceId }
        val port = SafeInput.parsePort(initial.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT
        if (profile == null) {
            appendLog("WARN", "已拒绝 ${request.name} 的未认证同步准备请求")
            discovery.answerSyncPreparation(request, false, "设备未配对或已主动删除", port)
            return
        }
        if (initial.selectedProfileId == profile.id && initial.hasUnsavedProfileChanges) {
            val message = "本机正在编辑该设备策略，请先保存或切换设备后重试"
            appendLog("WARN", "已拒绝 ${request.name} 的同步准备请求：$message")
            discovery.answerSyncPreparation(request, false, message, port)
            return
        }
        if (profile.secret != request.secret) {
            appendLog("LAN", "${request.name} 的密钥发生变化，按已保存设备 ID 自动恢复信任")
        }
        if (initial.isBusy || operationMutex.isLocked || remoteSessionDeviceId != null) {
            discovery.answerSyncPreparation(request, false, "远端设备正在执行其他任务", port)
            return
        }
        remoteSessionDeviceId = request.deviceId
        remoteSessionStartedAtMillis = System.currentTimeMillis()

        applyRemoteStrategy(
            StrategyUpdate(
                request.deviceId,
                request.name,
                request.host,
                request.secret,
                request.role,
                request.rangeMode,
                request.sinceEpochMillis
            )
        )
        val localRole = request.role.opposite()
        val activeProfile = _state.value.profiles.first { it.deviceId == request.deviceId }
        val rsync = _state.value.capabilities.rsyncPath
        if (rsync == null) {
            releaseRemoteSession(request.deviceId, "远端 ROOT/rsync 能力尚未就绪")
            discovery.answerSyncPreparation(request, false, "远端 ROOT/rsync 能力尚未就绪", port)
            return
        }

        if (localRole != SyncRole.SEND_ONLY) {
            val destinationCheck = engine.inspectDestinationDirectory(activeProfile.destinationPath)
            when (destinationCheck.state) {
                DestinationDirectoryState.READY -> Unit
                DestinationDirectoryState.MISSING -> {
                    val allowed = awaitDirectoryCreationDecision(request, activeProfile.destinationPath)
                    if (allowed != true) {
                        val reason = if (allowed == false) {
                            "接收端已拒绝创建接收目录"
                        } else {
                            "等待接收端确认创建目录超时"
                        }
                        releaseRemoteSession(request.deviceId, reason)
                        _state.update {
                            it.copy(
                                isBusy = false,
                                pendingDirectoryCreation = null,
                                phase = reason,
                                lastResult = reason
                            )
                        }
                        discovery.answerSyncPreparation(request, false, reason, port)
                        return
                    }
                    val createResult = engine.createDestinationDirectory(activeProfile.destinationPath)
                    appendLog(if (createResult.success) "OK" else "ERROR", createResult.summary)
                    if (!createResult.success) {
                        releaseRemoteSession(request.deviceId, createResult.summary)
                        _state.update {
                            it.copy(isBusy = false, phase = "接收目录创建失败", lastResult = createResult.summary)
                        }
                        discovery.answerSyncPreparation(request, false, createResult.summary, port)
                        return
                    }
                }
                DestinationDirectoryState.UNWRITABLE,
                DestinationDirectoryState.INVALID -> {
                    releaseRemoteSession(request.deviceId, destinationCheck.message)
                    _state.update {
                        it.copy(isBusy = false, phase = "接收目录不可用", lastResult = destinationCheck.message)
                    }
                    discovery.answerSyncPreparation(request, false, destinationCheck.message, port)
                    return
                }
            }
        }

        _state.update { it.copy(isBusy = true, phase = "正在为 ${request.name} 准备服务端") }
        val sessionSecret = engine.generateSecret()
        val result = try {
            engine.startServer(
                rsyncPath = rsync,
                sourcePath = activeProfile.sourcePath,
                destinationPath = activeProfile.destinationPath,
                port = port,
                secret = sessionSecret,
                onLog = ::streamLog,
                mode = localRole,
                rangeMode = request.rangeMode,
                sinceEpochMillis = request.sinceEpochMillis,
                untilEpochMillis = request.untilEpochMillis,
                allowCreateDestination = false
            )
        } catch (error: Exception) {
            com.rootsync.android.engine.EngineResult(
                false,
                error.message ?: error::class.java.simpleName
            )
        }
        _state.update {
            it.copy(
                serverRunning = result.success,
                isBusy = result.success,
                phase = result.summary,
                lastResult = result.summary
            )
        }
        appendLog(if (result.success) "OK" else "ERROR", "${request.name}：${result.summary}")
        if (result.success) {
            val foregroundReady = TransferForegroundService.update(
                context = getApplication(),
                title = if (request.isPreview) "RootSync 正在响应远端差异预览" else "RootSync 正在等待远端传输",
                detail = "${request.name} 已连接，正在保持接收端后台运行",
                eta = null,
                progress = null,
                incoming = true
            )
            if (!foregroundReady) {
                val stopResult = engine.stopServer(::streamLog)
                val message = "系统不允许从后台启动接收服务；请打开本机 RootSync 后由对方重试"
                remoteSessionOwnsServer = false
                releaseRemoteSession(request.deviceId, message)
                _state.update {
                    it.copy(
                        serverRunning = false,
                        isBusy = false,
                        phase = "等待用户打开应用",
                        lastResult = if (stopResult.success) message else "$message；${stopResult.summary}"
                    )
                }
                discovery.answerSyncPreparation(request, false, message, port)
                appendLog("WARN", message)
                return
            }
        }
        if (!result.success) clearRemoteActivity(request.deviceId)
        else {
            remoteSessionOwnsServer = true
            scheduleRemoteSessionStartTimeout(request.deviceId, remoteSessionStartedAtMillis)
        }
        discovery.answerSyncPreparation(
            request = request,
            ready = result.success,
            messageText = result.summary,
            port = port,
            sessionSecret = sessionSecret.takeIf { result.success }
        )
    }

    private suspend fun handleRemoteStorageCheckRequest(request: RemoteStorageCheckRequest) {
        val profile = _state.value.profiles.firstOrNull { it.deviceId == request.deviceId }
        val sessionMatches = remoteSessionDeviceId == request.deviceId && !remoteSessionStopping
        if (profile == null || !sessionMatches) {
            val message = "本机没有为该设备准备中的接收会话"
            discovery.answerRemoteStorageCheck(request, false, message, 0L)
            appendLog("WARN", "已拒绝 ${request.name} 的无会话空间检查")
            return
        }
        val availableBytes = engine.availableStorageBytes(profile.destinationPath)
        if (availableBytes == null) {
            val message = "本机无法读取接收目录剩余空间"
            discovery.answerRemoteStorageCheck(request, false, message, 0L)
            appendLog("ERROR", "$message：${profile.destinationPath}")
            return
        }
        val requiredBytes = requiredStorageBytes(request.expectedDownloadBytes)
        val ready = availableBytes >= requiredBytes
        val message = if (ready) {
            "接收空间检查通过：需要 ${formatBytes(requiredBytes)}，可用 ${formatBytes(availableBytes)}"
        } else {
            "接收空间不足：预计接收 ${formatBytes(request.expectedDownloadBytes)}，" +
                "至少需要 ${formatBytes(requiredBytes)}，当前可用 ${formatBytes(availableBytes)}"
        }
        discovery.answerRemoteStorageCheck(request, ready, message, availableBytes)
        appendLog(if (ready) "OK" else "ERROR", "${request.name}：$message")
        _state.update { state -> state.copy(phase = message, lastResult = message) }
    }

    private fun dispatchSyncPrepareRequest(request: SyncPrepareRequest) {
        if (!remotePrepareMutex.tryLock()) {
            val port = SafeInput.parsePort(_state.value.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT
            discovery.answerSyncPreparation(request, false, "本机正在处理另一台设备的连接请求", port)
            appendLog("LAN", "已向 ${request.name} 返回忙碌状态，未排队等待其他设备的决定")
            return
        }
        viewModelScope.launch {
            try {
                handleSyncPrepareRequest(request)
            } finally {
                remotePrepareMutex.unlock()
            }
        }
    }

    private suspend fun awaitDirectoryCreationDecision(
        request: SyncPrepareRequest,
        path: String
    ): Boolean? {
        val decision = CompletableDeferred<Boolean>()
        pendingDirectoryDecision = decision
        val prompt = DirectoryCreationPrompt(
            deviceId = request.deviceId,
            deviceName = request.name,
            path = path,
            isPreview = request.isPreview
        )
        _state.update {
            it.copy(
                isBusy = true,
                pendingDirectoryCreation = prompt,
                phase = "等待选择是否创建接收目录",
                lastResult = "${request.name} 请求向不存在的目录发送数据"
            )
        }
        discovery.answerSyncPreparationWaiting(request, "接收目录不存在，正在等待用户选择是否创建")
        TransferForegroundService.update(
            context = getApplication(),
            title = "RootSync 需要确认接收目录",
            detail = "${request.name} 请求创建：$path",
            eta = null,
            progress = null,
            incoming = true
        )
        appendLog("LAN", "${request.name} 等待本机确认创建接收目录：$path")
        val answer = withTimeoutOrNull(DIRECTORY_DECISION_TIMEOUT_MS) { decision.await() }
        if (pendingDirectoryDecision === decision) pendingDirectoryDecision = null
        _state.update { state ->
            if (state.pendingDirectoryCreation == prompt) {
                state.copy(pendingDirectoryCreation = null)
            } else state
        }
        TransferForegroundService.stop(getApplication())
        return answer
    }

    private suspend fun ensureLocalDestinationReady(
        requesterName: String,
        path: String,
        isPreview: Boolean
    ): Boolean {
        val check = engine.inspectDestinationDirectory(path)
        when (check.state) {
            DestinationDirectoryState.READY -> return true
            DestinationDirectoryState.UNWRITABLE,
            DestinationDirectoryState.INVALID -> {
                appendLog("ERROR", check.message)
                _state.update { it.copy(phase = "接收目录不可用", lastResult = check.message) }
                return false
            }
            DestinationDirectoryState.MISSING -> Unit
        }

        val decision = CompletableDeferred<Boolean>()
        pendingDirectoryDecision = decision
        val prompt = DirectoryCreationPrompt(
            deviceId = _state.value.selectedPairedDeviceId ?: "local",
            deviceName = requesterName,
            path = path,
            isPreview = isPreview
        )
        _state.update {
            it.copy(
                pendingDirectoryCreation = prompt,
                phase = "等待确认创建本机接收目录",
                lastResult = "本机接收目录不存在，确认后才会创建"
            )
        }
        val allowed = withTimeoutOrNull(DIRECTORY_DECISION_TIMEOUT_MS) { decision.await() }
        if (pendingDirectoryDecision === decision) pendingDirectoryDecision = null
        _state.update { state ->
            if (state.pendingDirectoryCreation == prompt) state.copy(pendingDirectoryCreation = null) else state
        }
        if (allowed != true) {
            val message = if (allowed == false) "用户拒绝创建本机接收目录" else "确认创建本机接收目录超时"
            appendLog("WARN", message)
            _state.update { it.copy(phase = "未创建接收目录", lastResult = message) }
            return false
        }
        val createResult = engine.createDestinationDirectory(path)
        appendLog(if (createResult.success) "OK" else "ERROR", createResult.summary)
        _state.update { it.copy(lastResult = createResult.summary) }
        return createResult.success
    }

    fun answerDirectoryCreation(allow: Boolean) {
        val prompt = _state.value.pendingDirectoryCreation ?: return
        val completed = pendingDirectoryDecision?.complete(allow) == true
        if (!completed) return
        _state.update {
            it.copy(
                pendingDirectoryCreation = null,
                phase = if (allow) "正在创建接收目录" else "已拒绝创建接收目录",
                lastResult = if (allow) {
                    "已允许为 ${prompt.deviceName} 创建接收目录"
                } else {
                    "已拒绝 ${prompt.deviceName} 的接收目录创建请求"
                }
            )
        }
    }

    private fun applySyncActivity(update: SyncActivityUpdate) {
        val profile = _state.value.profiles.firstOrNull { it.deviceId == update.deviceId } ?: return
        if (remoteSessionDeviceId != update.deviceId || remoteSessionStopping || operationMutex.isLocked) {
            if (update.active) {
                appendLog("WARN", "已忽略未匹配当前准备会话的远端任务消息：${update.name}")
            }
            return
        }
        val currentActivity = _state.value.remoteActivity
        if (update.active && currentActivity?.deviceId == update.deviceId &&
            currentActivity.type == update.type && currentActivity.taskId == update.taskId &&
            currentActivity.finished
        ) return
        if (update.active && update.itemPath != null) {
            showRemoteActivity(
                update.deviceId,
                update.name.ifBlank { profile.name },
                update.type,
                update.taskId
            )
            updateRemoteTransferItem(update.type, update.itemPath, update.itemIndex)
            if (update.type == SyncActivityType.TRANSFER) {
                updateIncomingTransferNotification(update.name.ifBlank { profile.name }, update.itemPath)
            }
            return
        }
        if (update.active) {
            showRemoteActivity(
                update.deviceId,
                update.name.ifBlank { profile.name },
                update.type,
                update.taskId
            )
            appendLog(
                "LAN",
                if (update.type == SyncActivityType.PREVIEW) {
                    "${update.name} 正在扫描差异文件夹"
                } else {
                    "${update.name} 正在执行同步"
                }
            )
            if (update.type == SyncActivityType.TRANSFER) {
                updateIncomingTransferNotification(update.name.ifBlank { profile.name }, null)
            }
        } else {
            finishRemoteActivity(update.deviceId, update.type, update.taskId, update.totalItems)
        }
    }

    private fun showRemoteActivity(
        deviceId: String,
        name: String,
        type: SyncActivityType,
        taskId: String = ""
    ) {
        val current = _state.value.remoteActivity
        if (current?.deviceId == deviceId && current.type == type && !current.finished &&
            (taskId.isBlank() || current.taskId == taskId)
        ) {
            _state.update { state ->
                if (state.remoteActivity?.startedAtMillis == current.startedAtMillis) {
                    state.copy(remoteActivity = current.copy(lastSeenAtMillis = System.currentTimeMillis()))
                } else state
            }
            return
        }
        val activity = RemoteSyncActivity(deviceId = deviceId, name = name, type = type, taskId = taskId)
        remoteSessionDeviceId = deviceId
        remoteSessionStartedAtMillis = activity.startedAtMillis
        _state.update {
            it.copy(
                remoteActivity = activity,
                isBusy = true,
                transferPanelTitle = if (type == SyncActivityType.PREVIEW) {
                    "对方差异扫描文件与目录"
                } else {
                    "正在双向同步的文件与目录"
                },
                transferFolders = emptyList(),
                currentTransferFolder = null,
                transferItemCount = 0,
                transferFoldersTruncated = false
            )
        }
        viewModelScope.launch {
            while (true) {
                val currentActivity = _state.value.remoteActivity
                if (currentActivity?.startedAtMillis != activity.startedAtMillis || currentActivity.finished) return@launch
                val idleMillis = System.currentTimeMillis() - currentActivity.lastSeenAtMillis
                val remaining = REMOTE_ACTIVITY_TIMEOUT_MS - idleMillis
                if (remaining > 0L) {
                    delay(remaining)
                    continue
                }
                var expired = false
                _state.update { state ->
                    if (state.remoteActivity?.startedAtMillis == activity.startedAtMillis) {
                        expired = true
                        state.copy(remoteActivity = null, isBusy = operationMutex.isLocked)
                    } else state
                }
                if (expired) releaseRemoteSession(deviceId, "远端任务长时间无心跳，已释放会话")
                return@launch
            }
        }
    }

    private fun clearRemoteActivity(deviceId: String) {
        releaseRemoteSession(deviceId, "远端任务已清理")
        _state.update { state ->
            if (state.remoteActivity?.deviceId == deviceId) {
                state.copy(remoteActivity = null, isBusy = operationMutex.isLocked)
            } else state
        }
    }

    private fun finishRemoteActivity(
        deviceId: String,
        type: SyncActivityType,
        taskId: String,
        totalItems: Int
    ) {
        val finishedAt = System.currentTimeMillis()
        var matched = false
        _state.update { state ->
            val activity = state.remoteActivity
            if (activity?.deviceId == deviceId && activity.type == type && !activity.finished &&
                (taskId.isBlank() || activity.taskId == taskId)
            ) {
                matched = true
                state.copy(
                    remoteActivity = activity.copy(finished = true, startedAtMillis = finishedAt),
                    transferItemCount = maxOf(state.transferItemCount, totalItems),
                    isBusy = operationMutex.isLocked
                )
            } else state
        }
        if (!matched) return
        releaseRemoteSession(deviceId, "远端任务已结束")
        appendLog(
            "LAN",
            if (type == SyncActivityType.PREVIEW) "对方差异扫描已结束" else "对方同步任务已结束"
        )
        viewModelScope.launch {
            delay(REMOTE_ACTIVITY_FINISHED_HOLD_MS)
            _state.update { state ->
                if (state.remoteActivity?.startedAtMillis == finishedAt &&
                    state.remoteActivity.finished
                ) {
                    state.copy(remoteActivity = null)
                } else state
            }
        }
    }

    private fun updateRemoteTransferItem(type: SyncActivityType, itemPath: String, itemIndex: Int) {
        _state.update { state ->
            val display = if (type == SyncActivityType.PREVIEW) {
                "对方扫描 · $itemPath"
            } else {
                "对方正在传输 · $itemPath"
            }
            val canAppend = display !in state.transferFolders &&
                state.transferFolders.size < MAX_VISIBLE_TRANSFER_FOLDERS
            state.copy(
                transferPanelTitle = if (type == SyncActivityType.PREVIEW) {
                    "对方差异扫描文件与目录"
                } else {
                    "正在双向同步的文件与目录"
                },
                currentTransferFolder = itemPath,
                transferFolders = if (canAppend) state.transferFolders + display else state.transferFolders,
                transferItemCount = maxOf(state.transferItemCount, itemIndex),
                transferFoldersTruncated = state.transferFoldersTruncated ||
                    (display !in state.transferFolders && !canAppend)
            )
        }
    }

    private fun updateIncomingTransferNotification(peerName: String, itemPath: String?) {
        TransferForegroundService.update(
            context = getApplication(),
            title = "RootSync 正在接收 $peerName 的同步",
            detail = itemPath?.let { "正在接收：$it" } ?: "远端设备正在传输文件",
            eta = null,
            progress = null,
            incoming = true
        )
    }

    private fun releaseRemoteSession(deviceId: String, reason: String) {
        if (remoteSessionDeviceId != deviceId) return
        if (remoteSessionStopping) return
        val shouldStopSessionServer = remoteSessionOwnsServer
        remoteSessionStartedAtMillis = 0L
        remoteSessionOwnsServer = false
        TransferForegroundService.stop(getApplication())
        appendLog("LAN", reason)
        if (shouldStopSessionServer) {
            remoteSessionStopping = true
            viewModelScope.launch {
                val result = try {
                    engine.stopServer(::streamLog)
                } catch (error: Exception) {
                    EngineResult(false, error.message ?: error::class.java.simpleName)
                } finally {
                    if (remoteSessionDeviceId == deviceId) remoteSessionDeviceId = null
                    remoteSessionStopping = false
                }
                _state.update { state ->
                    state.copy(
                        serverRunning = false,
                        lastResult = if (result.success) state.lastResult else result.summary
                    )
                }
                appendLog(
                    if (result.success) "LAN" else "ERROR",
                    if (result.success) "远端会话结束，临时 rsync 服务和会话密钥已失效" else result.summary
                )
            }
        } else {
            remoteSessionDeviceId = null
        }
    }

    private fun scheduleRemoteSessionStartTimeout(deviceId: String, startedAtMillis: Long) {
        viewModelScope.launch {
            delay(REMOTE_SESSION_START_TIMEOUT_MS)
            if (remoteSessionDeviceId == deviceId &&
                remoteSessionStartedAtMillis == startedAtMillis &&
                _state.value.remoteActivity?.deviceId != deviceId
            ) {
                releaseRemoteSession(deviceId, "远端未在限定时间内开始任务，已释放服务会话")
                _state.update { it.copy(isBusy = false, phase = "远端任务未开始") }
            }
        }
    }

    private fun saveConfig() {
        val value = _state.value
        val encodedProfiles = encodeProfiles(value.profiles)
        val previousProfiles = preferences.getString("profiles", null)
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
            putString("rangeMode", value.rangeMode.name)
            putBoolean("strictContentCheck", value.strictContentCheck)
            value.sinceEpochMillis?.let { putLong("sinceEpochMillis", it) }
                ?: remove("sinceEpochMillis")
            if (!previousProfiles.isNullOrBlank() && previousProfiles != encodedProfiles) {
                putString("profilesBackup", previousProfiles)
            }
            putString("profiles", encodedProfiles)
            putString("selectedProfileId", value.selectedProfileId)
            value.transferRecord?.let { putString("transferRecord", encodeTransferRecord(it)) }
                ?: remove("transferRecord")
            putString("transferRecords", encodeTransferRecords(value.transferRecords))
        }
    }

    private fun persistTransferRecord() {
        val current = _state.value
        val records = current.transferRecord?.let {
            mergeTransferRecord(current.transferRecords, it)
        } ?: current.transferRecords
        _state.update { it.copy(transferRecords = records) }
        preferences.edit {
            current.transferRecord?.let { putString("transferRecord", encodeTransferRecord(it)) }
                ?: remove("transferRecord")
            putString("transferRecords", encodeTransferRecords(records))
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
                if (!ensureLocalDestinationReady("本机服务端", current.destinationPath, false)) {
                    return@launchBusy
                }
                val result = engine.startServer(
                    rsync,
                    current.sourcePath,
                    current.destinationPath,
                    port,
                    current.serverSecret,
                    ::streamLog,
                    rangeMode = current.rangeMode,
                    sinceEpochMillis = current.sinceEpochMillis,
                    untilEpochMillis = System.currentTimeMillis()
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

    fun preview() = runTransferAfterPresenceCheck(dryRun = true)

    fun execute() {
        if (_state.value.transferRecord?.status == TransferStatus.PAUSED) resumeTransfer()
        else runTransferAfterPresenceCheck(dryRun = false)
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
                rangeMode = record.rangeMode,
                sinceEpochMillis = record.sinceEpochMillis,
                sourcePath = record.sourcePath,
                destinationPath = record.destinationPath,
                hasUnsavedProfileChanges = false,
                lastResult = "正在继续上次未完成传输"
            )
        }
        saveConfig()
        runTransferAfterPresenceCheck(dryRun = false, resumeRecord = record)
    }

    private fun runTransferAfterPresenceCheck(
        dryRun: Boolean,
        resumeRecord: TransferRecord? = null
    ) {
        val current = _state.value
        if (current.isBusy || current.isCheckingPeerOnline) return
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        val pairedDeviceId = profile?.deviceId?.takeUnless { it.startsWith("manual:") }
        if (pairedDeviceId == null) {
            runTransfer(dryRun, resumeRecord)
            return
        }
        _state.update {
            it.copy(
                isCheckingPeerOnline = true,
                phase = "正在确认 ${profile.name} 是否在线",
                lastResult = "正在发送实时在线探测…"
            )
        }
        viewModelScope.launch {
            val online = discovery.confirmPresence(pairedDeviceId, profile.host)
            _state.update { state ->
                state.copy(
                    isCheckingPeerOnline = false,
                    onlineDeviceIds = if (online) {
                        state.onlineDeviceIds + pairedDeviceId
                    } else {
                        state.onlineDeviceIds - pairedDeviceId
                    },
                    phase = if (online) "设备在线，正在开始任务" else "所选设备离线",
                    lastResult = if (online) {
                        "${profile.name} 在线，已通过实时探测"
                    } else {
                        "${profile.name} 当前离线，未启动任何预览或传输"
                    }
                )
            }
            if (online) runTransfer(dryRun, resumeRecord)
            else appendLog("WARN", "实时在线探测失败：${profile.name}/${profile.host}")
        }
    }

    private fun runTransfer(dryRun: Boolean, resumeRecord: TransferRecord? = null) {
        val current = _state.value
        appendLog(
            "DIAG",
            "开始任务 dryRun=$dryRun role=${current.role.name} range=${current.rangeMode.name} " +
                "since=${current.sinceEpochMillis ?: "ALL"} host=${current.remoteHost}:${current.portText} " +
                "source=${current.sourcePath} destination=${current.destinationPath} profile=${current.profileName}"
        )
        val rsync = current.capabilities.rsyncPath
        val port = SafeInput.parsePort(current.portText)
        val localPath = when (current.role) {
            SyncRole.SEND_ONLY, SyncRole.BIDIRECTIONAL -> current.sourcePath
            SyncRole.RECEIVE_ONLY -> current.destinationPath
        }
        val validation = when {
            rsync == null -> "内置 rsync 不可执行"
            !SafeInput.isValidIpv4(current.remoteHost) -> "请输入有效的远端 IPv4 地址"
            SafeInput.isLocalSelfTarget(current.remoteHost, current.localIp) ->
                "远端地址指向本机，已阻止可能递归写入自身目录的任务"
            port == null -> "端口必须位于 1024–65535"
            current.remoteSecret.length < SyncUiState.MIN_SECRET_LENGTH -> "请完成配对或输入远端密钥"
            current.rangeMode == SyncRangeMode.SINCE && current.sinceEpochMillis == null ->
                "请选择同步起始时间"
            current.rangeMode == SyncRangeMode.SINCE &&
                current.sinceEpochMillis != null &&
                current.sinceEpochMillis > System.currentTimeMillis() -> "同步起始时间不能晚于当前时间"
            else -> SafeInput.validateStoragePath(localPath)
        }
        if (validation != null || rsync == null || port == null) {
            appendLog("ERROR", validation ?: "配置无效")
            _state.update {
                it.copy(
                    lastResult = validation,
                    transferPanelTitle = if (dryRun) "差异文件与文件夹" else it.transferPanelTitle,
                    previewStatusText = if (dryRun) {
                        "无法开始预览：${validation ?: "配置无效"}"
                    } else it.previewStatusText,
                    previewReady = false
                )
            }
            return
        }

        val action = current.role.label
        launchBusy(
            phase = if (dryRun) "正在预览${action}差异" else "正在执行$action",
            localTransfer = false
        ) {
            if (current.role != SyncRole.SEND_ONLY &&
                !ensureLocalDestinationReady(current.profileName, current.destinationPath, dryRun)
            ) {
                return@launchBusy
            }
            _state.update { it.copy(localTransferActive = true) }
            pauseRequested = false
            localTransferItemCount = 0
            lastTransferItemUiMillis = 0L
            pendingUploadedBytes = 0L
            pendingDownloadedBytes = 0L
            lastTransferBytesUiMillis = 0L
            if (dryRun) {
                previewUploadBytes = 0L
                previewDownloadBytes = 0L
            }
            val now = System.currentTimeMillis()
            val untilEpochMillis = now
            val activeRecord = if (dryRun) null else resumeRecord?.copy(
                host = current.remoteHost,
                port = port,
                secret = current.remoteSecret,
                role = current.role,
                rangeMode = current.rangeMode,
                sinceEpochMillis = current.sinceEpochMillis,
                sourcePath = current.sourcePath,
                destinationPath = current.destinationPath,
                status = TransferStatus.RUNNING,
                progress = 0f,
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
                rangeMode = current.rangeMode,
                sinceEpochMillis = current.sinceEpochMillis,
                sourcePath = current.sourcePath,
                destinationPath = current.destinationPath,
                status = TransferStatus.RUNNING,
                message = "正在建立连接"
            )
            _state.update {
                it.copy(
                    progress = if (dryRun) null else 0f,
                    estimatedCompletionTime = if (dryRun) null else "正在统计同步总量…",
                    totalSyncBytes = 0L,
                    uploadedBytes = 0L,
                    downloadedBytes = 0L,
                    transferSpeedBytesPerSecond = 0L,
                    isPreviewing = dryRun,
                    transferPanelTitle = transferPanelTitle(dryRun, current.role),
                    transferFolders = emptyList(),
                    currentTransferFolder = null,
                    transferItemCount = 0,
                    transferFoldersTruncated = false,
                    transferRecord = if (dryRun) it.transferRecord else activeRecord,
                    previewStatusText = if (dryRun) "正在请求对方准备差异扫描…" else null,
                    lastResult = null
                )
            }
            if (!dryRun) {
                persistTransferRecord()
                updateTransferNotification(action, "正在准备远端服务", null, 0f)
            }
            val endpoint = ensureRemoteServerReady(current, port, untilEpochMillis, dryRun)
            if (endpoint == null) {
                if (!dryRun) markTransferPaused("远端暂时不可用，传输已自动暂停")
                else _state.update {
                    it.copy(
                        isPreviewing = false,
                        progress = null,
                        previewStatusText = "扫描未完成：${it.lastResult ?: "对方暂时不可用"}"
                    )
                }
                return@launchBusy
            }
            if (!dryRun) updateTransferNotification(action, "已连接，正在扫描文件", null, 0f)
            _state.update {
                it.copy(
                    previewStatusText = if (dryRun) "正在扫描双方差异文件夹…" else it.previewStatusText
                )
            }
            val updateProgress: (Float) -> Unit = { progress ->
                updateTransferProgress(progress, dryRun, action)
            }
            val remoteActivityType = if (dryRun) SyncActivityType.PREVIEW else SyncActivityType.TRANSFER
            val remoteTaskId = UUID.randomUUID().toString()
            discovery.sendSyncActivity(endpoint.host, remoteActivityType, active = true, taskId = remoteTaskId)
            var remoteItemCount = 0
            var lastRemoteItemSentAtMillis = 0L
            val sendRemoteItem: (RsyncItem) -> Unit = { item ->
                remoteItemCount += 1
                val nowMillis = System.currentTimeMillis()
                val throttleMillis = if (dryRun) REMOTE_PREVIEW_ITEM_THROTTLE_MS else TRANSFER_ITEM_REFRESH_MS
                if (remoteItemCount == 1 || nowMillis - lastRemoteItemSentAtMillis >= throttleMillis) {
                    lastRemoteItemSentAtMillis = nowMillis
                    discovery.sendSyncItem(
                        endpoint.host,
                        remoteActivityType,
                        remoteTaskId,
                        item.relativePath,
                        remoteItemCount
                    )
                }
            }
            var planned = if (dryRun) {
                PlannedTransfer()
            } else {
                _state.update { it.copy(phase = "正在统计需要同步的数据", estimatedCompletionTime = "正在统计同步总量…") }
                measurePlannedTransfer(
                    current,
                    endpoint,
                    rsync,
                    untilEpochMillis
                )
            }
            if (!dryRun && planned.result.success && planned.downloadBytes > 0L) {
                val availableBytes = engine.availableStorageBytes(current.destinationPath)
                if (availableBytes != null) {
                    val requiredBytes = requiredStorageBytes(planned.downloadBytes)
                    if (availableBytes < requiredBytes) {
                        val message = "本机接收空间不足：预计下载 ${formatBytes(planned.downloadBytes)}，" +
                            "需保留至少 ${formatBytes(requiredBytes)}，当前可用 ${formatBytes(availableBytes)}"
                        appendLog("ERROR", message)
                        planned = planned.copy(result = EngineResult(false, message))
                    }
                } else {
                    appendLog("WARN", "无法读取本机接收目录剩余空间，将由 rsync 写入错误保护任务")
                }
            }
            if (!dryRun && planned.result.success && planned.uploadBytes > 0L) {
                val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
                if (profile != null && !profile.deviceId.startsWith("manual:")) {
                    _state.update { it.copy(phase = "正在检查对方接收空间") }
                    val remoteStorage = discovery.requestRemoteStorageCheck(endpoint.host, planned.uploadBytes)
                    if (remoteStorage == null) {
                        val message = "${profile.name} 未响应接收空间检查；为避免写满对方存储，本次任务已暂停"
                        appendLog("ERROR", message)
                        planned = planned.copy(result = EngineResult(false, message))
                    } else if (!remoteStorage.ready) {
                        val message = "${profile.name}：${remoteStorage.message}"
                        appendLog("ERROR", message)
                        planned = planned.copy(result = EngineResult(false, message))
                    } else {
                        appendLog("OK", "${profile.name}：${remoteStorage.message}")
                    }
                } else {
                    appendLog("WARN", "手动 rsync 设备不支持远端空间预检，请在接收端自行确认可用空间")
                }
            }
            if (!dryRun && planned.result.success) {
                resetTransferSpeedTracking()
                _state.update {
                    it.copy(
                        totalSyncBytes = planned.uploadBytes + planned.downloadBytes,
                        estimatedCompletionTime = if (planned.uploadBytes + planned.downloadBytes > 0L) {
                            "正在采集近 10 秒传输速度…"
                        } else {
                            "没有需要传输的数据"
                        },
                        phase = "正在执行$action"
                    )
                }
            }
            val etaJob = if (!dryRun && planned.result.success) {
                viewModelScope.launch {
                    while (isActive) {
                        delay(ETA_UPDATE_INTERVAL_MS)
                        refreshTransferEta(action)
                    }
                }
            } else null
            val remoteHeartbeatJob = viewModelScope.launch {
                while (isActive) {
                    delay(REMOTE_ACTIVITY_HEARTBEAT_MS)
                    discovery.sendSyncActivity(
                        endpoint.host,
                        remoteActivityType,
                        active = true,
                        taskId = remoteTaskId
                    )
                }
            }
            val result = try {
                if (!planned.result.success) planned.result else when (current.role) {
                SyncRole.SEND_ONLY -> engine.push(
                    rsyncPath = rsync,
                    host = endpoint.host,
                    port = endpoint.port,
                    sourcePath = current.sourcePath,
                    secret = endpoint.secret,
                    rangeMode = current.rangeMode,
                    sinceEpochMillis = current.sinceEpochMillis,
                    untilEpochMillis = untilEpochMillis,
                    bidirectional = false,
                    dryRun = dryRun,
                    strictChecksum = current.strictContentCheck,
                    onLog = ::streamLog,
                    onProgress = updateProgress,
                    onItem = { item ->
                        updateTransferItem(item, dryRun, current.role)
                        sendRemoteItem(item)
                    },
                    onTransferredBytes = { bytes ->
                        if (!dryRun) updateTransferBytes(upload = true, bytes = bytes, action = action)
                    }
                )
                SyncRole.RECEIVE_ONLY -> engine.pull(
                    rsyncPath = rsync,
                    host = endpoint.host,
                    port = endpoint.port,
                    destinationPath = current.destinationPath,
                    secret = endpoint.secret,
                    rangeMode = current.rangeMode,
                    sinceEpochMillis = current.sinceEpochMillis,
                    untilEpochMillis = untilEpochMillis,
                    bidirectional = false,
                    dryRun = dryRun,
                    strictChecksum = current.strictContentCheck,
                    onLog = ::streamLog,
                    onProgress = updateProgress,
                    onItem = { item ->
                        updateTransferItem(item, dryRun, current.role)
                        sendRemoteItem(item)
                    },
                    onTransferredBytes = { bytes ->
                        if (!dryRun) updateTransferBytes(upload = false, bytes = bytes, action = action)
                    }
                )
                    SyncRole.BIDIRECTIONAL -> {
                    appendLog("INFO", "双向同步第 1/2 阶段：接收远端较新文件")
                    val pullResult = engine.pull(
                        rsyncPath = rsync,
                        host = endpoint.host,
                        port = endpoint.port,
                        destinationPath = current.destinationPath,
                        secret = endpoint.secret,
                        rangeMode = current.rangeMode,
                        sinceEpochMillis = current.sinceEpochMillis,
                        untilEpochMillis = untilEpochMillis,
                        bidirectional = true,
                        dryRun = dryRun,
                        strictChecksum = current.strictContentCheck,
                        onLog = ::streamLog,
                        onProgress = { progress -> updateProgress(progress * 0.5f) },
                        onItem = { item ->
                            updateTransferItem(item, dryRun, current.role, "接收")
                            sendRemoteItem(item)
                        },
                        onTransferredBytes = { bytes ->
                            if (!dryRun) updateTransferBytes(upload = false, bytes = bytes, action = action)
                        }
                    )
                    if (!pullResult.success || pauseRequested) {
                        pullResult
                    } else {
                        appendLog("INFO", "双向同步第 2/2 阶段：发送本机较新文件")
                        val pushResult = engine.push(
                            rsyncPath = rsync,
                            host = endpoint.host,
                            port = endpoint.port,
                            sourcePath = current.sourcePath,
                            secret = endpoint.secret,
                            rangeMode = current.rangeMode,
                            sinceEpochMillis = current.sinceEpochMillis,
                            untilEpochMillis = untilEpochMillis,
                            bidirectional = true,
                            dryRun = dryRun,
                            strictChecksum = current.strictContentCheck,
                            onLog = ::streamLog,
                            onProgress = { progress -> updateProgress(0.5f + progress * 0.5f) },
                            onItem = { item ->
                                updateTransferItem(item, dryRun, current.role, "发送")
                                sendRemoteItem(item)
                            },
                            onTransferredBytes = { bytes ->
                                if (!dryRun) updateTransferBytes(upload = true, bytes = bytes, action = action)
                            }
                        )
                        if (pushResult.success) {
                            EngineResult(
                                true,
                                if (dryRun) {
                                    "双向差异预览完成；全程零删除"
                                } else {
                                    if (current.strictContentCheck) {
                                        "双向同步完成，所选范围严格内容比较和两个方向传输后校验均通过；未删除任何用户数据"
                                    } else {
                                        "双向同步完成，本次实际传输文件校验通过；快速模式未读取未变化文件内容，未删除任何用户数据"
                                    }
                                }
                            )
                        } else pushResult
                    }
                    }
                }
            } finally {
                if (!dryRun) flushTransferBytes(action, force = true)
                etaJob?.cancel()
                remoteHeartbeatJob.cancel()
                discovery.sendSyncActivity(
                    endpoint.host,
                    remoteActivityType,
                    active = false,
                    taskId = remoteTaskId,
                    totalItems = remoteItemCount
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
                    progress = when {
                        dryRun -> null
                        result.success -> 1f
                        else -> it.progress
                    },
                    estimatedCompletionTime = if (!dryRun && result.success) "已完成" else it.estimatedCompletionTime,
                    isPreviewing = false,
                    transferItemCount = maxOf(it.transferItemCount, localTransferItemCount),
                    transferRecord = if (!dryRun && record != null) {
                        record.copy(
                            status = if (result.success) TransferStatus.COMPLETED else TransferStatus.PAUSED,
                            progress = if (result.success) 1f else record.progress,
                            updatedAtMillis = System.currentTimeMillis(),
                            message = if (result.success) result.summary else "${result.summary}；已自动暂停"
                        )
                    } else record,
                    previewStatusText = if (dryRun) {
                        if (result.success) {
                            if (it.transferItemCount == 0) {
                                "扫描完成，没有发现需要新增或更新的文件或目录。"
                            } else {
                                "扫描完成，发现 ${it.transferItemCount} 个差异项目。"
                            }
                        } else {
                            "扫描失败：${result.summary}"
                        }
                    } else it.previewStatusText,
                    lastResult = result.summary,
                    phase = if (!dryRun && !result.success) "连接中断，已自动暂停" else result.summary
                )
            }
            if (!dryRun) {
                persistTransferRecord()
                if (result.success) {
                    TransferForegroundService.notifyCompleted(
                        getApplication(),
                        "RootSync 同步完成",
                        if (current.strictContentCheck) {
                            "${current.profileName.ifBlank { current.remoteHost }}：所选范围严格内容比较和传输后校验通过"
                        } else {
                            "${current.profileName.ifBlank { current.remoteHost }}：本次实际传输文件校验通过（快速模式）"
                        }
                    )
                }
                TransferForegroundService.stop(getApplication())
            }
            appendLog(if (result.success) "OK" else "ERROR", result.summary)
        }
    }

    fun pauseTransfer() {
        if (!_state.value.localTransferActive) {
            appendLog("WARN", "当前是对方发起的任务，不能从本机伪暂停；请在发起设备操作")
            return
        }
        if (_state.value.isPreviewing) {
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
            val removedId = it.transferRecord?.id
            it.copy(
                transferRecord = null,
                transferRecords = if (removedId == null) it.transferRecords else {
                    it.transferRecords.filterNot { record -> record.id == removedId }
                },
                progress = null,
                estimatedCompletionTime = null,
                lastResult = "传输记录已删除；已下载的分片和用户文件未删除"
            )
        }
        persistTransferRecord()
    }

    private suspend fun measurePlannedTransfer(
        current: SyncUiState,
        endpoint: RemoteEndpoint,
        rsyncPath: String,
        untilEpochMillis: Long
    ): PlannedTransfer {
        var uploadBytes = 0L
        var downloadBytes = 0L
        val countUpload: (RsyncItem) -> Unit = { item ->
            if (item.itemizedChange.getOrNull(1) != 'd') {
                uploadBytes = safeAddBytes(uploadBytes, item.sizeBytes)
            }
        }
        val countDownload: (RsyncItem) -> Unit = { item ->
            if (item.itemizedChange.getOrNull(1) != 'd') {
                downloadBytes = safeAddBytes(downloadBytes, item.sizeBytes)
            }
        }
        appendLog("INFO", "预扫描差异以统计同步总数据大小…")
        val pullResult = if (current.role != SyncRole.SEND_ONLY) {
            engine.pull(
                rsyncPath = rsyncPath,
                host = endpoint.host,
                port = endpoint.port,
                destinationPath = current.destinationPath,
                secret = endpoint.secret,
                rangeMode = current.rangeMode,
                sinceEpochMillis = current.sinceEpochMillis,
                untilEpochMillis = untilEpochMillis,
                bidirectional = current.role == SyncRole.BIDIRECTIONAL,
                dryRun = true,
                strictChecksum = current.strictContentCheck,
                onLog = ::streamLog,
                onProgress = {},
                onItem = countDownload
            )
        } else EngineResult(true, "无需统计下载量")
        if (!pullResult.success) return PlannedTransfer(result = pullResult)

        val pushResult = if (current.role != SyncRole.RECEIVE_ONLY) {
            engine.push(
                rsyncPath = rsyncPath,
                host = endpoint.host,
                port = endpoint.port,
                sourcePath = current.sourcePath,
                secret = endpoint.secret,
                rangeMode = current.rangeMode,
                sinceEpochMillis = current.sinceEpochMillis,
                untilEpochMillis = untilEpochMillis,
                bidirectional = current.role == SyncRole.BIDIRECTIONAL,
                dryRun = true,
                strictChecksum = current.strictContentCheck,
                onLog = ::streamLog,
                onProgress = {},
                onItem = countUpload
            )
        } else EngineResult(true, "无需统计上传量")
        if (!pushResult.success) return PlannedTransfer(result = pushResult)
        appendLog("INFO", "同步总量统计完成：上传 ${formatBytes(uploadBytes)}，下载 ${formatBytes(downloadBytes)}")
        return PlannedTransfer(uploadBytes, downloadBytes)
    }

    private fun safeAddBytes(current: Long, increment: Long): Long {
        val safeIncrement = increment.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - current < safeIncrement) Long.MAX_VALUE else current + safeIncrement
    }

    private fun requiredStorageBytes(downloadBytes: Long): Long {
        val reserveBytes = (downloadBytes.coerceAtLeast(0L) / 10L)
            .coerceIn(MIN_STORAGE_RESERVE_BYTES, MAX_STORAGE_RESERVE_BYTES)
        return safeAddBytes(downloadBytes, reserveBytes)
    }

    private fun updateTransferItem(
        item: RsyncItem,
        dryRun: Boolean,
        role: SyncRole,
        directionLabel: String? = null
    ) {
        localTransferItemCount += 1
        if (dryRun && item.itemizedChange.getOrNull(1) != 'd') {
            val isUpload = role == SyncRole.SEND_ONLY ||
                (role == SyncRole.BIDIRECTIONAL && directionLabel == "发送")
            if (isUpload) previewUploadBytes = safeAddBytes(previewUploadBytes, item.sizeBytes)
            else previewDownloadBytes = safeAddBytes(previewDownloadBytes, item.sizeBytes)
        }
        val now = System.currentTimeMillis()
        val shouldRefresh = dryRun || localTransferItemCount == 1 ||
            now - lastTransferItemUiMillis >= TRANSFER_ITEM_REFRESH_MS
        if (!shouldRefresh) return
        lastTransferItemUiMillis = now
        _state.update { state ->
            val prefix = directionLabel?.let { "$it · " }.orEmpty()
            val display = if (dryRun) {
                "$prefix${item.changeLabel} · ${item.relativePath}"
            } else {
                "$prefix${item.relativePath}"
            }
            val alreadyShown = display in state.transferFolders
            val canAppend = !alreadyShown && state.transferFolders.size < MAX_VISIBLE_TRANSFER_FOLDERS
            state.copy(
                transferFolders = if (canAppend) state.transferFolders + display else state.transferFolders,
                currentTransferFolder = item.relativePath,
                transferItemCount = localTransferItemCount,
                transferFoldersTruncated = state.transferFoldersTruncated || (!alreadyShown && !canAppend),
                transferPanelTitle = transferPanelTitle(dryRun, role)
            )
        }
    }

    private fun transferPanelTitle(dryRun: Boolean, role: SyncRole): String = when {
        dryRun -> "差异文件与文件夹"
        role == SyncRole.SEND_ONLY -> "正在上传的文件与目录"
        role == SyncRole.RECEIVE_ONLY -> "正在接收的文件与目录"
        else -> "正在双向同步的文件与目录"
    }

    private fun updateTransferProgress(
        progress: Float,
        dryRun: Boolean,
        @Suppress("UNUSED_PARAMETER") action: String
    ) {
        if (!dryRun) return
        val normalized = progress.coerceIn(0f, 1f)
        _state.update { state ->
            state.copy(
                progress = normalized
            )
        }
    }

    private fun resetTransferSpeedTracking() {
        synchronized(transferSpeedSamples) {
            transferSpeedSamples.clear()
            val now = System.currentTimeMillis()
            val state = _state.value
            transferSpeedSamples.addLast(
                TransferSpeedSample(now, safeAddBytes(state.uploadedBytes, state.downloadedBytes))
            )
        }
    }

    private fun updateTransferBytes(upload: Boolean, bytes: Long, action: String) {
        val safeBytes = bytes.coerceAtLeast(0L)
        if (upload) pendingUploadedBytes = maxOf(pendingUploadedBytes, safeBytes)
        else pendingDownloadedBytes = maxOf(pendingDownloadedBytes, safeBytes)
        val now = System.currentTimeMillis()
        if (now - lastTransferBytesUiMillis >= TRANSFER_BYTES_UI_INTERVAL_MS) {
            flushTransferBytes(action, force = false)
        }
    }

    private fun flushTransferBytes(action: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTransferBytesUiMillis < TRANSFER_BYTES_UI_INTERVAL_MS) return
        lastTransferBytesUiMillis = now
        _state.update { state ->
            val uploaded = maxOf(state.uploadedBytes, pendingUploadedBytes)
            val downloaded = maxOf(state.downloadedBytes, pendingDownloadedBytes)
            val done = safeAddBytes(uploaded, downloaded)
            val progress = if (state.totalSyncBytes > 0L) {
                (done.toDouble() / state.totalSyncBytes).toFloat().coerceIn(0f, 1f)
            } else state.progress
            state.copy(
                uploadedBytes = uploaded,
                downloadedBytes = downloaded,
                progress = progress,
                transferRecord = state.transferRecord?.let { record ->
                    record.copy(
                        progress = progress ?: record.progress,
                        updatedAtMillis = System.currentTimeMillis(),
                        message = state.currentTransferFolder?.let { "正在处理 $it" } ?: "正在传输"
                    )
                }
            )
        }

        val snapshot = _state.value
        val done = safeAddBytes(snapshot.uploadedBytes, snapshot.downloadedBytes)
        synchronized(transferSpeedSamples) {
            transferSpeedSamples.addLast(TransferSpeedSample(now, done))
            val cutoff = now - ETA_SAMPLE_WINDOW_MS
            while (transferSpeedSamples.size > 2 && transferSpeedSamples.elementAt(1).timeMillis <= cutoff) {
                transferSpeedSamples.removeFirst()
            }
        }
        val notificationState = _state.value
        val notificationNow = now
        if (notificationNow - lastTransferPersistMillis >= 1_000L) {
            lastTransferPersistMillis = notificationNow
            persistTransferRecord()
            updateTransferNotification(
                action,
                "已上传 ${formatBytes(notificationState.uploadedBytes)} · 已下载 ${formatBytes(notificationState.downloadedBytes)}",
                notificationState.estimatedCompletionTime,
                notificationState.progress
            )
        }
    }

    private fun refreshTransferEta(action: String) {
        val now = System.currentTimeMillis()
        val snapshot = _state.value
        val done = safeAddBytes(snapshot.uploadedBytes, snapshot.downloadedBytes)
        val speed: Long
        synchronized(transferSpeedSamples) {
            transferSpeedSamples.addLast(TransferSpeedSample(now, done))
            val cutoff = now - ETA_SAMPLE_WINDOW_MS
            while (transferSpeedSamples.size > 2 && transferSpeedSamples.elementAt(1).timeMillis <= cutoff) {
                transferSpeedSamples.removeFirst()
            }
            val first = transferSpeedSamples.firstOrNull()
            val last = transferSpeedSamples.lastOrNull()
            val elapsedMillis = if (first != null && last != null) last.timeMillis - first.timeMillis else 0L
            val deltaBytes = if (first != null && last != null) {
                (last.totalBytes - first.totalBytes).coerceAtLeast(0L)
            } else 0L
            speed = if (elapsedMillis > 0L) {
                (deltaBytes.toDouble() * 1_000.0 / elapsedMillis).toLong().coerceAtLeast(0L)
            } else 0L
        }
        val remaining = (snapshot.totalSyncBytes - done).coerceAtLeast(0L)
        val eta = when {
            remaining == 0L -> "已完成"
            speed <= 0L -> "近 10 秒无有效传输，暂无法估算剩余时间"
            else -> {
                val remainingSeconds = kotlin.math.ceil(remaining.toDouble() / speed.toDouble())
                    .toLong().coerceAtLeast(1L)
                "预计还需 ${formatDuration(remainingSeconds)}（近 10 秒 ${formatBytes(speed)}/s）"
            }
        }
        _state.update {
            it.copy(estimatedCompletionTime = eta, transferSpeedBytesPerSecond = speed)
        }
        val state = _state.value
        updateTransferNotification(
            action,
            "已上传 ${formatBytes(state.uploadedBytes)} · 已下载 ${formatBytes(state.downloadedBytes)}",
            eta,
            state.progress
        )
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var scaled = value
        var unitIndex = 0
        while (scaled >= 1000.0 && unitIndex < units.lastIndex) {
            scaled /= 1000.0
            unitIndex += 1
        }
        return if (unitIndex == 0) "${scaled.toLong()} ${units[unitIndex]}"
        else String.format(java.util.Locale.US, "%.2f %s", scaled, units[unitIndex])
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
                transferItemCount = maxOf(state.transferItemCount, localTransferItemCount),
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
        configuredPort: Int,
        untilEpochMillis: Long,
        isPreview: Boolean
    ): RemoteEndpoint? {
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile == null || profile.deviceId.startsWith("manual:")) {
            appendLog("LAN", "手动设备直接尝试 rsync 连接，避免 TCP 探测占用唯一连接槽")
            return RemoteEndpoint(current.remoteHost, configuredPort, current.remoteSecret)
        }

        appendLog(
            "LAN",
            "正在请求 ${profile.name} 按本次${current.role.label}方向准备正确的发送/接收模块"
        )
        _state.update { it.copy(phase = "等待 ${profile.name} 准备服务端") }
        val prepared = discovery.requestSyncPreparation(
            host = current.remoteHost,
            role = current.role,
            rangeMode = current.rangeMode,
            sinceEpochMillis = current.sinceEpochMillis,
            untilEpochMillis = untilEpochMillis,
            isPreview = isPreview,
            onWaiting = { status ->
                val waitingText = "${profile.name} 正在选择操作：$status"
                _state.update { state ->
                    state.copy(
                        phase = "${profile.name} 正在选择操作",
                        previewStatusText = if (isPreview) waitingText else state.previewStatusText,
                        lastResult = waitingText,
                        transferRecord = if (!isPreview) {
                            state.transferRecord?.copy(
                                updatedAtMillis = System.currentTimeMillis(),
                                message = waitingText
                            )
                        } else state.transferRecord
                    )
                }
                if (!isPreview) {
                    updateTransferNotification(current.role.label, waitingText, null, current.progress)
                }
            }
        )
        if (prepared == null) {
            val message = "${profile.name} 未响应服务准备请求；为防止连接到旧设备策略，本次任务已停止"
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
        val preparedSecret = prepared.secret.takeIf { it.length >= SyncUiState.MIN_SECRET_LENGTH }
        if (preparedSecret == null) {
            val message = "${profile.name} 未返回本次任务的会话密钥；为防止复用旧服务，本次任务已停止"
            appendLog("ERROR", message)
            _state.update { it.copy(lastResult = message, phase = "远端会话无效") }
            return null
        }
        _state.update { state ->
            state.copy(
                portText = preparedPort.toString(),
                profiles = state.profiles.map { item ->
                    if (item.id == profile.id) {
                        item.copy(port = preparedPort, host = prepared.host)
                    } else item
                }
            )
        }
        saveConfig()
        delay(150)
        appendLog("OK", "${profile.name} 已自动准备 rsync 服务")
        return RemoteEndpoint(prepared.host, preparedPort, preparedSecret)
    }

    fun cancel() {
        if (!_state.value.localTransferActive) {
            appendLog("WARN", "当前没有可由本机取消的任务")
            return
        }
        viewModelScope.launch {
            val result = engine.cancel()
            appendLog(if (result.success) "INFO" else "ERROR", result.summary)
        }
        _state.update { state ->
            state.copy(
                isBusy = false,
                localTransferActive = false,
                isPreviewing = false,
                phase = "任务已取消",
                progress = if (state.isPreviewing) null else state.progress,
                previewStatusText = if (state.isPreviewing) "差异预览已取消。" else state.previewStatusText,
                lastResult = "任务已取消"
            )
        }
        appendLog("WARN", "用户取消了当前任务，正在结束底层 rsync 进程")
    }

    private suspend fun refreshProfilePresence() {
        val profiles = _state.value.profiles.filterNot { it.deviceId.startsWith("manual:") }
        if (profiles.isEmpty()) {
            _state.update { it.copy(onlineDeviceIds = emptySet()) }
            return
        }
        discovery.probePresence(profiles.map { it.host })
        delay(PROFILE_PRESENCE_RESPONSE_MS)
        _state.update { state ->
            val recentlyDiscovered = state.discoveredDevices
                .filter { System.currentTimeMillis() - it.lastSeenMillis <= PROFILE_ONLINE_TTL_MS }
                .mapTo(linkedSetOf()) { it.deviceId }
            state.copy(onlineDeviceIds = recentlyDiscovered)
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
        diagnosticLogger.append("INFO", "用户清空了界面日志；持久诊断日志继续保留")
    }

    fun exportDiagnosticLog(uri: Uri) {
        viewModelScope.launch {
            diagnosticLogger.append("EXPORT", "用户请求导出详细诊断日志")
            val result = diagnosticLogger.export(uri, diagnosticStateHeader())
            if (result.isSuccess) {
                appendLog("OK", "详细诊断日志已导出")
                _state.update { it.copy(lastResult = "详细诊断日志已导出") }
            } else {
                val message = result.exceptionOrNull()?.message ?: "未知错误"
                appendLog("ERROR", "导出详细诊断日志失败：$message")
                _state.update { it.copy(lastResult = "导出日志失败：$message") }
            }
        }
    }

    fun diagnosticAdbCommand(): String = diagnosticLogger.adbReadCommand()

    fun diagnosticLogPath(): String = diagnosticLogger.pathDescription()

    private fun launchBusy(
        phase: String,
        localTransfer: Boolean = false,
        block: suspend () -> Unit
    ) {
        if (remoteSessionDeviceId != null) {
            appendLog("WARN", "正在为远端设备提供同步服务，请等待当前任务结束")
            return
        }
        if (_state.value.isBusy || !operationMutex.tryLock()) return
        viewModelScope.launch {
            _state.update {
                it.copy(isBusy = true, localTransferActive = localTransfer, phase = phase)
            }
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
                _state.update { it.copy(isBusy = false, localTransferActive = false) }
                operationMutex.unlock()
            }
        }
    }

    override fun onCleared() {
        pendingDirectoryDecision?.cancel()
        nsdPortRefreshJob?.cancel()
        discovery.stop()
        super.onCleared()
    }

    private fun streamLog(message: String) {
        when {
            message.startsWith("INTEGRITY_VERIFY_START") -> {
                _state.update {
                    it.copy(
                        phase = "正在校验已同步数据完整性",
                        estimatedCompletionTime = "正在逐文件计算并比对校验和…"
                    )
                }
                val state = _state.value
                updateTransferNotification(
                    state.role.label,
                    "数据传输结束，正在执行完整性校验",
                    state.estimatedCompletionTime,
                    state.progress
                )
            }
            message.startsWith("INTEGRITY_VERIFY_PASSED") -> {
                _state.update { it.copy(phase = "本次实际传输文件校验通过") }
            }
        }
        appendLog("RSYNC", message)
    }

    private fun appendLog(level: String, message: String) {
        if (message.isBlank()) return
        diagnosticLogger.append(level, message)
        val entry = LogEntry(
            time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            level = level,
            message = message
        )
        _state.update { it.copy(logs = (it.logs + entry).takeLast(400)) }
    }

    private fun diagnosticStateHeader(): String {
        val current = _state.value
        val capabilities = current.capabilities
        return buildString {
            appendLine("===== 导出时状态快照 =====")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("deviceName=${current.deviceName}")
            appendLine("deviceId=$deviceId")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
            appendLine("localIp=${current.localIp}")
            appendLine("rootGranted=${capabilities.rootGranted}")
            appendLine("rsyncPath=${capabilities.rsyncPath ?: "null"}")
            appendLine("rsyncVersion=${capabilities.rsyncVersion ?: "null"}")
            appendLine("syncMetaReady=${capabilities.syncMetaReady}")
            appendLine("selectedProfile=${current.profileName}/${current.selectedProfileId ?: "manual"}")
            appendLine("remote=${current.remoteHost}:${current.portText}")
            appendLine("role=${current.role.name}")
            appendLine("range=${current.rangeMode.name} since=${current.sinceEpochMillis ?: "ALL"}")
            appendLine("sourcePath=${current.sourcePath}")
            appendLine("destinationPath=${current.destinationPath}")
            appendLine("serverPort=${current.serverPortText} serverRunning=${current.serverRunning}")
            appendLine("phase=${current.phase}")
            appendLine("isBusy=${current.isBusy} isPreviewing=${current.isPreviewing}")
            appendLine("previewReady=${current.previewReady} items=${current.transferItemCount}")
            appendLine("previewStatus=${current.previewStatusText ?: "null"}")
            appendLine("lastResult=${current.lastResult ?: "null"}")
            appendLine("transferStatus=${current.transferRecord?.status?.name ?: "none"}")
            append("注意：配对密钥不会写入诊断日志。")
        }
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
                put("controlToken", profile.controlToken)
                put("role", profile.role.name)
                put("rangeMode", profile.rangeMode.name)
                put("sinceEpochMillis", profile.sinceEpochMillis)
                put("sourcePath", profile.sourcePath)
                put("destinationPath", profile.destinationPath)
            })
        }
    }.toString()

    private fun decodeProfiles(raw: String?): List<PeerProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { error ->
            diagnosticLogger.append("ERROR", "设备配置 JSON 损坏：${error.message ?: error::class.java.simpleName}")
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val profile = runCatching {
                    val item = array.getJSONObject(index)
                    val role = runCatching { SyncRole.valueOf(item.getString("role")) }
                        .getOrDefault(SyncRole.RECEIVE_ONLY)
                    val rangeMode = runCatching {
                        SyncRangeMode.valueOf(item.optString("rangeMode", SyncRangeMode.ALL.name))
                    }.getOrDefault(SyncRangeMode.ALL)
                    val since = item.optLong("sinceEpochMillis", -1L).takeIf { it > 0L }
                    val sourcePath = migrateBiliPath(
                        item.optString("sourcePath", SyncUiState.DEFAULT_BILI_PATH)
                    )
                    val secret = item.optString("secret")
                    PeerProfile(
                            id = item.getString("id"),
                            deviceId = item.optString("deviceId", "manual:${item.getString("host")}"),
                            name = item.optString("name", item.getString("host")),
                            host = item.getString("host"),
                            port = item.optInt("port", SyncUiState.DEFAULT_RSYNC_PORT),
                            secret = secret,
                            controlToken = item.optString("controlToken")
                                .takeIf { it.length >= MIN_CONTROL_TOKEN_LENGTH }
                                ?: legacyControlToken(secret),
                            role = role,
                            rangeMode = rangeMode,
                            sinceEpochMillis = since,
                            sourcePath = sourcePath,
                            destinationPath = if (role == SyncRole.BIDIRECTIONAL) {
                                sourcePath
                            } else migrateBiliPath(
                                item.optString("destinationPath", SyncUiState.DEFAULT_BILI_PATH)
                            )
                    )
                }.onFailure { error ->
                    diagnosticLogger.append(
                        "ERROR",
                        "已跳过损坏的第 ${index + 1} 条设备配置：${error.message ?: error::class.java.simpleName}"
                    )
                }.getOrNull()
                if (profile != null) add(profile)
                }
            }
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
        put("rangeMode", record.rangeMode.name)
        put("sinceEpochMillis", record.sinceEpochMillis)
        put("sourcePath", record.sourcePath)
        put("destinationPath", record.destinationPath)
        put("status", record.status.name)
        put("progress", record.progress.toDouble())
        put("createdAtMillis", record.createdAtMillis)
        put("updatedAtMillis", record.updatedAtMillis)
        put("message", record.message)
    }.toString()

    private fun encodeTransferRecords(records: List<TransferRecord>): String = JSONArray().apply {
        records.sortedByDescending { it.updatedAtMillis }.take(MAX_TRANSFER_RECORDS).forEach { record ->
            put(JSONObject(encodeTransferRecord(record)))
        }
    }.toString()

    private fun decodeTransferRecords(raw: String?): List<TransferRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeTransferRecord(array.getJSONObject(index).toString())?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun recordMatchesProfile(record: TransferRecord, profile: PeerProfile): Boolean =
        record.profileId == profile.id ||
            (!record.deviceId.isNullOrBlank() && record.deviceId == profile.deviceId)

    private fun mergeTransferRecord(
        records: List<TransferRecord>,
        record: TransferRecord
    ): List<TransferRecord> = (records.filterNot { existing ->
        existing.id == record.id ||
            (record.profileId != null && existing.profileId == record.profileId) ||
            (record.deviceId != null && existing.deviceId == record.deviceId)
    } + record).sortedByDescending { it.updatedAtMillis }.take(MAX_TRANSFER_RECORDS)

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
                rangeMode = runCatching {
                    SyncRangeMode.valueOf(item.optString("rangeMode", SyncRangeMode.ALL.name))
                }.getOrDefault(SyncRangeMode.ALL),
                sinceEpochMillis = item.optLong("sinceEpochMillis", -1L).takeIf { it > 0L },
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

    private fun legacyControlToken(secret: String): String = buildString(64) {
        MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .forEach { byte -> append((byte.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }

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
        const val DEFAULT_RANGE_MILLIS = 24L * 60L * 60L * 1000L
        const val REMOTE_ACTIVITY_TIMEOUT_MS = 90_000L
        const val REMOTE_ACTIVITY_HEARTBEAT_MS = 30_000L
        const val REMOTE_ACTIVITY_FINISHED_HOLD_MS = 8_000L
        const val REMOTE_SESSION_START_TIMEOUT_MS = 2L * 60L * 1000L
        const val DIRECTORY_DECISION_TIMEOUT_MS = 110_000L
        const val PROFILE_PRESENCE_INTERVAL_MS = 15_000L
        const val PROFILE_ONLINE_TTL_MS = 45_000L
        const val PROFILE_PRESENCE_RESPONSE_MS = 800L
        const val NSD_PORT_DEBOUNCE_MS = 800L
        const val MIN_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
        const val MAX_STORAGE_RESERVE_BYTES = 512L * 1024L * 1024L
        const val REMOTE_PREVIEW_ITEM_THROTTLE_MS = 150L
        const val TRANSFER_ITEM_REFRESH_MS = 3_000L
        const val TRANSFER_BYTES_UI_INTERVAL_MS = 250L
        const val ETA_SAMPLE_WINDOW_MS = 10_000L
        const val ETA_UPDATE_INTERVAL_MS = 10_000L
        const val MIN_CONTROL_TOKEN_LENGTH = 32
        const val MAX_TRANSFER_RECORDS = 32
    }

    private data class RemoteEndpoint(val host: String, val port: Int, val secret: String)
    private data class PlannedTransfer(
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val result: EngineResult = EngineResult(true, "同步总量统计完成")
    )
    private data class TransferSpeedSample(val timeMillis: Long, val totalBytes: Long)
}

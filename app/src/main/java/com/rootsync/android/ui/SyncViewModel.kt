package com.rootsync.android.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import com.rootsync.android.domain.StrategyRevision
import com.rootsync.android.domain.StrategyLogic
import com.rootsync.android.domain.SyncActivityType
import com.rootsync.android.domain.SyncActivityUpdate
import com.rootsync.android.domain.SyncPrepareRequest
import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.ScanProgress
import com.rootsync.android.domain.SyncUiState
import com.rootsync.android.domain.TransferRecord
import com.rootsync.android.domain.TransferStatus
import com.rootsync.android.domain.TrustedPeerUpdate
import com.rootsync.android.engine.DestinationDirectoryState
import com.rootsync.android.engine.RootSyncEngine
import com.rootsync.android.engine.EngineResult
import com.rootsync.android.engine.RsyncItem
import com.rootsync.android.engine.SafeInput
import com.rootsync.android.engine.SyncPlan
import com.rootsync.android.engine.receiveDirectoryTimes
import com.rootsync.android.root.RootAuthorization
import com.rootsync.android.service.TransferForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
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
    private val _previewPlan = MutableStateFlow<SyncPlan?>(null)
    val previewPlan: StateFlow<SyncPlan?> = _previewPlan.asStateFlow()
    private var activeOperationJob: Job? = null
    private var remotePrepareJob: Job? = null
    private var activeRemotePrepareRequestId: String? = null
    @Volatile private var pauseRequested = false
    @Volatile private var lastTransferPersistMillis = 0L
    private var runtimeCleanupAttempted = false
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
    private fun strategyTaskActive(): Boolean = _state.value.isBusy ||
        _state.value.localTransferActive || remoteSessionDeviceId != null
    private var strategyClock = maxOf(preferences.getLong("strategyClock", 0L),
        _state.value.profiles.maxOfOrNull { maxOf(it.strategyRevision.counter,
            it.queuedStrategy?.revision?.counter ?: 0L, it.queuedRoleRevision?.counter ?: 0L) } ?: 0L)
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
            RootAuthorization.managerState.collect { name ->
                if (name != null) _state.update { state ->
                    state.copy(capabilities = state.capabilities.copy(checks = state.capabilities.checks.map {
                        if (it.name == "ROOT 管理器") it.copy(detail = name) else it
                    }))
                }
            }
        }
        viewModelScope.launch {
            discovery.cancelledPreparations.collect { (deviceId, requestId) ->
                if (remoteSessionDeviceId == deviceId && activeRemotePrepareRequestId == requestId) {
                    remotePrepareJob?.cancelAndJoin()
                    pendingDirectoryDecision?.cancel()
                    engine.cancel()
                    releaseRemoteSession(deviceId, "对方已取消服务准备")
                    _state.update { it.copy(isBusy = false, pendingDirectoryCreation = null, phase = "远端准备已取消") }
                }
            }
        }
        viewModelScope.launch {
            discovery.incompatibleDevices.collect { ids ->
                _state.update { it.copy(incompatibleDeviceIds = ids) }
            }
        }
        viewModelScope.launch {
            discovery.strategyAcknowledgements.collect { ack ->
                val profile = _state.value.profiles.firstOrNull { it.deviceId == ack.deviceId }
                if (profile != null && profile.strategyRevision == ack.revision) {
                    val updated = profile.copy(confirmedStrategyRevision = ack.revision)
                    persistStrategyProfile(updated)
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (!strategyTaskActive()) {
                    _state.value.profiles.toList().forEach { profile ->
                        profile.queuedStrategy?.takeIf {
                            it.revision >= (profile.queuedRoleRevision ?: StrategyRevision())
                        }?.let { applyRemoteStrategy(it) }
                        val latest = _state.value.profiles.firstOrNull { it.id == profile.id } ?: return@forEach
                        latest.queuedRole?.let { commitRole(latest, it, latest.queuedRoleRevision) }
                    }
                    _state.value.profiles.filterNot { it.deviceId.startsWith("manual:") }.forEach {
                        if (!it.strategyRevision.valid) proposeStrategy(it)
                        else sendStrategyProfile(it)
                    }
                }
                delay(5_000)
            }
        }
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
        refreshCapabilitiesInternal(forceRetry = false)
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
            destinationPath = resolvedDestination,
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

    fun refreshCapabilities() = refreshCapabilitiesInternal(forceRetry = true)

    private fun refreshCapabilitiesInternal(forceRetry: Boolean) {
        launchBusy("正在请求 ROOT 并检查能力") {
            _state.update { it.copy(isChecking = true, phase = "正在请求 ROOT 并检查能力") }
            appendLog("INFO", "开始 ROOT、内置 rsync 与目录检查")
            val before = _state.value
            val capabilities = engine.probe(
                before.sourcePath, before.destinationPath, forceRetry,
                onRootGranted = {
                    _state.update { it.copy(capabilities = it.capabilities.copy(rootGranted = true),
                        phase = "ROOT 已授权，正在检测内置引擎") }
                },
                onUpdate = { snapshot, phase ->
                    _state.update { it.copy(capabilities = snapshot, phase = phase) }
                },
                onLog = ::streamLog
            )
            // Publish all completed checks BEFORE cleanup, never hide readiness behind it.
            _state.update { it.copy(capabilities = capabilities) }
            if (capabilities.rootGranted && !runtimeCleanupAttempted) {
                runtimeCleanupAttempted = true
                _state.update { it.copy(phase = "引擎检测已结束，清理本应用遗留进程（最多2秒）") }
                val cleanup = engine.cleanupStaleRuntimeProcesses(::streamLog)
                appendLog(if (cleanup.success) "INFO" else "WARN", cleanup.summary)
            }
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
            previewReady = false
        )
    }
    fun setDestinationPath(value: String) = updateConfig { copy(destinationPath = value, previewReady = false) }
    fun setBidirectionalPath(value: String) = updateConfig {
        copy(sourcePath = value, previewReady = false)
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
        _previewPlan.value = null
        val current = _state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile == null) {
            updateConfig { copy(role = value, previewReady = false) }
            return
        }
        if (strategyTaskActive()) {
            strategyClock = maxOf(strategyClock, profile.strategyRevision.counter,
                profile.queuedStrategy?.revision?.counter ?: 0L) + 1
            persistStrategyProfile(profile.copy(queuedRole = value,
                queuedRoleRevision = StrategyRevision(strategyClock, deviceId)))
            return
        }
        commitRole(profile, value)
    }

    private fun commitRole(profile: PeerProfile, value: SyncRole, revision: StrategyRevision? = null) {
        val updated = profile.copy(role = value, queuedRole = null, queuedRoleRevision = null)
        if (!proposeStrategy(updated, revision)) return
        _state.update {
            if (it.selectedProfileId == profile.id) it.copy(role = value, previewReady = false) else it
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
        _previewPlan.value = null
        _state.update {
            it.block().let { updated ->
                if (profileDraft) updated.copy(hasUnsavedProfileChanges = true) else updated
            }
        }
        saveConfig()
    }

    fun saveCurrentProfile() {
        val current = _state.value
        if (strategyTaskActive()) {
            _state.update { it.copy(lastResult = "任务运行期间请等待结束后保存其他草稿；角色变更会排队") }
            return
        }
        val port = SafeInput.parsePort(current.portText)
        val validationError = when {
            !SafeInput.isValidIpv4(current.remoteHost) -> "请输入有效的远端 IPv4 地址"
            SafeInput.isLocalSelfTarget(current.remoteHost, current.localIp) ->
                "远端地址不能指向本机"
            port == null -> "端口必须位于 1024–65535"
            current.remoteSecret.length < SyncUiState.MIN_SECRET_LENGTH ->
                "密钥至少需要 ${SyncUiState.MIN_SECRET_LENGTH} 位"
            SafeInput.validateStoragePath(current.sourcePath) != null ->
                "发送目录无效：${SafeInput.validateStoragePath(current.sourcePath)}"
            SafeInput.validateStoragePath(current.destinationPath) != null ->
                "接收目录无效：${SafeInput.validateStoragePath(current.destinationPath)}"
            current.rangeMode == SyncRangeMode.SINCE && current.sinceEpochMillis == null ->
                "请选择同步起始时间"
            current.rangeMode == SyncRangeMode.SINCE &&
                current.sinceEpochMillis != null && current.sinceEpochMillis > System.currentTimeMillis() ->
                "同步起始时间不能晚于当前时间"
            else -> null
        }
        if (validationError != null) {
            appendLog("ERROR", validationError)
            _state.update { it.copy(phase = "策略未保存", lastResult = validationError) }
            return
        }
        when {
            port != null -> {
                val id = current.selectedProfileId ?: UUID.randomUUID().toString()
                val existing = current.profiles.firstOrNull { it.id == id }
                val profile = PeerProfile(
                    id = id,
                    deviceId = existing?.deviceId ?: "manual:${current.remoteHost}:$port",
                    name = current.profileName.ifBlank { "${current.remoteHost}:$port" },
                    host = current.remoteHost,
                    port = port,
                    secret = current.remoteSecret,
                    controlToken = existing?.controlToken.orEmpty(),
                    strategyRevision = existing?.strategyRevision ?: StrategyRevision(),
                    confirmedStrategyRevision = null,
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
        _previewPlan.value = null
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
                destinationPath = profile.destinationPath,
                transferRecord = profileRecord,
                previewReady = false,
                lastResult = "已切换到 ${profile.name}"
            )
        }
        saveConfig()
    }

    fun newProfile() {
        _previewPlan.value = null
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
        _previewPlan.value = null
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
            strategyRevision = StrategyRevision(),
            confirmedStrategyRevision = null,
            queuedStrategy = null,
            queuedRole = null,
            queuedRoleRevision = null,
            role = device.role,
            rangeMode = device.rangeMode,
            sinceEpochMillis = device.sinceEpochMillis,
            destinationPath = existing.destinationPath
        ) ?: PeerProfile(
            id = UUID.randomUUID().toString(),
            deviceId = device.deviceId,
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
            controlToken = device.controlToken,
            strategyRevision = StrategyRevision(),
            confirmedStrategyRevision = null,
            queuedStrategy = null,
            queuedRole = null,
            queuedRoleRevision = null,
            role = device.role,
            rangeMode = device.rangeMode,
            sinceEpochMillis = device.sinceEpochMillis,
            sourcePath = current.sourcePath,
            destinationPath = current.destinationPath
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
        val rotatedSecret = update.secret?.takeIf { it.length >= SyncUiState.MIN_SECRET_LENGTH && it != existing.secret }
        val updated = existing.copy(
            name = update.name,
            host = update.host,
            port = update.port,
            secret = rotatedSecret ?: existing.secret
        )
        if (updated == existing) return
        _state.update { state ->
            val selected = state.selectedProfileId == existing.id
            state.copy(
                profiles = state.profiles.map { if (it.id == existing.id) updated else it },
                profileName = if (selected) updated.name else state.profileName,
                remoteHost = if (selected) updated.host else state.remoteHost,
                portText = if (selected) updated.port.toString() else state.portText,
                remoteSecret = if (selected) updated.secret else state.remoteSecret,
                selectedProfileId = if (selected) updated.id else state.selectedProfileId,
                lastResult = if (rotatedSecret != null && !state.isBusy) "已更新 ${updated.name} 的连接密钥" else state.lastResult
            )
        }
        saveConfig()
    }

    private fun persistStrategyProfile(profile: PeerProfile): Boolean {
        val profiles = _state.value.profiles.map { if (it.id == profile.id) profile else it }
        // commit(), not apply(): a network ACK must never precede durable storage.
        val saved = preferences.edit().putString("profiles", encodeProfiles(profiles))
            .putLong("strategyClock", strategyClock).commit()
        if (!saved) {
            _state.update { it.copy(lastResult = "策略写入失败，请重试", previewReady = false) }
            return false
        }
        _state.update { it.copy(profiles = profiles) }
        return true
    }

    private fun proposeStrategy(profile: PeerProfile, queuedRevision: StrategyRevision? = null): Boolean {
        if (profile.deviceId.startsWith("manual:")) return persistStrategyProfile(profile)
        val revision = queuedRevision ?: StrategyRevision(
            maxOf(strategyClock, profile.strategyRevision.counter, profile.queuedStrategy?.revision?.counter ?: 0L) + 1, deviceId)
        strategyClock = maxOf(strategyClock, revision.counter)
        val updated = profile.copy(strategyRevision = revision,
            confirmedStrategyRevision = null, queuedStrategy = null)
        if (!persistStrategyProfile(updated)) return false
        sendStrategyProfile(updated)
        return true
    }

    private fun sendStrategyProfile(profile: PeerProfile) {
        if (profile.deviceId.startsWith("manual:") || !profile.strategyRevision.valid) return
        discovery.sendStrategy(
            host = profile.host, role = profile.role, rangeMode = profile.rangeMode,
            sinceEpochMillis = profile.sinceEpochMillis, expectedDeviceId = profile.deviceId,
            revision = profile.strategyRevision
        )
    }

    private fun publishCurrentStrategy() {
        val current = _state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        proposeStrategy(profile)
    }

    private fun applyRemoteStrategy(update: StrategyUpdate) {
        if (!update.revision.valid) return
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == update.deviceId } ?: return
        strategyClock = maxOf(strategyClock, update.revision.counter)
        if (update.revision < (existing.queuedStrategy?.revision ?: StrategyRevision())) return
        when (StrategyLogic.receive(existing, update)) {
            StrategyLogic.Decision.STALE -> { sendStrategyProfile(existing); return }
            StrategyLogic.Decision.INVALID -> return
            StrategyLogic.Decision.DUPLICATE -> {
                if (existing.confirmedStrategyRevision == update.revision && existing.queuedStrategy == null) {
                    discovery.acknowledgeStrategy(update)
                    return
                }
            }
            StrategyLogic.Decision.APPLY -> Unit
        }
        if (strategyTaskActive()) {
            if (update.revision > existing.strategyRevision &&
                update.revision >= (existing.queuedStrategy?.revision ?: StrategyRevision())) {
                persistStrategyProfile(existing.copy(queuedStrategy = update))
            }
            // Retransmission after the task completes will receive the durable ACK.
            return
        }
        val updated = StrategyLogic.apply(existing, update)
        if (!persistStrategyProfile(updated)) return
        _state.update { state ->
            val selected = state.selectedProfileId == existing.id
            // Preserve all local paths and unrelated drafts. Only untouched strategy fields follow.
            state.copy(
                role = if (selected) updated.role else state.role,
                rangeMode = if (selected && state.rangeMode == existing.rangeMode) updated.rangeMode else state.rangeMode,
                sinceEpochMillis = if (selected && state.sinceEpochMillis == existing.sinceEpochMillis)
                    updated.sinceEpochMillis else state.sinceEpochMillis,
                previewReady = if (selected) false else state.previewReady
            )
        }
        if (_state.value.selectedProfileId == existing.id) _previewPlan.value = null
        discovery.acknowledgeStrategy(update)
    }

    /** Call before starting a task; pending/offline/old-protocol strategies cannot run. */
    fun isStrategyConfirmed(profile: PeerProfile): Boolean =
        profile.deviceId.startsWith("manual:") ||
            (profile.deviceId !in _state.value.incompatibleDeviceIds &&
                profile.queuedStrategy == null && profile.queuedRole == null &&
                profile.strategyRevision.valid && profile.strategyRevision == profile.confirmedStrategyRevision)

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

        if (!isStrategyConfirmed(profile) || request.strategyRevision != profile.strategyRevision ||
            request.role.opposite() != profile.role || request.rangeMode != profile.rangeMode ||
            (profile.rangeMode == SyncRangeMode.SINCE && (request.rangeMode == SyncRangeMode.SINCE && request.sinceEpochMillis != profile.sinceEpochMillis))) {
            remoteSessionDeviceId = null
            discovery.answerSyncPreparation(request, false, "两端策略尚未确认一致，请等待策略同步", port)
            sendStrategyProfile(profile)
            return
        }
        val localRole = request.role.opposite()
        val activeProfile = _state.value.profiles.first { it.deviceId == request.deviceId }
        val rsync = _state.value.capabilities.rsyncPath
        if (rsync == null) {
            releaseRemoteSession(request.deviceId, "远端 ROOT/rsync 能力尚未就绪")
            discovery.answerSyncPreparation(request, false, "远端 ROOT/rsync 能力尚未就绪", port)
            return
        }

        val receivePath = if (localRole == SyncRole.BIDIRECTIONAL) activeProfile.sourcePath else activeProfile.destinationPath
        if (localRole != SyncRole.SEND_ONLY && !request.isPreview) {
            val destinationCheck = engine.inspectDestinationDirectory(receivePath)
            when (destinationCheck.state) {
                DestinationDirectoryState.READY -> Unit
                DestinationDirectoryState.MISSING -> {
                    val allowed = awaitDirectoryCreationDecision(request, receivePath)
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
                    val createResult = engine.createDestinationDirectory(receivePath)
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

        _state.update { it.copy(isBusy = true, phase = "正在为 ${request.name} 准备服务端",
            scanDetail = "正在准备目录扫描", scanUpdatedMillis = SystemClock.elapsedRealtime()) }
        val foregroundReadyBeforeScan = TransferForegroundService.update(
            context = getApplication(), title = "RootSync 正在准备目录清单",
            detail = "${request.name}：只读扫描中，可由发起端取消", eta = null, progress = null, incoming = true)
        if (!foregroundReadyBeforeScan) {
            releaseRemoteSession(request.deviceId, "请打开本机 RootSync 后重试")
            _state.update { it.copy(isBusy = false) }
            discovery.answerSyncPreparation(request, false, "系统不允许后台启动，请打开本机 RootSync", port)
            return
        }
        val sessionSecret = engine.generateSecret()
        val result = try {
            engine.startServer(
                rsyncPath = rsync,
                sourcePath = activeProfile.sourcePath,
                destinationPath = receivePath,
                port = port,
                secret = sessionSecret,
                onLog = ::streamLog,
                mode = localRole,
                rangeMode = request.rangeMode,
                sinceEpochMillis = request.sinceEpochMillis,
                untilEpochMillis = request.untilEpochMillis,
                allowCreateDestination = false,
                previewOnly = request.isPreview,
                strictChecksum = request.strictChecksum
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val availableBytes = engine.availableStorageBytes(if (profile.role == SyncRole.BIDIRECTIONAL) profile.sourcePath else profile.destinationPath)
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
        activeRemotePrepareRequestId = request.requestId
        _state.update { it.copy(scanDetail = "正在准备目录扫描", scanUpdatedMillis = SystemClock.elapsedRealtime()) }
        remotePrepareJob = viewModelScope.launch {
            val heartbeat = launch {
                while (isActive) {
                    discovery.answerSyncPreparationWaiting(request,
                        if (_state.value.pendingDirectoryCreation != null) "等待接收端确认创建目录"
                        else "${_state.value.scanDetail} · 扫描状态距今 ${((SystemClock.elapsedRealtime() - _state.value.scanUpdatedMillis) / 1000).coerceAtLeast(0)} 秒")
                    delay(2_000)
                }
            }
            try {
                handleSyncPrepareRequest(request)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    engine.cancel()
                    engine.stopServer(::streamLog)
                }
                releaseRemoteSession(request.deviceId, "服务准备已取消")
                _state.update { it.copy(isBusy = false, serverRunning = false, pendingDirectoryCreation = null) }
                throw cancelled
            } catch (error: Exception) {
                releaseRemoteSession(request.deviceId, "服务准备失败")
                _state.update { it.copy(isBusy = false, pendingDirectoryCreation = null) }
                discovery.answerSyncPreparation(request, false, error.message ?: "目录准备失败",
                    SafeInput.parsePort(_state.value.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT)
            } finally {
                heartbeat.cancel()
                if (remoteSessionDeviceId != request.deviceId) activeRemotePrepareRequestId = null
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
        if (isPreview) return SafeInput.validateStoragePath(path) == null
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
        if (update.taskId != activeRemotePrepareRequestId) return
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
        if (taskId != activeRemotePrepareRequestId || remoteSessionDeviceId != deviceId) return
        val existing = _state.value.remoteActivity
        if (existing == null || existing.taskId != taskId) {
            val name = _state.value.profiles.firstOrNull { it.deviceId == deviceId }?.name ?: "远端设备"
            showRemoteActivity(deviceId, name, type, taskId)
        }
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
        val current = _state.value
        val record = current.transferRecord?.takeIf { it.status == TransferStatus.PAUSED } ?: return
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile != null && !recordMatchesProfile(record, profile)) {
            _state.update { it.copy(lastResult = "旧记录不属于当前设备，请重新选择") }
            return
        }
        // Resume fragments, never restore an obsolete role/path over current confirmed settings.
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
        val draft = _state.value
        // Bidirectional uses one root, without changing the saved receive-only path.
        val current = if (draft.role == SyncRole.BIDIRECTIONAL) draft.copy(destinationPath = draft.sourcePath) else draft
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        val rsync = current.capabilities.rsyncPath
        val port = SafeInput.parsePort(current.portText)
        val localPath = if (current.role == SyncRole.RECEIVE_ONLY) current.destinationPath else current.sourcePath
        val validation = when {
            rsync == null -> "内置 rsync 不可执行"
            !SafeInput.isValidIpv4(current.remoteHost) -> "请输入有效的远端 IPv4 地址"
            SafeInput.isLocalSelfTarget(current.remoteHost, current.localIp) -> "远端地址指向本机，已阻止递归同步"
            port == null -> "端口必须位于 1024–65535"
            current.remoteSecret.length < SyncUiState.MIN_SECRET_LENGTH -> "请完成配对或输入远端密钥"
            current.hasUnsavedProfileChanges -> "请先保存本机目录设置"
            profile != null && !isStrategyConfirmed(profile) -> "两端策略尚未确认，请等待对方上线并确认"
            current.rangeMode == SyncRangeMode.SINCE && current.sinceEpochMillis == null -> "请选择同步起始时间"
            current.rangeMode == SyncRangeMode.SINCE && (current.sinceEpochMillis ?: 0) > System.currentTimeMillis() -> "同步起始时间不能晚于当前时间"
            else -> SafeInput.validateStoragePath(localPath)
        }
        if (validation != null || rsync == null || port == null) {
            _state.update { it.copy(lastResult = validation, previewReady = false,
                previewStatusText = if (dryRun) "无法扫描：$validation" else it.previewStatusText) }
            appendLog("ERROR", validation ?: "配置无效")
            return
        }
        val action = current.role.label
        launchBusy(if (dryRun) "正在只读扫描双方差异" else "正在准备$action", localTransfer = true) {
            _previewPlan.value = null
            pauseRequested = false
            localTransferItemCount = 0
            lastTransferItemUiMillis = 0L
            pendingUploadedBytes = 0L
            pendingDownloadedBytes = 0L
            lastTransferBytesUiMillis = 0L
            previewUploadBytes = 0L
            previewDownloadBytes = 0L
            val until = System.currentTimeMillis()
            val record = if (dryRun) null else (resumeRecord ?: TransferRecord(
                id = UUID.randomUUID().toString(), profileId = current.selectedProfileId,
                deviceId = profile?.deviceId, peerName = current.profileName.ifBlank { current.remoteHost },
                host = current.remoteHost, port = port, secret = current.remoteSecret,
                role = current.role, rangeMode = current.rangeMode, sinceEpochMillis = current.sinceEpochMillis,
                sourcePath = current.sourcePath, destinationPath = current.destinationPath,
                status = TransferStatus.RUNNING, message = "正在准备同步"
            )).copy(status = TransferStatus.RUNNING, host = current.remoteHost, port = port,
                role = current.role, sourcePath = current.sourcePath, destinationPath = current.destinationPath,
                rangeMode = current.rangeMode, sinceEpochMillis = current.sinceEpochMillis,
                secret = current.remoteSecret, peerName = current.profileName.ifBlank { current.remoteHost },
                progress = 0f, updatedAtMillis = until, message = "正在重新校验双方目录")
            _state.update { it.copy(progress = if (dryRun) null else 0f, isPreviewing = dryRun,
                previewStartedMillis = SystemClock.elapsedRealtime(), scanUpdatedMillis = SystemClock.elapsedRealtime(),
                scanDetail = "等待对方准备只读目录清单", previewFraction = null,
                previewReady = false, lastResult = null, totalSyncBytes = 0, uploadedBytes = 0, downloadedBytes = 0,
                estimatedCompletionTime = if (dryRun) null else "正在比较双方完整清单…",
                transferSpeedBytesPerSecond = 0, transferPanelTitle = transferPanelTitle(dryRun, current.role),
                transferFolders = emptyList(), transferFoldersTruncated = false, currentTransferFolder = null,
                transferItemCount = 0, transferRecord = if (dryRun) it.transferRecord else record,
                previewStatusText = if (dryRun) "正在准备只读扫描，不创建或修改同步目录…" else it.previewStatusText) }
            if (!dryRun) persistTransferRecord()
            val foreground = TransferForegroundService.update(getApplication(),
                if (dryRun) "RootSync 正在扫描差异" else "RootSync 正在准备同步",
                "正在准备目录清单", null, null)
            check(foreground) { "系统未允许后台运行，请打开 RootSync 后重试" }
            try {
                if (!dryRun && current.role != SyncRole.SEND_ONLY &&
                    !ensureLocalDestinationReady(current.profileName, current.destinationPath, false)) {
                    markTransferPaused("接收目录尚未确认创建")
                    return@launchBusy
                }
                val endpoint = ensureRemoteServerReady(current, port, until, dryRun)
                if (endpoint == null) {
                    if (!dryRun) markTransferPaused(_state.value.lastResult ?: "远端暂时不可用")
                    else _state.update { it.copy(isPreviewing = false, previewStatusText = "扫描未完成：${it.lastResult}") }
                    return@launchBusy
                }
                val type = if (dryRun) SyncActivityType.PREVIEW else SyncActivityType.TRANSFER
                val taskId = endpoint.requestId ?: UUID.randomUUID().toString()
                var remoteItems = 0
                var lastRemoteItem = 0L
                val heartbeat = viewModelScope.launch {
                    while (isActive) {
                        discovery.sendSyncActivity(endpoint.host, type, active = true, taskId = taskId)
                        delay(REMOTE_ACTIVITY_HEARTBEAT_MS)
                    }
                }
                var etaJob: Job? = null
                val result: EngineResult
                try {
                    _state.update { it.copy(phase = "正在比较双方目录清单") }
                    val plan = engine.createSyncPlan(rsync, endpoint.host, endpoint.port, endpoint.secret,
                        localPath, current.role, current.strictContentCheck, current.rangeMode,
                        current.sinceEpochMillis, until, ::streamLog)
                    _previewPlan.value = plan
                    _state.update { it.copy(totalSyncBytes = safeAddBytes(plan.uploadBytes, plan.downloadBytes),
                        previewStatusText = if (dryRun) plan.summary else it.previewStatusText) }
                    if (dryRun) {
                        remoteItems = plan.items.size
                        result = EngineResult(true, "只读扫描完成：${plan.summary}")
                    } else if (plan.conflicts > 0) {
                        result = EngineResult(false, "发现 ${plan.conflicts} 个冲突，未传输任何文件；请在完整计划中查看并处理后重扫")
                    } else {
                        var preflight = EngineResult(true, "空间检查通过")
                        if (plan.downloadBytes > 0) {
                            val available = engine.availableStorageBytes(current.destinationPath)
                            val needed = requiredStorageBytes(plan.downloadBytes)
                            if (available != null && available < needed) preflight = EngineResult(false,
                                "本机空间不足：需要 ${formatBytes(needed)}，可用 ${formatBytes(available)}")
                        }
                        if (preflight.success && plan.uploadBytes > 0 && profile != null && !profile.deviceId.startsWith("manual:")) {
                            val remote = discovery.requestRemoteStorageCheck(endpoint.host, plan.uploadBytes)
                            if (remote == null || !remote.ready) preflight = EngineResult(false,
                                remote?.message ?: "对方未响应接收空间检查")
                        }
                        resetTransferSpeedTracking()
                        _state.update { it.copy(phase = "正在执行$action", estimatedCompletionTime =
                            if (plan.uploadBytes + plan.downloadBytes == 0L) "无需传输内容或仅更新属性" else "正在采集近 10 秒传输速度…") }
                        etaJob = viewModelScope.launch { while (isActive) {
                            delay(ETA_UPDATE_INTERVAL_MS); refreshTransferEta(action)
                        } }
                        fun item(item: RsyncItem, upload: Boolean) {
                            updateTransferItem(item, false, current.role, if (upload) "发送" else "接收")
                            remoteItems++
                            val now = System.currentTimeMillis()
                            if (remoteItems == 1 || now - lastRemoteItem >= TRANSFER_ITEM_REFRESH_MS) {
                                lastRemoteItem = now
                                discovery.sendSyncItem(endpoint.host, type, taskId, item.relativePath, remoteItems)
                            }
                        }
                        var transfer = preflight
                        if (transfer.success && plan.receiveItems.isNotEmpty() && !pauseRequested) {
                            transfer = engine.pull(rsyncPath = rsync, host = endpoint.host, port = endpoint.port,
                                destinationPath = current.destinationPath, secret = endpoint.secret,
                                rangeMode = current.rangeMode, sinceEpochMillis = current.sinceEpochMillis,
                                untilEpochMillis = until, bidirectional = current.role == SyncRole.BIDIRECTIONAL,
                                dryRun = false, strictChecksum = current.strictContentCheck, plannedItems = plan.receiveItems,
                                directoryTimes = plan.receiveDirectoryTimes(),
                                onLog = ::streamLog, onProgress = { updateTransferProgress(it, false, action) },
                                onItem = { item(it, false) },
                                onTransferredBytes = { updateTransferBytes(false, it, action) })
                        }
                        if (transfer.success && plan.sendItems.isNotEmpty() && !pauseRequested) {
                            transfer = engine.push(rsyncPath = rsync, host = endpoint.host, port = endpoint.port,
                                sourcePath = current.sourcePath, secret = endpoint.secret,
                                rangeMode = current.rangeMode, sinceEpochMillis = current.sinceEpochMillis,
                                untilEpochMillis = until, bidirectional = current.role == SyncRole.BIDIRECTIONAL,
                                dryRun = false, strictChecksum = current.strictContentCheck, plannedItems = plan.sendItems,
                                onLog = ::streamLog, onProgress = { updateTransferProgress(it, false, action) },
                                onItem = { item(it, true) },
                                onTransferredBytes = { updateTransferBytes(true, it, action) })
                        }
                        result = if (transfer.success) EngineResult(true,
                            "同步完成：${plan.summary}；零删除，本次实际传输文件校验通过") else transfer
                    }
                } finally {
                    etaJob?.cancel()
                    heartbeat.cancel()
                    withContext(NonCancellable) {
                        engine.cancel() // Reap ROOT children before waiting for the finish ACK.
                        discovery.sendSyncActivity(endpoint.host, type, active = false, taskId = taskId, totalItems = remoteItems)
                    }
                    if (!dryRun) flushTransferBytes(action, force = true)
                }
                if (!dryRun && pauseRequested) {
                    markTransferPaused("用户已暂停，临时分片保留")
                    return@launchBusy
                }
                _state.update { it.copy(isPreviewing = false, previewReady = dryRun && result.success,
                    previewStatusText = if (dryRun) result.summary else it.previewStatusText,
                    transferItemCount = if (dryRun) (_previewPlan.value?.items?.size ?: 0) else localTransferItemCount,
                    progress = if (dryRun) null else if (result.success) 1f else it.progress,
                    estimatedCompletionTime = if (!dryRun && result.success) "已完成" else it.estimatedCompletionTime,
                    lastResult = result.summary, phase = result.summary,
                    transferRecord = if (dryRun) it.transferRecord else it.transferRecord?.copy(
                        status = if (result.success) TransferStatus.COMPLETED else TransferStatus.PAUSED,
                        progress = if (result.success) 1f else it.transferRecord.progress,
                        updatedAtMillis = System.currentTimeMillis(), message = result.summary)) }
                if (!dryRun) {
                    persistTransferRecord()
                    if (result.success) TransferForegroundService.notifyCompleted(getApplication(), "RootSync 同步完成", result.summary)
                }
                appendLog(if (result.success) "OK" else "ERROR", result.summary)
            } finally { TransferForegroundService.stop(getApplication()) }
        }
    }

    fun pauseTransfer() {
        if (!_state.value.localTransferActive) return
        if (_state.value.isPreviewing) { cancel(); return }
        pauseRequested = true
        activeOperationJob?.cancel(CancellationException("用户暂停任务"))
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
            strategyRevision = profile.strategyRevision,
            strictChecksum = current.strictContentCheck,
            expectedDeviceId = profile.deviceId,
            onWaiting = { status ->
                val waitingText = "${profile.name} 正在准备：$status"
                _state.update { state ->
                    state.copy(
                        phase = "${profile.name} 正在准备目录清单",
                        previewStatusText = if (isPreview) waitingText else state.previewStatusText,
                        scanDetail = waitingText, previewFraction = null, scanUpdatedMillis = SystemClock.elapsedRealtime(),
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
        return RemoteEndpoint(prepared.host, preparedPort, preparedSecret, prepared.requestId)
    }

    fun cancel() {
        if (!_state.value.localTransferActive) {
            appendLog("WARN", "当前没有可由本机取消的任务")
            return
        }
        _state.update { it.copy(phase = "正在取消并清理 ROOT 进程…") }
        pendingDirectoryDecision?.cancel()
        activeOperationJob?.cancel(CancellationException("用户取消任务"))
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
        activeOperationJob = viewModelScope.launch {
            _state.update {
                it.copy(isBusy = true, localTransferActive = localTransfer, phase = phase)
            }
            try {
                block()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { engine.cancel() }
                val wasPreview = _state.value.isPreviewing
                if (!wasPreview && _state.value.transferRecord?.status == TransferStatus.RUNNING) {
                    markTransferPaused(if (pauseRequested) "用户已暂停，临时分片保留" else "任务已取消，临时分片保留")
                }
                _state.update { it.copy(isPreviewing = false, previewReady = false,
                    pendingDirectoryCreation = null, phase = if (pauseRequested) "任务已暂停" else "任务已取消",
                    previewStatusText = if (wasPreview) "扫描已取消，未产生完整计划" else it.previewStatusText) }
                if (wasPreview) _previewPlan.value = null
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                appendLog("ERROR", message)
                if (_state.value.transferRecord?.status == TransferStatus.RUNNING &&
                    !_state.value.isPreviewing
                ) {
                    markTransferPaused("$message；传输已自动暂停")
                } else {
                    _state.update { it.copy(lastResult = message, phase = "操作失败", isPreviewing = false,
                        previewStatusText = if (it.isPreviewing) "扫描失败：$message" else it.previewStatusText) }
                }
            } finally {
                _state.update { it.copy(isBusy = false, localTransferActive = false, isChecking = false) }
                TransferForegroundService.stop(getApplication())
                activeOperationJob = null
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
        ScanProgress.parse(message)?.let { scan ->
            _state.update { state -> state.copy(scanDetail = scan.detail,
                scanUpdatedMillis = SystemClock.elapsedRealtime(), previewFraction = scan.fraction,
                phase = if (state.isBusy) scan.detail else state.phase,
                previewStatusText = if (state.isPreviewing) scan.detail else state.previewStatusText) }
            // High-frequency counters belong to progress state, not the persistent log.
            if (!message.startsWith("PREVIEW_STAGE=")) return
        }
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

    private fun encodeRevision(revision: StrategyRevision): JSONObject = JSONObject()
        .put("counter", revision.counter).put("writerId", revision.writerId)

    private fun decodeRevision(value: JSONObject?): StrategyRevision? = value?.let {
        StrategyRevision(it.optLong("counter"), it.optString("writerId")).takeIf { revision -> revision.valid }
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
                put("strategyRevision", encodeRevision(profile.strategyRevision))
                profile.confirmedStrategyRevision?.let { put("confirmedStrategyRevision", encodeRevision(it)) }
                profile.queuedRole?.let { put("queuedRole", it.name) }
                profile.queuedRoleRevision?.let { put("queuedRoleRevision", encodeRevision(it)) }
                profile.queuedStrategy?.let { queued ->
                    put("queuedStrategy", JSONObject().apply {
                        put("deviceId", queued.deviceId); put("name", queued.name); put("host", queued.host)
                        put("secret", queued.secret); put("role", queued.role.name)
                        put("rangeMode", queued.rangeMode.name); put("sinceEpochMillis", queued.sinceEpochMillis)
                        put("requestId", queued.requestId); put("revision", encodeRevision(queued.revision))
                    })
                }
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
                            strategyRevision = decodeRevision(item.optJSONObject("strategyRevision")) ?: StrategyRevision(),
                            confirmedStrategyRevision = decodeRevision(item.optJSONObject("confirmedStrategyRevision")),
                            queuedRoleRevision = decodeRevision(item.optJSONObject("queuedRoleRevision")),
                            queuedRole = item.optString("queuedRole").takeIf { it.isNotBlank() }?.let { SyncRole.valueOf(it) },
                            queuedStrategy = item.optJSONObject("queuedStrategy")?.let { queued ->
                                StrategyUpdate(queued.getString("deviceId"), queued.getString("name"),
                                    queued.getString("host"), queued.getString("secret"),
                                    SyncRole.valueOf(queued.getString("role")),
                                    SyncRangeMode.valueOf(queued.getString("rangeMode")),
                                    queued.optLong("sinceEpochMillis", -1L).takeIf { it > 0 },
                                    queued.getString("requestId"),
                                    decodeRevision(queued.optJSONObject("revision")) ?: StrategyRevision())
                            },
                            role = role,
                            rangeMode = rangeMode,
                            sinceEpochMillis = since,
                            sourcePath = sourcePath,
                            destinationPath = migrateBiliPath(
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

    private data class RemoteEndpoint(val host: String, val port: Int, val secret: String,
        val requestId: String? = null)
    private data class PlannedTransfer(
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val result: EngineResult = EngineResult(true, "同步总量统计完成")
    )
    private data class TransferSpeedSample(val timeMillis: Long, val totalBytes: Long)
}

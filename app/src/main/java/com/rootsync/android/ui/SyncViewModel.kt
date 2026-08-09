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
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.SyncUiState
import com.rootsync.android.engine.RootSyncEngine
import com.rootsync.android.engine.SafeInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    private val discovery = LanDiscoveryManager(
        context = application,
        deviceId = deviceId,
        deviceName = deviceName,
        localPort = { SafeInput.parsePort(_state.value.serverPortText) ?: SyncUiState.DEFAULT_RSYNC_PORT },
        localSecret = { _state.value.serverSecret },
        onLog = { message -> appendLog("LAN", message) }
    )

    init {
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
            mirror = selected?.mirror ?: preferences.getBoolean("mirror", true),
            profiles = profiles,
            selectedProfileId = selected?.id,
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
    fun setRole(value: SyncRole) = updateConfig { copy(role = value, previewReady = false) }
    fun setMirror(value: Boolean) = updateConfig { copy(mirror = value, previewReady = false) }

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
                mirror = mirror,
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
                    mirror = current.mirror,
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
                mirror = profile.mirror,
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
                mirror = true,
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
            it.copy(
                profiles = it.profiles.filterNot { profile -> profile.id == id },
                selectedProfileId = null,
                profileName = "新设备",
                remoteHost = "",
                remoteSecret = "",
                previewReady = false,
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
        discovery.requestPair(device)
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

    private fun PairRequest.toAccepted() = PairAccepted(deviceId, name, host, port, secret)

    private fun upsertPairedDevice(device: PairAccepted) {
        val current = _state.value
        val existing = current.profiles.firstOrNull { it.deviceId == device.deviceId }
        val profile = existing?.copy(
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret
        ) ?: PeerProfile(
            id = UUID.randomUUID().toString(),
            deviceId = device.deviceId,
            name = device.name,
            host = device.host,
            port = device.port,
            secret = device.secret,
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
                mirror = profile.mirror,
                sourcePath = profile.sourcePath,
                destinationPath = profile.destinationPath,
                previewReady = false
            )
        }
        saveConfig()
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
            putBoolean("mirror", value.mirror)
            putString("profiles", encodeProfiles(value.profiles))
            putString("selectedProfileId", value.selectedProfileId)
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
        if (_state.value.mirror && !_state.value.previewReady) {
            appendLog("WARN", "镜像删除前必须先完成差异预览")
            _state.update { it.copy(lastResult = "请先执行镜像预览") }
            return
        }
        runTransfer(dryRun = false)
    }

    private fun runTransfer(dryRun: Boolean) {
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
            _state.update { it.copy(progress = if (dryRun) null else 0f, lastResult = null) }
            val result = if (current.role == SyncRole.SEND_ONLY) {
                engine.push(
                    rsyncPath = rsync,
                    host = current.remoteHost,
                    port = port,
                    sourcePath = current.sourcePath,
                    secret = current.remoteSecret,
                    mirror = current.mirror,
                    dryRun = dryRun,
                    onLog = ::streamLog,
                    onProgress = { progress -> _state.update { it.copy(progress = progress) } }
                )
            } else {
                engine.pull(
                    rsyncPath = rsync,
                    host = current.remoteHost,
                    port = port,
                    destinationPath = current.destinationPath,
                    secret = current.remoteSecret,
                    mirror = current.mirror,
                    dryRun = dryRun,
                    onLog = ::streamLog,
                    onProgress = { progress -> _state.update { it.copy(progress = progress) } }
                )
            }
            _state.update {
                it.copy(
                    previewReady = dryRun && result.success,
                    progress = if (!dryRun && result.success) 1f else it.progress,
                    lastResult = result.summary,
                    phase = result.summary
                )
            }
            appendLog(if (result.success) "OK" else "ERROR", result.summary)
        }
    }

    fun cancel() {
        engine.cancel()
        _state.update { it.copy(isBusy = false, phase = "任务已取消", lastResult = "任务已取消") }
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
                _state.update { it.copy(lastResult = message, phase = "操作失败") }
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
                put("mirror", profile.mirror)
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
                            mirror = item.optBoolean("mirror", true),
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
}

package com.rootsync.android.domain

enum class SyncRole(val label: String) {
    SEND_ONLY("只发送"),
    RECEIVE_ONLY("只接收");

    fun opposite(): SyncRole = when (this) {
        SEND_ONLY -> RECEIVE_ONLY
        RECEIVE_ONLY -> SEND_ONLY
    }
}

enum class CheckState {
    CHECKING,
    PASS,
    WARNING,
    FAIL
}

data class CapabilityCheck(
    val name: String,
    val state: CheckState,
    val detail: String
)

data class DeviceCapabilities(
    val rootGranted: Boolean = false,
    val rsyncPath: String? = null,
    val rsyncVersion: String? = null,
    val syncMetaReady: Boolean = false,
    val detectedMediaPath: String? = null,
    val checks: List<CapabilityCheck> = emptyList()
)

data class PeerProfile(
    val id: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int = SyncUiState.DEFAULT_RSYNC_PORT,
    val secret: String,
    val role: SyncRole = SyncRole.RECEIVE_ONLY,
    val sourcePath: String = SyncUiState.DEFAULT_BILI_PATH,
    val destinationPath: String = SyncUiState.DEFAULT_BILI_PATH
)

data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenMillis: Long = System.currentTimeMillis()
)

data class PairRequest(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String,
    val role: SyncRole
)

data class PairAccepted(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String,
    val role: SyncRole
)

data class StrategyUpdate(
    val deviceId: String,
    val name: String,
    val host: String,
    val secret: String,
    val role: SyncRole
)

data class SyncPrepareRequest(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val secret: String,
    val role: SyncRole
)

data class SyncPrepareResult(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val ready: Boolean,
    val message: String,
    val secret: String
)

data class LogEntry(
    val time: String,
    val level: String,
    val message: String
)

data class SyncUiState(
    val isChecking: Boolean = true,
    val isBusy: Boolean = false,
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    val localIp: String = "未连接 Wi-Fi",
    val deviceName: String = "Android 设备",
    val profileName: String = "手动设备",
    val remoteHost: String = "",
    val portText: String = DEFAULT_RSYNC_PORT.toString(),
    val serverPortText: String = DEFAULT_RSYNC_PORT.toString(),
    val sourcePath: String = DEFAULT_BILI_PATH,
    val destinationPath: String = DEFAULT_BILI_PATH,
    val serverSecret: String = "",
    val remoteSecret: String = "",
    val role: SyncRole = SyncRole.RECEIVE_ONLY,
    val profiles: List<PeerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val pendingPairRequest: PairRequest? = null,
    val serverRunning: Boolean = false,
    val phase: String = "等待检查",
    val progress: Float? = null,
    val previewReady: Boolean = false,
    val lastResult: String? = null,
    val logs: List<LogEntry> = emptyList()
) {
    val canOperate: Boolean
        get() = capabilities.rootGranted && capabilities.rsyncPath != null && !isBusy

    companion object {
        const val DEFAULT_RSYNC_PORT = 8873
        const val MIN_SECRET_LENGTH = 6
        const val DEFAULT_BILI_PATH =
            "/storage/emulated/0/Android/data/tv.danmaku.bili/download"
        const val LEGACY_BILI_PATH =
            "/storage/emulated/0/Android/data/com.danmaku.bili/download"
    }
}

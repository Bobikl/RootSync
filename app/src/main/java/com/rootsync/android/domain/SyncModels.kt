package com.rootsync.android.domain

enum class SyncRole(val label: String) {
    SEND_ONLY("只发送"),
    RECEIVE_ONLY("只接收"),
    BIDIRECTIONAL("双向同步");

    fun opposite(): SyncRole = when (this) {
        SEND_ONLY -> RECEIVE_ONLY
        RECEIVE_ONLY -> SEND_ONLY
        BIDIRECTIONAL -> BIDIRECTIONAL
    }
}

enum class SyncRangeMode(val label: String) {
    ALL("全部内容"),
    SINCE("指定时间至今")
}

enum class SyncActivityType {
    PREVIEW,
    TRANSFER
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

data class StrategyRevision(val counter: Long = 0, val writerId: String = "") : Comparable<StrategyRevision> {
    override fun compareTo(other: StrategyRevision): Int =
        compareValuesBy(this, other, StrategyRevision::counter, StrategyRevision::writerId)
    fun next(writer: String): StrategyRevision = StrategyRevision(Math.addExact(counter, 1L), writer)
    val valid: Boolean get() = counter > 0 && counter < Long.MAX_VALUE && writerId.isNotBlank()
}

enum class StrategyStatus { UNPAIRED, PENDING, CONFIRMED, UPGRADE_REQUIRED }

data class StrategyAcknowledgement(val deviceId: String, val revision: StrategyRevision)

data class PeerProfile(
    val id: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int = SyncUiState.DEFAULT_RSYNC_PORT,
    val secret: String,
    val controlToken: String = "",
    val strategyRevision: StrategyRevision = StrategyRevision(),
    val confirmedStrategyRevision: StrategyRevision? = null,
    val queuedStrategy: StrategyUpdate? = null,
    val queuedRole: SyncRole? = null,
    val queuedRoleRevision: StrategyRevision? = null,
    val role: SyncRole = SyncRole.RECEIVE_ONLY,
    val rangeMode: SyncRangeMode = SyncRangeMode.ALL,
    val sinceEpochMillis: Long? = null,
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
    val controlToken: String,
    val role: SyncRole,
    val rangeMode: SyncRangeMode,
    val sinceEpochMillis: Long?
)

data class PairAccepted(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String,
    val controlToken: String,
    val role: SyncRole,
    val rangeMode: SyncRangeMode,
    val sinceEpochMillis: Long?
)

data class TrustedPeerUpdate(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String? = null
)

data class StrategyUpdate(
    val deviceId: String,
    val name: String,
    val host: String,
    val secret: String,
    val role: SyncRole,
    val rangeMode: SyncRangeMode,
    val sinceEpochMillis: Long?,
    val requestId: String = "",
    val revision: StrategyRevision = StrategyRevision()
)

data class SyncPrepareRequest(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val secret: String,
    val role: SyncRole,
    val rangeMode: SyncRangeMode,
    val sinceEpochMillis: Long?,
    val untilEpochMillis: Long,
    val isPreview: Boolean,
    val strategyRevision: StrategyRevision = StrategyRevision(),
    val strictChecksum: Boolean = false
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

data class RemoteStorageCheckRequest(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val expectedDownloadBytes: Long
)

data class RemoteStorageCheckResult(
    val requestId: String,
    val deviceId: String,
    val ready: Boolean,
    val message: String,
    val availableBytes: Long
)

data class DirectoryCreationPrompt(
    val deviceId: String,
    val deviceName: String,
    val path: String,
    val isPreview: Boolean,
    val strategyRevision: StrategyRevision = StrategyRevision(),
    val strictChecksum: Boolean = false
)

data class SyncActivityUpdate(
    val deviceId: String,
    val name: String,
    val host: String,
    val secret: String,
    val type: SyncActivityType,
    val active: Boolean,
    val taskId: String = "",
    val itemPath: String? = null,
    val itemIndex: Int = 0,
    val totalItems: Int = 0
)

data class RemoteSyncActivity(
    val deviceId: String,
    val name: String,
    val type: SyncActivityType,
    val taskId: String = "",
    val startedAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis(),
    val finished: Boolean = false
)

data class LogEntry(
    val time: String,
    val level: String,
    val message: String
)

enum class TransferStatus(val label: String) {
    RUNNING("传输中"),
    PAUSED("已暂停"),
    COMPLETED("已完成")
}

data class TransferRecord(
    val id: String,
    val profileId: String?,
    val deviceId: String?,
    val peerName: String,
    val host: String,
    val port: Int,
    val secret: String,
    val role: SyncRole,
    val rangeMode: SyncRangeMode,
    val sinceEpochMillis: Long?,
    val sourcePath: String,
    val destinationPath: String,
    val status: TransferStatus,
    val progress: Float = 0f,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val message: String = ""
)

data class SyncUiState(
    val isChecking: Boolean = true,
    val isBusy: Boolean = false,
    val localTransferActive: Boolean = false,
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
    val rangeMode: SyncRangeMode = SyncRangeMode.ALL,
    val strictContentCheck: Boolean = false,
    val sinceEpochMillis: Long? = null,
    val profiles: List<PeerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val hasUnsavedProfileChanges: Boolean = false,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val onlineDeviceIds: Set<String> = emptySet(),
    val incompatibleDeviceIds: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isCheckingPeerOnline: Boolean = false,
    val pendingPairRequest: PairRequest? = null,
    val pendingDirectoryCreation: DirectoryCreationPrompt? = null,
    val serverRunning: Boolean = false,
    val phase: String = "等待检查",
    val progress: Float? = null,
    val estimatedCompletionTime: String? = null,
    val totalSyncBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val transferSpeedBytesPerSecond: Long = 0L,
    val isPreviewing: Boolean = false,
    val previewStartedMillis: Long = 0L,
    val scanUpdatedMillis: Long = 0L,
    val scanDetail: String = "等待扫描进度",
    val previewFraction: Float? = null,
    val transferPanelTitle: String = "差异与传输详情",
    val transferFolders: List<String> = emptyList(),
    val currentTransferFolder: String? = null,
    val transferItemCount: Int = 0,
    val transferFoldersTruncated: Boolean = false,
    val transferRecord: TransferRecord? = null,
    val transferRecords: List<TransferRecord> = emptyList(),
    val previewReady: Boolean = false,
    val previewStatusText: String? = null,
    val remoteActivity: RemoteSyncActivity? = null,
    val lastResult: String? = null,
    val logs: List<LogEntry> = emptyList()
) {
    val canOperate: Boolean
        get() = capabilities.rootGranted && capabilities.rsyncPath != null && !isBusy

    val selectedPairedDeviceId: String?
        get() = profiles.firstOrNull { it.id == selectedProfileId }
            ?.deviceId
            ?.takeUnless { it.startsWith("manual:") }

    val isSelectedPeerOnline: Boolean
        get() = selectedPairedDeviceId?.let { it in onlineDeviceIds } ?: true

    val strategyStatus: StrategyStatus
        get() {
            val profile = profiles.firstOrNull { it.id == selectedProfileId }
                ?: return StrategyStatus.UNPAIRED
            if (profile.deviceId.startsWith("manual:")) return StrategyStatus.UNPAIRED
            if (profile.deviceId in incompatibleDeviceIds) return StrategyStatus.UPGRADE_REQUIRED
            return if (profile.queuedStrategy == null && profile.queuedRole == null && profile.strategyRevision.valid &&
                profile.confirmedStrategyRevision == profile.strategyRevision) StrategyStatus.CONFIRMED
            else StrategyStatus.PENDING
        }
    val selectedStrategyPending: Boolean
        get() = strategyStatus == StrategyStatus.PENDING || strategyStatus == StrategyStatus.UPGRADE_REQUIRED

    val canStartTransfer: Boolean
        get() = canOperate && !isCheckingPeerOnline && isSelectedPeerOnline && !hasUnsavedProfileChanges && !selectedStrategyPending

    companion object {
        const val DEFAULT_RSYNC_PORT = 8873
        const val MIN_SECRET_LENGTH = 6
        const val DEFAULT_BILI_PATH =
            "/storage/emulated/0/Android/data/tv.danmaku.bili/download"
        const val LEGACY_BILI_PATH =
            "/storage/emulated/0/Android/data/com.danmaku.bili/download"
    }
}

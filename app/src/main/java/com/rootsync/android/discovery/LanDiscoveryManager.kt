package com.rootsync.android.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.rootsync.android.domain.DiscoveredDevice
import com.rootsync.android.domain.PairAccepted
import com.rootsync.android.domain.PairRequest
import com.rootsync.android.domain.StrategyUpdate
import com.rootsync.android.domain.SyncActivityType
import com.rootsync.android.domain.SyncActivityUpdate
import com.rootsync.android.domain.SyncPrepareRequest
import com.rootsync.android.domain.SyncPrepareResult
import com.rootsync.android.domain.SyncRangeMode
import com.rootsync.android.domain.SyncRole
import com.rootsync.android.domain.TrustedPeerUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网发现使用两条互为回退的通道：
 * 1. Android NSD / DNS-SD，负责跨机型可靠发现；
 * 2. UDP 广播，负责快速发现与配对请求/应答。
 */
class LanDiscoveryManager(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val localPort: () -> Int,
    private val localSecret: () -> String,
    private val localControlToken: () -> String,
    private val trustedControlToken: (String) -> String?,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoingPairRequests = ConcurrentHashMap<String, PairIntent>()
    private val trustedReconnectRequests = ConcurrentHashMap<String, String>()
    private val requestCreatedTimes = ConcurrentHashMap<String, Long>()
    private val seenIncomingRequests = ConcurrentHashMap<String, Long>()
    private val cachedResponses = ConcurrentHashMap<String, CachedResponse>()
    private val trustedReconnectTimes = ConcurrentHashMap<String, Long>()
    private val prepareWaiters = ConcurrentHashMap<String, CompletableDeferred<SyncPrepareResult>>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val scanGeneration = AtomicInteger(0)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null
    @Volatile private var nsdDiscovering = false

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _pairRequests = MutableSharedFlow<PairRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pairRequests: SharedFlow<PairRequest> = _pairRequests.asSharedFlow()

    private val _pairAccepted = MutableSharedFlow<PairAccepted>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pairAccepted: SharedFlow<PairAccepted> = _pairAccepted.asSharedFlow()

    private val _strategyUpdates = MutableSharedFlow<StrategyUpdate>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val strategyUpdates: SharedFlow<StrategyUpdate> = _strategyUpdates.asSharedFlow()

    private val _syncPrepareRequests = MutableSharedFlow<SyncPrepareRequest>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncPrepareRequests: SharedFlow<SyncPrepareRequest> = _syncPrepareRequests.asSharedFlow()

    private val _syncActivityUpdates = MutableSharedFlow<SyncActivityUpdate>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncActivityUpdates: SharedFlow<SyncActivityUpdate> = _syncActivityUpdates.asSharedFlow()

    private val _trustedPeerUpdates = MutableSharedFlow<TrustedPeerUpdate>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val trustedPeerUpdates: SharedFlow<TrustedPeerUpdate> = _trustedPeerUpdates.asSharedFlow()

    fun start() {
        if (socket == null) startUdpListener()
        registerNsdService()
    }

    fun scan() {
        start()
        cleanupExpiredRequests()
        _devices.update { devices ->
            val cutoff = System.currentTimeMillis() - DEVICE_TTL_MS
            devices.filter { it.lastSeenMillis >= cutoff }
        }

        val generation = scanGeneration.incrementAndGet()
        acquireMulticastLock()
        startNsdDiscovery()
        scope.launch {
            val message = baseMessage(TYPE_DISCOVER)
            repeat(UDP_SCAN_BURSTS) { index ->
                broadcastAddresses().forEach { address -> send(message, address) }
                if (index < UDP_SCAN_BURSTS - 1) delay(UDP_SCAN_INTERVAL_MS)
            }
            onLog("正在使用 NSD/mDNS 与 UDP 广播扫描局域网")
            delay(SCAN_WINDOW_MS - UDP_SCAN_INTERVAL_MS * (UDP_SCAN_BURSTS - 1))
            if (scanGeneration.get() == generation) {
                stopNsdDiscovery()
                releaseMulticastLock()
            }
        }
    }

    fun probePresence(hosts: Collection<String>) {
        start()
        cleanupExpiredRequests()
        val now = System.currentTimeMillis()
        _devices.update { devices -> devices.filter { now - it.lastSeenMillis <= DEVICE_TTL_MS } }
        scope.launch {
            val message = baseMessage(TYPE_DISCOVER)
            repeat(PRESENCE_PROBE_BURSTS) { index ->
                hosts.distinct().forEach { host ->
                    runCatching { InetAddress.getByName(host) }
                        .onSuccess { address -> send(message, address) }
                }
                if (index < PRESENCE_PROBE_BURSTS - 1) delay(PRESENCE_PROBE_INTERVAL_MS)
            }
        }
    }

    suspend fun confirmPresence(
        deviceId: String,
        host: String,
        timeoutMillis: Long = ACTIVE_PRESENCE_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        start()
        val startedAt = System.currentTimeMillis()
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext false
        repeat(ACTIVE_PRESENCE_PROBE_BURSTS) { index ->
            send(baseMessage(TYPE_DISCOVER), address)
            if (index < ACTIVE_PRESENCE_PROBE_BURSTS - 1) delay(ACTIVE_PRESENCE_PROBE_INTERVAL_MS)
        }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (_devices.value.any { it.deviceId == deviceId && it.lastSeenMillis >= startedAt }) {
                return@withContext true
            }
            delay(100L)
        }
        false
    }

    fun requestPair(
        device: DiscoveredDevice,
        role: SyncRole,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?
    ) {
        start()
        val requestId = UUID.randomUUID().toString()
        outgoingPairRequests[requestId] = PairIntent(role, rangeMode, sinceEpochMillis)
        requestCreatedTimes[requestId] = System.currentTimeMillis()
        val message = baseMessage(TYPE_PAIR_REQUEST)
            .put("requestId", requestId)
            .put("secret", localSecret())
            .put("controlToken", localControlToken())
            .put("role", role.name)
            .put("rangeMode", rangeMode.name)
        sinceEpochMillis?.let { message.put("sinceEpochMillis", it) }
        scope.launch {
            sendRepeated(message, InetAddress.getByName(device.host), 3, 250L)
            onLog("已向 ${device.name} 发出配对请求")
        }
    }

    fun reconnectTrusted(device: DiscoveredDevice) {
        if (trustedControlToken(device.deviceId) == null) return
        start()
        val requestId = UUID.randomUUID().toString()
        trustedReconnectRequests[requestId] = device.deviceId
        requestCreatedTimes[requestId] = System.currentTimeMillis()
        val message = baseMessage(TYPE_TRUSTED_HELLO)
            .put("requestId", requestId)
            .put("secret", localSecret())
            .put("controlToken", localControlToken())
        scope.launch {
            sendRepeated(message, InetAddress.getByName(device.host), 2, 200L)
            onLog("正在恢复与 ${device.name} 的已信任连接")
        }
    }

    fun answerPair(request: PairRequest, allow: Boolean) {
        start()
        val type = if (allow) TYPE_PAIR_ACCEPT else TYPE_PAIR_DENY
        val message = baseMessage(type).put("requestId", request.requestId)
        if (allow) {
            message.put("secret", localSecret())
            message.put("controlToken", localControlToken())
        }
        cachedResponses[request.requestId] = CachedResponse(message.toString(), System.currentTimeMillis())
        scope.launch {
            send(message, InetAddress.getByName(request.host))
            onLog(if (allow) "已允许 ${request.name} 连接" else "已拒绝 ${request.name} 连接")
        }
    }

    fun sendStrategy(
        host: String,
        role: SyncRole,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?
    ) {
        start()
        val message = baseMessage(TYPE_STRATEGY_UPDATE)
            .put("secret", localSecret())
            .put("controlToken", localControlToken())
            .put("role", role.name)
            .put("rangeMode", rangeMode.name)
        sinceEpochMillis?.let { message.put("sinceEpochMillis", it) }
        scope.launch {
            sendRepeated(message, InetAddress.getByName(host), 2, 180L)
            onLog("已发送零删除设备策略：${role.label}")
        }
    }

    suspend fun requestSyncPreparation(
        host: String,
        role: SyncRole,
        rangeMode: SyncRangeMode,
        sinceEpochMillis: Long?,
        untilEpochMillis: Long,
        isPreview: Boolean,
        timeoutMillis: Long = PREPARE_TIMEOUT_MS
    ): SyncPrepareResult? {
        start()
        val requestId = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<SyncPrepareResult>()
        prepareWaiters[requestId] = waiter
        val message = baseMessage(TYPE_SYNC_PREPARE)
            .put("requestId", requestId)
            .put("secret", localSecret())
            .put("controlToken", localControlToken())
            .put("role", role.name)
            .put("rangeMode", rangeMode.name)
            .put("untilEpochMillis", untilEpochMillis)
            .put("isPreview", isPreview)
        sinceEpochMillis?.let { message.put("sinceEpochMillis", it) }
        val retryJob = scope.launch {
            sendRepeated(message, InetAddress.getByName(host), 3, 8_000L)
            onLog("已请求远端自动准备 rsync 服务")
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { waiter.await() }
        } finally {
            retryJob.cancel()
            prepareWaiters.remove(requestId, waiter)
        }
    }

    suspend fun sendSyncActivity(
        host: String,
        type: SyncActivityType,
        active: Boolean,
        taskId: String,
        totalItems: Int = 0
    ) =
        withContext(Dispatchers.IO) {
            start()
            val message = baseMessage(TYPE_SYNC_ACTIVITY)
                .put("secret", localSecret())
                .put("controlToken", localControlToken())
                .put("activity", type.name)
                .put("active", active)
                .put("taskId", taskId)
                .put("totalItems", totalItems.coerceAtLeast(0))
            val address = InetAddress.getByName(host)
            repeat(2) { index ->
                send(message, address)
                if (index == 0) delay(80)
            }
        }

    fun sendSyncItem(
        host: String,
        type: SyncActivityType,
        taskId: String,
        itemPath: String,
        itemIndex: Int
    ) {
        if (itemPath.isBlank()) return
        start()
        scope.launch {
            val message = baseMessage(TYPE_SYNC_ACTIVITY)
                .put("secret", localSecret())
                .put("controlToken", localControlToken())
                .put("activity", type.name)
                .put("active", true)
                .put("taskId", taskId)
                .put("itemPath", itemPath.take(1200))
                .put("itemIndex", itemIndex.coerceAtLeast(0))
            send(message, InetAddress.getByName(host))
        }
    }

    fun answerSyncPreparation(
        request: SyncPrepareRequest,
        ready: Boolean,
        messageText: String,
        port: Int
    ) {
        start()
        val message = baseMessage(TYPE_SYNC_READY)
            .put("requestId", request.requestId)
            .put("controlToken", localControlToken())
            .put("ready", ready)
            .put("message", messageText.take(240))
            .put("port", port)
        if (ready) message.put("secret", localSecret())
        cachedResponses[request.requestId] = CachedResponse(message.toString(), System.currentTimeMillis())
        scope.launch { send(message, InetAddress.getByName(request.host)) }
    }

    fun stop() {
        stopNsdDiscovery()
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        registeredServiceName = null
        releaseMulticastLock()
        socket?.close()
        socket = null
        scope.cancel()
    }

    private fun startUdpListener() {
        val opened = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (error: Exception) {
            onLog("UDP 发现监听失败：${error.message ?: error::class.java.simpleName}")
            return
        }
        wifiNetwork()?.let { network ->
            runCatching { network.bindSocket(opened) }
                .onFailure { error ->
                    onLog("UDP 无法绑定 Wi-Fi，继续使用系统默认网络：${error.message ?: error::class.java.simpleName}")
                }
        }
        socket = opened
        scope.launch {
            try {
                val buffer = ByteArray(MAX_PACKET_SIZE)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    opened.receive(packet)
                    handlePacket(
                        payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                        sender = packet.address
                    )
                }
            } catch (_: SocketException) {
                // stop() 关闭 socket 后会正常退出。
            } catch (error: Exception) {
                onLog("UDP 发现监听异常：${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifiManager.createMulticastLock("RootSync:LanDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { error ->
            onLog("无法启用 Wi-Fi 组播接收：${error.message ?: error::class.java.simpleName}")
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        multicastLock = null
    }

    private fun registerNsdService() {
        if (registrationListener != null) return
        val serviceInfo = NsdServiceInfo().apply {
            // 服务实例名保持短 ASCII，避免中文设备名超过 mDNS 单标签 63 字节限制。
            // 实际显示名称放在 TXT 属性 deviceName 中。
            serviceName = "RootSync-${deviceId.take(12)}"
            serviceType = NSD_SERVICE_TYPE
            port = localPort()
            setAttribute("deviceId", deviceId)
            setAttribute("deviceName", deviceName.take(64))
            setAttribute("protocol", PROTOCOL_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                onLog("NSD 服务已注册：${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registrationListener = null
                onLog("NSD 服务注册失败：$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onLog("NSD 服务注销失败：$errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            registrationListener = null
            onLog("NSD 不可用：${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun startNsdDiscovery() {
        if (nsdDiscovering) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onLog("NSD 扫描已启动")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_rootsync._tcp")) return
                if (info.serviceName == registeredServiceName) return
                resolveQueue.offer(info)
                resolveNextNsdService()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // 设备列表依靠 TTL 清理，避免 Wi-Fi 抖动导致条目闪烁。
            }

            override fun onDiscoveryStopped(serviceType: String) {
                nsdDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdDiscovering = false
                discoveryListener = null
                onLog("NSD 扫描启动失败：$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdDiscovering = false
                discoveryListener = null
                onLog("NSD 扫描停止失败：$errorCode")
            }
        }
        discoveryListener = listener
        nsdDiscovering = true
        runCatching {
            nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            nsdDiscovering = false
            discoveryListener = null
            onLog("NSD 扫描异常：${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun stopNsdDiscovery() {
        val listener = discoveryListener ?: return
        if (nsdDiscovering) runCatching { nsdManager.stopServiceDiscovery(listener) }
        discoveryListener = null
        nsdDiscovering = false
    }

    @Suppress("DEPRECATION")
    private fun resolveNextNsdService() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        runCatching {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    onLog("NSD 地址解析失败：$errorCode")
                    resolveNextNsdService()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    addResolvedNsdDevice(serviceInfo)
                    resolving.set(false)
                    resolveNextNsdService()
                }
            })
        }.onFailure { error ->
            resolving.set(false)
            onLog("NSD 地址解析异常：${error.message ?: error::class.java.simpleName}")
            resolveNextNsdService()
        }
    }

    private fun addResolvedNsdDevice(info: NsdServiceInfo) {
        val remoteDeviceId = info.attributes["deviceId"]?.toString(Charsets.UTF_8).orEmpty()
        if (remoteDeviceId.isBlank() || remoteDeviceId == deviceId) return
        val remoteName = info.attributes["deviceName"]?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: info.serviceName.removePrefix("RootSync-").substringBeforeLast('-')
        val address = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.host
        } else {
            @Suppress("DEPRECATION")
            info.host
        }
        val host = address?.hostAddress?.takeIf { it.count { char -> char == '.' } == 3 } ?: return
        val port = info.port.takeIf { it in 1024..65535 } ?: 8873
        addOrUpdateDevice(remoteDeviceId, remoteName, host, port)
        onLog("NSD 发现设备：$remoteName ($host:$port)")
    }

    private fun handlePacket(payload: String, sender: InetAddress) {
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (message.optString("magic") != MAGIC || message.optInt("version") != PROTOCOL_VERSION) return
        val remoteDeviceId = message.optString("deviceId")
        if (remoteDeviceId.isBlank() || remoteDeviceId == deviceId) return
        val remoteName = message.optString("name").ifBlank { "Android 设备" }
        val remotePort = message.optInt("port", 8873).takeIf { it in 1024..65535 } ?: 8873
        val host = sender.hostAddress ?: return

        when (message.optString("type")) {
            TYPE_DISCOVER -> scope.launch { send(baseMessage(TYPE_ANNOUNCE), sender) }
            TYPE_ANNOUNCE -> addOrUpdateDevice(remoteDeviceId, remoteName, host, remotePort)
            TYPE_PAIR_REQUEST -> {
                val requestId = message.optString("requestId")
                if (requestId.isBlank()) return
                cachedResponses[requestId]?.let { cached ->
                    scope.launch { send(JSONObject(cached.payload), sender) }
                    return
                }
                if (seenIncomingRequests.putIfAbsent(requestId, System.currentTimeMillis()) != null) return
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                val role = parseRole(message.optString("role"))
                val rangeMode = parseRangeMode(message.optString("rangeMode"))
                val since = parseSince(message, rangeMode)
                if (secret.length >= 6 && controlToken.length >= MIN_CONTROL_TOKEN_LENGTH &&
                    role != null && rangeMode != null &&
                    (rangeMode == SyncRangeMode.ALL || since != null)
                ) {
                    _pairRequests.tryEmit(
                        PairRequest(
                            requestId,
                            remoteDeviceId,
                            remoteName,
                            host,
                            remotePort,
                            secret,
                            controlToken,
                            role,
                            rangeMode,
                            since
                        )
                    )
                }
            }
            TYPE_PAIR_ACCEPT -> {
                val requestId = message.optString("requestId")
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                val intent = outgoingPairRequests.remove(requestId)
                requestCreatedTimes.remove(requestId)
                if (intent != null && secret.length >= 6 && controlToken.length >= MIN_CONTROL_TOKEN_LENGTH) {
                    addOrUpdateDevice(remoteDeviceId, remoteName, host, remotePort)
                    _pairAccepted.tryEmit(
                        PairAccepted(
                            remoteDeviceId,
                            remoteName,
                            host,
                            remotePort,
                            secret,
                            controlToken,
                            intent.role,
                            intent.rangeMode,
                            intent.sinceEpochMillis
                        )
                    )
                }
            }
            TYPE_PAIR_DENY -> {
                val requestId = message.optString("requestId")
                requestCreatedTimes.remove(requestId)
                if (outgoingPairRequests.remove(requestId) != null) onLog("$remoteName 拒绝了配对请求")
            }
            TYPE_TRUSTED_HELLO -> {
                val requestId = message.optString("requestId")
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                if (requestId.isNotBlank() && secret.length >= 6 &&
                    isValidTrustedControl(remoteDeviceId, controlToken)
                ) {
                    addOrUpdateDevice(remoteDeviceId, remoteName, host, remotePort, refreshTrust = false)
                    _trustedPeerUpdates.tryEmit(
                        TrustedPeerUpdate(remoteDeviceId, remoteName, host, remotePort, secret)
                    )
                    val reply = baseMessage(TYPE_TRUSTED_ACK)
                        .put("requestId", requestId)
                        .put("secret", localSecret())
                        .put("controlToken", localControlToken())
                    scope.launch { send(reply, sender) }
                }
            }
            TYPE_TRUSTED_ACK -> {
                val requestId = message.optString("requestId")
                val expectedDeviceId = trustedReconnectRequests.remove(requestId)
                requestCreatedTimes.remove(requestId)
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                if (expectedDeviceId == remoteDeviceId && secret.length >= 6 &&
                    isValidTrustedControl(remoteDeviceId, controlToken)
                ) {
                    addOrUpdateDevice(remoteDeviceId, remoteName, host, remotePort, refreshTrust = false)
                    _trustedPeerUpdates.tryEmit(
                        TrustedPeerUpdate(remoteDeviceId, remoteName, host, remotePort, secret)
                    )
                    onLog("已自动恢复与 $remoteName 的信任连接")
                }
            }
            TYPE_STRATEGY_UPDATE -> {
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                val role = parseRole(message.optString("role"))
                val rangeMode = parseRangeMode(message.optString("rangeMode"))
                val since = parseSince(message, rangeMode)
                if (secret.length >= 6 && isValidTrustedControl(remoteDeviceId, controlToken) &&
                    role != null && rangeMode != null &&
                    (rangeMode == SyncRangeMode.ALL || since != null)
                ) {
                    _strategyUpdates.tryEmit(
                        StrategyUpdate(
                            remoteDeviceId,
                            remoteName,
                            host,
                            secret,
                            role,
                            rangeMode,
                            since
                        )
                    )
                }
            }
            TYPE_SYNC_PREPARE -> {
                val requestId = message.optString("requestId")
                if (requestId.isBlank()) return
                cachedResponses[requestId]?.let { cached ->
                    scope.launch { send(JSONObject(cached.payload), sender) }
                    return
                }
                if (seenIncomingRequests.putIfAbsent(requestId, System.currentTimeMillis()) != null) return
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                val role = parseRole(message.optString("role"))
                val rangeMode = parseRangeMode(message.optString("rangeMode"))
                val since = parseSince(message, rangeMode)
                val until = message.optLong("untilEpochMillis", -1L)
                val isPreview = message.optBoolean("isPreview", false)
                if (secret.length >= 6 && isValidTrustedControl(remoteDeviceId, controlToken) &&
                    role != null && rangeMode != null &&
                    (rangeMode == SyncRangeMode.ALL || since != null) && until > 0L
                ) {
                    _syncPrepareRequests.tryEmit(
                        SyncPrepareRequest(
                            requestId,
                            remoteDeviceId,
                            remoteName,
                            host,
                            secret,
                            role,
                            rangeMode,
                            since,
                            until,
                            isPreview
                        )
                    )
                }
            }
            TYPE_SYNC_ACTIVITY -> {
                val secret = message.optString("secret")
                val controlToken = message.optString("controlToken")
                val activity = runCatching {
                    SyncActivityType.valueOf(message.optString("activity"))
                }.getOrNull()
                if (secret.length >= 6 && activity != null &&
                    isValidTrustedControl(remoteDeviceId, controlToken)
                ) {
                    _syncActivityUpdates.tryEmit(
                        SyncActivityUpdate(
                            deviceId = remoteDeviceId,
                            name = remoteName,
                            host = host,
                            secret = secret,
                            type = activity,
                            active = message.optBoolean("active", false),
                            taskId = message.optString("taskId"),
                            itemPath = message.optString("itemPath").takeIf { it.isNotBlank() },
                            itemIndex = message.optInt("itemIndex", 0).coerceAtLeast(0),
                            totalItems = message.optInt("totalItems", 0).coerceAtLeast(0)
                        )
                    )
                }
            }
            TYPE_SYNC_READY -> {
                val requestId = message.optString("requestId")
                val controlToken = message.optString("controlToken")
                if (!isValidTrustedControl(remoteDeviceId, controlToken)) return
                val ready = message.optBoolean("ready", false)
                val secret = message.optString("secret")
                val result = SyncPrepareResult(
                    requestId = requestId,
                    deviceId = remoteDeviceId,
                    name = remoteName,
                    host = host,
                    port = remotePort,
                    ready = ready,
                    message = message.optString("message").ifBlank {
                        if (ready) "远端服务已准备" else "远端服务准备失败"
                    },
                    secret = secret
                )
                prepareWaiters.remove(requestId)?.complete(result)
            }
        }
    }

    private fun parseRole(value: String): SyncRole? =
        runCatching { SyncRole.valueOf(value) }.getOrNull()

    private fun parseRangeMode(value: String): SyncRangeMode? =
        runCatching { SyncRangeMode.valueOf(value) }.getOrNull()

    private fun parseSince(message: JSONObject, mode: SyncRangeMode?): Long? =
        if (mode == SyncRangeMode.SINCE) {
            message.optLong("sinceEpochMillis", -1L).takeIf { it > 0L }
        } else null

    private fun isValidTrustedControl(deviceId: String, receivedToken: String): Boolean {
        val expectedToken = trustedControlToken(deviceId) ?: return false
        return receivedToken.length >= MIN_CONTROL_TOKEN_LENGTH && receivedToken == expectedToken
    }

    private fun addOrUpdateDevice(
        deviceId: String,
        name: String,
        host: String,
        port: Int,
        refreshTrust: Boolean = true
    ) {
        val device = DiscoveredDevice(deviceId, name, host, port)
        _devices.update { current ->
            (current.filterNot { it.deviceId == deviceId } + device)
                .sortedBy { it.name.lowercase() }
        }
        if (trustedControlToken(deviceId) != null) {
            _trustedPeerUpdates.tryEmit(TrustedPeerUpdate(deviceId, name, host, port))
            if (refreshTrust) {
                val now = System.currentTimeMillis()
                val previous = trustedReconnectTimes.put(deviceId, now) ?: 0L
                if (now - previous >= TRUST_RECONNECT_COOLDOWN_MS) reconnectTrusted(device)
            }
        }
    }

    private fun baseMessage(type: String): JSONObject = JSONObject()
        .put("magic", MAGIC)
        .put("version", PROTOCOL_VERSION)
        .put("type", type)
        .put("deviceId", deviceId)
        .put("name", deviceName)
        .put("port", localPort())

    private fun send(message: JSONObject, address: InetAddress) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PACKET_SIZE) return
        val activeSocket = socket ?: return
        runCatching {
            activeSocket.send(DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT))
        }.onFailure { error ->
            onLog("发送局域网发现包失败：${error.message ?: error::class.java.simpleName}")
        }
    }

    private suspend fun sendRepeated(
        message: JSONObject,
        address: InetAddress,
        attempts: Int,
        intervalMillis: Long
    ) {
        repeat(attempts.coerceAtLeast(1)) { index ->
            send(message, address)
            if (index < attempts - 1) delay(intervalMillis)
        }
    }

    private fun cleanupExpiredRequests() {
        val cutoff = System.currentTimeMillis() - REQUEST_TTL_MS
        requestCreatedTimes.entries.removeIf { entry ->
            if (entry.value >= cutoff) return@removeIf false
            outgoingPairRequests.remove(entry.key)
            trustedReconnectRequests.remove(entry.key)
            true
        }
        seenIncomingRequests.entries.removeIf { it.value < cutoff }
        cachedResponses.entries.removeIf { it.value.createdAtMillis < cutoff }
    }

    private fun wifiNetwork(): Network? {
        val active = connectivity.activeNetwork
        if (active != null && connectivity.getNetworkCapabilities(active)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        ) return active
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val properties = connectivity.getLinkProperties(wifiNetwork())
            properties?.linkAddresses
                ?.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.forEach { link ->
                    val prefix = link.prefixLength.coerceIn(0, 32)
                    val raw = link.address.address.copyOf()
                    for (bit in prefix until 32) {
                        val byteIndex = bit / 8
                        val mask = 1 shl (7 - bit % 8)
                        raw[byteIndex] = (raw[byteIndex].toInt() or mask).toByte()
                    }
                    result += InetAddress.getByAddress(raw)
                }
        }
        return result
    }

    companion object {
        const val DISCOVERY_PORT = 8874
        const val SCAN_WINDOW_MS = 8_000L
        private const val NSD_SERVICE_TYPE = "_rootsync._tcp."
        private const val MAGIC = "ROOTSYNC_LAN"
        private const val PROTOCOL_VERSION = 7
        private const val MIN_CONTROL_TOKEN_LENGTH = 32
        private const val UDP_SCAN_BURSTS = 3
        private const val UDP_SCAN_INTERVAL_MS = 700L
        private const val PRESENCE_PROBE_BURSTS = 2
        private const val PRESENCE_PROBE_INTERVAL_MS = 180L
        private const val MAX_PACKET_SIZE = 4096
        private const val DEVICE_TTL_MS = 90_000L
        private const val TYPE_DISCOVER = "discover"
        private const val TYPE_ANNOUNCE = "announce"
        private const val TYPE_PAIR_REQUEST = "pair_request"
        private const val TYPE_PAIR_ACCEPT = "pair_accept"
        private const val TYPE_PAIR_DENY = "pair_deny"
        private const val TYPE_TRUSTED_HELLO = "trusted_hello"
        private const val TYPE_TRUSTED_ACK = "trusted_ack"
        private const val TYPE_STRATEGY_UPDATE = "strategy_update"
        private const val TYPE_SYNC_PREPARE = "sync_prepare"
        private const val TYPE_SYNC_READY = "sync_ready"
        private const val TYPE_SYNC_ACTIVITY = "sync_activity"
        private const val PREPARE_TIMEOUT_MS = 30_000L
        private const val TRUST_RECONNECT_COOLDOWN_MS = 10_000L
        private const val REQUEST_TTL_MS = 2L * 60L * 1000L
        private const val ACTIVE_PRESENCE_TIMEOUT_MS = 1_500L
        private const val ACTIVE_PRESENCE_PROBE_BURSTS = 3
        private const val ACTIVE_PRESENCE_PROBE_INTERVAL_MS = 180L
    }

    private data class PairIntent(
        val role: SyncRole,
        val rangeMode: SyncRangeMode,
        val sinceEpochMillis: Long?
    )

    private data class CachedResponse(val payload: String, val createdAtMillis: Long)
}

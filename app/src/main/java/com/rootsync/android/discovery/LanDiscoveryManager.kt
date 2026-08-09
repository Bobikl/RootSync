package com.rootsync.android.discovery

import android.content.Context
import android.net.ConnectivityManager
import com.rootsync.android.domain.DiscoveredDevice
import com.rootsync.android.domain.PairAccepted
import com.rootsync.android.domain.PairRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LanDiscoveryManager(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val localPort: () -> Int,
    private val localSecret: () -> String,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoingPairRequests = ConcurrentHashMap.newKeySet<String>()
    private var socket: DatagramSocket? = null

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

    fun start() {
        if (socket != null) return
        val opened = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (error: Exception) {
            onLog("局域网发现监听失败：${error.message ?: error::class.java.simpleName}")
            return
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
                // 正常 stop() 会关闭 socket；端口占用则由下一次 scan 的日志提示。
            } catch (error: Exception) {
                onLog("局域网发现监听失败：${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    fun scan() {
        start()
        _devices.update { devices ->
            val cutoff = System.currentTimeMillis() - DEVICE_TTL_MS
            devices.filter { it.lastSeenMillis >= cutoff }
        }
        scope.launch {
            val message = baseMessage(TYPE_DISCOVER)
            broadcastAddresses().forEach { address -> send(message, address) }
            onLog("已发送 UDP 局域网扫描，端口 $DISCOVERY_PORT")
        }
    }

    fun requestPair(device: DiscoveredDevice) {
        start()
        val requestId = UUID.randomUUID().toString()
        outgoingPairRequests += requestId
        val message = baseMessage(TYPE_PAIR_REQUEST)
            .put("requestId", requestId)
            .put("secret", localSecret())
        scope.launch {
            send(message, InetAddress.getByName(device.host))
            onLog("已向 ${device.name} 发出配对请求")
        }
    }

    fun answerPair(request: PairRequest, allow: Boolean) {
        start()
        val type = if (allow) TYPE_PAIR_ACCEPT else TYPE_PAIR_DENY
        val message = baseMessage(type)
            .put("requestId", request.requestId)
        if (allow) message.put("secret", localSecret())
        scope.launch {
            send(message, InetAddress.getByName(request.host))
            onLog(if (allow) "已允许 ${request.name} 连接" else "已拒绝 ${request.name} 连接")
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        scope.cancel()
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
                val secret = message.optString("secret")
                if (requestId.isNotBlank() && secret.length >= 16) {
                    _pairRequests.tryEmit(
                        PairRequest(requestId, remoteDeviceId, remoteName, host, remotePort, secret)
                    )
                }
            }
            TYPE_PAIR_ACCEPT -> {
                val requestId = message.optString("requestId")
                val secret = message.optString("secret")
                if (outgoingPairRequests.remove(requestId) && secret.length >= 16) {
                    addOrUpdateDevice(remoteDeviceId, remoteName, host, remotePort)
                    _pairAccepted.tryEmit(
                        PairAccepted(remoteDeviceId, remoteName, host, remotePort, secret)
                    )
                }
            }
            TYPE_PAIR_DENY -> {
                val requestId = message.optString("requestId")
                if (outgoingPairRequests.remove(requestId)) onLog("$remoteName 拒绝了配对请求")
            }
        }
    }

    private fun addOrUpdateDevice(deviceId: String, name: String, host: String, port: Int) {
        val device = DiscoveredDevice(deviceId, name, host, port)
        _devices.update { current ->
            (current.filterNot { it.deviceId == deviceId } + device)
                .sortedBy { it.name.lowercase() }
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

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            val properties = connectivity.getLinkProperties(connectivity.activeNetwork)
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
        private const val MAGIC = "ROOTSYNC_LAN"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_PACKET_SIZE = 4096
        private const val DEVICE_TTL_MS = 60_000L
        private const val TYPE_DISCOVER = "discover"
        private const val TYPE_ANNOUNCE = "announce"
        private const val TYPE_PAIR_REQUEST = "pair_request"
        private const val TYPE_PAIR_ACCEPT = "pair_accept"
        private const val TYPE_PAIR_DENY = "pair_deny"
    }
}

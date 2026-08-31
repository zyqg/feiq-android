package com.feiq.droid.net

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 收发通道。绑定 2425 端口，收消息 + 发广播/单播。
 * 收到的报文通过 onPacket 回调（在 IO 线程，UI 层自行切回主线程）。
 */
class UdpChannel(
    private val portProvider: () -> Int = { Protocol.DEFAULT_PORT },
    private val onPacket: (PacketCodec.Packet, String, Int) -> Unit,
) {
    companion object { private const val TAG = "UdpChannel" }

    private var socket: DatagramSocket? = null
    private var scope = newScope()
    @Volatile private var running = false
    private val addressCache = ConcurrentHashMap<String, InetAddress>()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动监听。绑定失败（端口被占）时抛异常由上层处理。 */
    fun start() {
        if (running) return
        val port = portProvider()
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            // 加大收发缓冲，抵抗图片传输时的突发(100+包/几十毫秒)
            runCatching { receiveBufferSize = 1 shl 20 }  // 1MB
            runCatching { sendBufferSize = 1 shl 20 }
            bind(InetSocketAddress(port))
        }
        socket = s
        running = true
        scope.launch { receiveLoop(s) }
        Log.i(TAG, "UDP 已绑定 $port")
    }

    private suspend fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65535)
        while (running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                s.receive(dp)
                val pkt = PacketCodec.parse(dp.data, dp.length)
                if (pkt != null) {
                    onPacket(pkt, dp.address.hostAddress ?: "", dp.port)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "recv error: ${e.message}")
            }
        }
    }

    /** 发到对端的 2425 端口（标准 IPMsg）。 */
    fun send(data: ByteArray, ip: String) = sendTo(data, ip, portProvider())

    /** 发到指定 ip:port（飞秋图片 ACK 需回到块的真实来源端口）。 */
    fun sendTo(data: ByteArray, ip: String, port: Int) {
        scope.launch {
            try {
                val addr = addressFor(ip)
                socket?.send(DatagramPacket(data, data.size, addr, port))
            } catch (e: Exception) {
                Log.w(TAG, "send to $ip:$port failed: ${e.message}")
            }
        }
    }

    /**
     * 同步发送（直接在调用线程发，不起协程）。
     * 用于图片 ACK：在接收线程内立即回 ACK，避免协程风暴导致 ACK 丢失/乱序。
     */
    fun sendNow(data: ByteArray, ip: String, port: Int) {
        try {
            val addr = addressFor(ip)
            socket?.send(DatagramPacket(data, data.size, addr, port))
        } catch (e: Exception) {
            Log.w(TAG, "sendNow to $ip:$port failed: ${e.message}")
        }
    }

    private fun addressFor(ip: String): InetAddress =
        addressCache.getOrPut(ip) { InetAddress.getByName(ip) }

    fun broadcast(data: ByteArray) = send(data, Protocol.BROADCAST)

    fun stop() {
        running = false
        socket?.close()
        socket = null
        scope.cancel()
        scope = newScope()
    }
}

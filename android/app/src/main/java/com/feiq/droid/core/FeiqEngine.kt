package com.feiq.droid.core

import android.util.Log
import com.feiq.droid.net.FeiqRichText
import com.feiq.droid.net.FileListCodec
import com.feiq.droid.net.PacketCodec
import com.feiq.droid.net.Protocol
import com.feiq.droid.net.TcpFileClient
import com.feiq.droid.net.TcpFileServer
import com.feiq.droid.net.UdpChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.InputStream
import java.io.OutputStream

/**
 * 协议引擎：发现 + 收发消息的核心逻辑。
 * 与 tools/ipmsg_tool.py 的 online/send 行为对应，已实测可与飞秋互通。
 */
class FeiqEngine(
    private val identity: Identity,
) {
    companion object { private const val TAG = "FeiqEngine"
        private const val CAPTURE_TAG = "FeiqCapture"
        private const val EMOJI_TAG = "FeiqEmoji"
        // 飞秋私有图片分块包命令低字节(完整命令 0x2000C0)
        private const val FEIQ_IMG_CHUNK = 0xC0
        // 飞秋图片块 ACK 命令(实测 = 0xC1 = 块命令+1)
        private const val FEIQ_IMG_ACK = 0xC1
        // 飞秋原生抖一抖：2026-06-07 ADB 抓包确认 cmd=209(base 0xD1), extra=00。
        private const val FEIQ_SHAKE = 0xD1
        // 手机发送 0xD1 后，电脑飞秋回 0xD2 + 00，视为抖一抖确认。
        private const val FEIQ_SHAKE_ACK = 0xD2
    }

    data class Identity(
        var user: String,     // 登录名（飞秋会展示在昵称括号里）
        var host: String,     // 主机名（旧版飞秋也会参与展示）
        val nick: String,     // 昵称（中文 OK）
        val group: String,    // 分组
        val pseudoMac: String, // 伪 MAC（12位HEX，仿飞秋版本段）
        val portProvider: () -> Int, // UDP/TCP 协议端口，默认 2425
    )

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    /** 收到的消息流（UI 订阅）。 */
    private val _incoming = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ChatMessage> = _incoming

    /** 回执流：(对端IP, 原消息包号)。包号用于精确匹配是哪条消息送达。 */
    private val _delivered = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val delivered: SharedFlow<Pair<String, String>> = _delivered

    /** 收到飞秋原生抖一抖事件。 */
    private val _shakeEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val shakeEvents: SharedFlow<String> = _shakeEvents

    /** 收到文件通告流（UI 订阅，提示用户接收）。 */
    private val _incomingFiles = MutableSharedFlow<IncomingFile>(extraBufferCapacity = 32)
    val incomingFiles: SharedFlow<IncomingFile> = _incomingFiles

    /** 文件传输进度流： "fileId:got:total"（粗粒度，UI 自取）。 */
    private val _fileProgress = MutableSharedFlow<FileProgress>(extraBufferCapacity = 128)
    val fileProgress: SharedFlow<FileProgress> = _fileProgress

    data class FileProgress(val fileId: Int, val done: Long, val total: Long, val outgoing: Boolean)

    private val peerMap = LinkedHashMap<String, Peer>()

    private val versionField get() = PacketCodec.buildVersionField(identity.pseudoMac)

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun currentPort(): Int = identity.portProvider().coerceIn(1, 65535)

    private val udp = UdpChannel({ currentPort() }) { pkt, ip, port -> onPacket(pkt, ip, port) }

    // 飞秋内联图片重组器（私有协议逆向）
    private val imageReceiver = com.feiq.droid.net.InlineImageReceiver { peerIp, imageId, data ->
        Log.i(TAG, "内联图片重组完成 $imageId ${data.size}字节 from $peerIp")
        _inlineImages.tryEmit(InlineImageData(peerIp, imageId, data))
    }
    private val _inlineImages = MutableSharedFlow<InlineImageData>(extraBufferCapacity = 8)
    val inlineImages: SharedFlow<InlineImageData> = _inlineImages
    data class InlineImageData(val peerIp: String, val imageId: String, val data: ByteArray)

    // 待发送文件登记： "packetId:fileId" -> 提供输入流的工厂(可重开以支持续传)
    private val offered = HashMap<String, OfferedSource>()
    private data class OfferedSource(val open: () -> InputStream, val size: Long, val isDir: Boolean)

    private val tcpServer = TcpFileServer(
        portProvider = { currentPort() },
        provider = { packetId, fileId ->
            val src = synchronized(offered) { offered["$packetId:$fileId"] }
            src?.let { TcpFileServer.OfferedFile(it.open(), it.size, it.isDir) }
        },
        onProgress = { fid, sent, total ->
            _fileProgress.tryEmit(FileProgress(fid, sent, total, outgoing = true))
        }
    )

    fun start() {
        udp.start()
        tcpServer.start()
        warmupImagePath()
        broadcastEntry()
    }

    /**
     * 预热图片接收热路径（JIT 编译 ACK 构造/字符串解析），
     * 减少首张图首窗口 ACK 因冷启动延迟而被飞秋判超时。
     */
    private fun warmupImagePath() {
        try {
            repeat(300) { i ->
                val header = "warmup|512|${i * 512}|999|${i + 1}|512|0|2|0|00000000#"
                header.trim().removeSuffix("#").split("|")  // JIT 解析
                val ackBody = "warmup|${i + 1}#".toByteArray(PacketCodec.GBK) + byteArrayOf(0)
                PacketCodec.build(versionField, identity.user, identity.host, FEIQ_IMG_ACK, ackBody)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try { sendExit() } catch (_: Exception) {}
        tcpServer.stop()
        udp.stop()
    }

    /** 网络切换/恢复后重建 socket，并重新广播上线。保持同一个引擎对象，避免 UI 订阅丢失。 */
    fun restartNetwork() {
        try { tcpServer.stop() } catch (_: Exception) {}
        try { udp.stop() } catch (_: Exception) {}
        udp.start()
        tcpServer.start()
        warmupImagePath()
        broadcastEntry()
    }

    /** 上线广播 BR_ENTRY，附加段=昵称\0分组，命令字带文件能力位（仿飞秋）。 */
    fun broadcastEntry() {
        val cmd = Protocol.IPMSG_BR_ENTRY or Protocol.IPMSG_FILEATTACHOPT
        udp.broadcast(buildEntryPacket(cmd))
    }

    private fun sendExit() {
        val cmd = Protocol.IPMSG_BR_EXIT
        udp.broadcast(buildEntryPacket(cmd))
    }

    // 可变的昵称/分组（用户可在设置里改）
    @Volatile private var curNick = identity.nick
    @Volatile private var curGroup = identity.group

    /** 更新昵称/分组并重新广播，让其他端看到新名字。 */
    fun updateProfile(nick: String, group: String) {
        curNick = nick
        curGroup = group
        identity.user = nick.replace(":", ";").trim().ifBlank { "android" }
        identity.host = identity.user
        broadcastEntry()
    }

    private fun buildEntryPacket(cmd: Int): ByteArray {
        val extra = PacketCodec.encodeText(curNick) +
            byteArrayOf(0) + PacketCodec.encodeText(curGroup) + byteArrayOf(0)
        return PacketCodec.build(versionField, identity.user, identity.host, cmd, extra)
    }

    /** 主动刷新：再广播一次上线，触发别人回应。 */
    fun refresh() = broadcastEntry()

    /** 手动添加/探测一个 IP：单播上线包到指定地址，绕过广播受限环境。 */
    fun probePeer(ip: String) {
        udp.send(buildEntryPacket(Protocol.IPMSG_BR_ENTRY or Protocol.IPMSG_FILEATTACHOPT), ip)
    }

    /** 发送文字消息（要求回执）。返回该消息的包号，UI 据此关联送达状态。 */
    fun sendMessage(
        ip: String,
        text: String,
        richStyle: FeiqRichText.FontStyle? = null,
    ): String {
        if (text.trim() == "/imgtest") { sendInlineImageTest(ip); return "" }
        val cmd = Protocol.IPMSG_SENDMSG or Protocol.IPMSG_SENDCHECKOPT
        val pktNo = PacketCodec.nextPacketNo().toString()
        val head = "$versionField:$pktNo:${identity.user}:${identity.host}:$cmd:"
            .toByteArray(PacketCodec.GBK)
        val wireText = FeiqRichText.buildTaggedText(text, richStyle)
        udp.send(head + PacketCodec.encodeText(wireText), ip)
        return pktNo
    }

    fun sendMessageToMany(
        ips: Collection<String>,
        text: String,
        richStyle: FeiqRichText.FontStyle? = null,
    ): Map<String, String> {
        val targets = ips.distinct()
        return targets.associateWith { sendMessage(it, text, richStyle) }
    }

    data class InlineImageSend(val imageId: String, val data: ByteArray)

    fun sendMixedMessage(
        ip: String,
        textWithMarkers: String,
        images: List<InlineImageSend>,
        richStyle: FeiqRichText.FontStyle? = null,
    ): String {
        val cmd = Protocol.IPMSG_SENDMSG or Protocol.IPMSG_SENDCHECKOPT
        val pktNo = PacketCodec.nextPacketNo().toString()
        val head = "$versionField:$pktNo:${identity.user}:${identity.host}:$cmd:"
            .toByteArray(PacketCodec.GBK)
        val wireText = FeiqRichText.buildTaggedText(textWithMarkers, richStyle)
        udp.send(head + PacketCodec.encodeText(wireText), ip)
        scope.launch {
            images.forEachIndexed { idx, img ->
                delay(if (idx == 0) 40 else 80)
                sendInlineChunks(ip, img.imageId, img.data)
            }
        }
        return pktNo
    }

    /** 发送飞秋原生抖一抖。抓包确认：cmd=0xD1，附加段为单个 NUL 字节。 */
    fun sendShake(ip: String) {
        val pkt = PacketCodec.build(
            versionField,
            identity.user,
            identity.host,
            FEIQ_SHAKE,
            byteArrayOf(0)
        )
        udp.send(pkt, ip)
        Log.i(TAG, "已向 $ip 发送飞秋原生抖一抖")
    }

    /**
     * 【调试】按飞秋私有协议给对端发一张合成图片，用于诱使飞秋回 ACK 以便抓包。
     * 先发 SENDMSG 标记 /~#>id<B~，再发若干 0x2000C0 分块。
     */
    fun sendInlineImageTest(ip: String) {
        val imageId = "aabbccdd"
        val total = 2048
        val chunkSize = 512
        val totalChunks = (total + chunkSize - 1) / chunkSize
        // 合成数据
        val data = ByteArray(total) { (it and 0xFF).toByte() }
        // 1) 标记包
        val marker = "/~#>$imageId<B~"
        udp.send(
            PacketCodec.build(versionField, identity.user, identity.host,
                Protocol.IPMSG_SENDMSG or Protocol.IPMSG_SENDCHECKOPT,
                PacketCodec.encodeText(marker)),
            ip
        )
        // 2) 分块包(命令 0x2000C0)
        val imgCmd = 0x200000 or FEIQ_IMG_CHUNK   // = 0x2000C0
        for (i in 0 until totalChunks) {
            val offset = i * chunkSize
            val n = minOf(chunkSize, total - offset)
            val header = "$imageId|$total|$offset|$totalChunks|${i + 1}|$n|0|2|0|00000000#"
            val payload = data.copyOfRange(offset, offset + n)
            val extra = header.toByteArray(PacketCodec.GBK) + byteArrayOf(0) + payload
            val pktNo = PacketCodec.nextPacketNo()
            val head = "$versionField:$pktNo:${identity.user}:${identity.host}:$imgCmd:"
                .toByteArray(PacketCodec.GBK)
            udp.send(head + extra, ip)
        }
        Log.i(TAG, "已向 $ip 发送测试内联图片 $imageId ($totalChunks 块)，等待飞秋 ACK")
    }

    /**
     * 给对端发送一张真实图片（飞秋私有内联协议，飞秋端会内联显示）。
     * 先发标记包，再分块发。分块间小睡，避免突发丢包。
     */
    fun sendInlineImage(ip: String, data: ByteArray) {
        val imageId = java.lang.Long.toHexString(PacketCodec.nextPacketNo() and 0xFFFFFFFFL).padStart(8, '0').takeLast(8)
        sendMixedMessage(ip, "/~#>$imageId<B~", listOf(InlineImageSend(imageId, data)))
    }

    private suspend fun sendInlineChunks(ip: String, imageId: String, data: ByteArray) {
        val total = data.size
        val chunkSize = 512
        val totalChunks = (total + chunkSize - 1) / chunkSize
        val imgCmd = 0x200000 or FEIQ_IMG_CHUNK
        for (i in 0 until totalChunks) {
            val offset = i * chunkSize
            val n = minOf(chunkSize, total - offset)
            val header = "$imageId|$total|$offset|$totalChunks|${i + 1}|$n|0|2|0|00000000#"
            val payload = data.copyOfRange(offset, offset + n)
            val extra = header.toByteArray(PacketCodec.GBK) + byteArrayOf(0) + payload
            val pktNo = PacketCodec.nextPacketNo()
            val head = "$versionField:$pktNo:${identity.user}:${identity.host}:$imgCmd:"
                .toByteArray(PacketCodec.GBK)
            udp.send(head + extra, ip)
            if (i % 32 == 31) delay(8)
        }
        Log.i(TAG, "已向 $ip 发送内联图片 $imageId ($total 字节 $totalChunks 块)")
    }

    /** 单个待发文件的描述（含打开输入流的工厂）。 */
    data class SendItem(
        val name: String,
        val size: Long,
        val mtime: Long,
        val openStream: () -> InputStream,
        val attr: Int = Protocol.IPMSG_FILE_REGULAR,
    )

    /** 发送单个文件（便捷封装）。 */
    fun sendFile(ip: String, name: String, size: Long, mtime: Long, openStream: () -> InputStream) =
        sendFiles(ip, listOf(SendItem(name, size, mtime, openStream)))

    /**
     * 发送多个文件：一条 SENDMSG|FILEATTACH 通告里带多文件清单，每个文件独立 fileId。
     * 对方按 fileId 分别 TCP 拉取。
     */
    fun sendFiles(ip: String, items: List<SendItem>) {
        if (items.isEmpty()) return
        val packetNo = PacketCodec.nextPacketNo().toString()
        val outFiles = ArrayList<FileListCodec.OutFile>(items.size)
        synchronized(offered) {
            items.forEachIndexed { i, it ->
                val isDir = (it.attr and 0xFF) == Protocol.IPMSG_FILE_DIR
                offered["$packetNo:$i"] = OfferedSource(it.openStream, it.size, isDir)
                outFiles.add(FileListCodec.OutFile(i, it.name, it.size, it.mtime, it.attr))
            }
        }
        val cmd = Protocol.IPMSG_SENDMSG or Protocol.IPMSG_SENDCHECKOPT or Protocol.IPMSG_FILEATTACHOPT
        val label = if (items.size == 1) "[文件] ${items[0].name}"
            else "[文件] ${items.size}个文件"
        val extra = PacketCodec.encodeText(label) +
            byteArrayOf(0) +
            FileListCodec.buildMulti(outFiles)
        val head = "$versionField:$packetNo:${identity.user}:${identity.host}:$cmd:"
            .toByteArray(PacketCodec.GBK)
        udp.send(head + extra, ip)
        Log.i(TAG, "已通告 ${items.size} 个文件给 $ip packet=$packetNo")
    }

    /**
     * 接收文件：主动连对方 TCP 拉取，写入 sink。阻塞，调用方放 IO 线程。
     * @return 实际接收字节数
     */
    fun downloadFile(file: IncomingFile, sink: OutputStream): Long {
        return TcpFileClient.download(
            ip = file.peerIp, packetId = file.packetId, fileId = file.fileId,
            port = currentPort(), size = file.size, versionField = versionField,
            user = identity.user, host = identity.host, sink = sink,
            onProgress = { got, total ->
                _fileProgress.tryEmit(FileProgress(file.fileId, got, total, outgoing = false))
            }
        )
    }

    fun downloadDir(file: IncomingFile, destRoot: java.io.File): Long {
        return TcpFileClient.downloadDir(
            ip = file.peerIp, packetId = file.packetId, fileId = file.fileId,
            port = currentPort(), versionField = versionField,
            user = identity.user, host = identity.host, destRoot = destRoot,
            onProgress = { got, total ->
                _fileProgress.tryEmit(FileProgress(file.fileId, got, total, outgoing = false))
            }
        )
    }

    private fun onPacket(pkt: PacketCodec.Packet, ip: String, srcPort: Int) {
        // 过滤掉自己（收到自己广播的回环），避免把自己列进用户列表
        if (pkt.user == identity.user && pkt.host == identity.host) return
        logCaptureIfUseful(pkt, ip, srcPort)
        when (pkt.commandBase) {
            Protocol.IPMSG_BR_ENTRY -> {
                upsertPeer(pkt, ip)
                // 回应对方，单播自己的 ANSENTRY
                val cmd = Protocol.IPMSG_ANSENTRY or Protocol.IPMSG_FILEATTACHOPT
                udp.send(buildEntryPacket(cmd), ip)
            }
            Protocol.IPMSG_ANSENTRY -> upsertPeer(pkt, ip)
            Protocol.IPMSG_BR_ABSENCE -> upsertPeer(pkt, ip)
            Protocol.IPMSG_BR_EXIT -> removePeer(ip)
            Protocol.IPMSG_SENDMSG -> {
                logIncomingTextForEmojiCapture(pkt, ip)
                // 收到消息：回执 + 推给 UI
                if (pkt.wantsRecvAck) {
                    val ack = PacketCodec.build(
                        versionField, identity.user, identity.host,
                        Protocol.IPMSG_RECVMSG,
                        pkt.packetNo.toByteArray(Charsets.US_ASCII)
                    )
                    udp.send(ack, ip)
                }
                if (pkt.hasFile) {
                    // 文件通告：解析清单，推给 UI 生成文件卡片(不再额外发文字气泡)
                    val files = FileListCodec.parse(pkt.extraExtRaw)
                    for (f in files) {
                        if (f.isRegular || f.isDir) {
                            _incomingFiles.tryEmit(
                                IncomingFile(ip, pkt.packetNo, f.fileId, f.name, f.size, f.isDir)
                            )
                        }
                    }
                    // 飞秋文件附带的正文常是文件名/空，去掉文件名后若仍有真实文字才显示
                    val parsed = FeiqRichText.parse(pkt.extraMain)
                    val text = parsed.plainText
                    val fileNames = files.map { it.name }.toSet()
                    if (text.isNotEmpty() && text !in fileNames &&
                        !text.startsWith("[文件]") && files.none { text.contains(it.name) }) {
                        _incoming.tryEmit(ChatMessage(ip, text, outgoing = false, richStyle = parsed.fontTagBody))
                    }
                } else {
                    val parsed = FeiqRichText.parse(pkt.extraMain)
                    val text = parsed.tokenText
                    val note = when {
                        // 内联图片：标记包先到，图片随后由分块重组完成并单独显示。
                        // 这里只在有附带文字时显示文字；纯图片标记不显示占位文字。
                        parsed.inlineImageIds.isNotEmpty() -> {
                            if (parsed.plainText.isEmpty() && parsed.inlineImageIds.size == 1) return else text
                        }
                        text.isEmpty() -> return  // 纯标记/空消息，不显示
                        else -> text
                    }
                    _incoming.tryEmit(ChatMessage(ip, note, outgoing = false, richStyle = parsed.fontTagBody))
                }
            }
            Protocol.IPMSG_RECVMSG -> {
                // 对方确认收到了我发的消息；附加段=原消息包号
                _delivered.tryEmit(ip to pkt.extraMain.trim())
            }
            FEIQ_IMG_CHUNK -> {
                // 飞秋私有图片分块包(0x2000C0)：先回 ACK 再重组，避免 Windows 飞秋窗口等待 15 秒。
                val info = com.feiq.droid.net.InlineImageReceiver.parseHeader(pkt.extraMain, pkt.extraExtRaw.size)
                if (info != null) {
                    val ackBody = "${info.imageId}|${info.index}#".toByteArray(PacketCodec.GBK) +
                        byteArrayOf(0)
                    val ack = PacketCodec.build(
                        versionField, identity.user, identity.host, FEIQ_IMG_ACK, ackBody
                    )
                    // 同步立即回 ACK，回到块的真实来源端口。
                    udp.sendNow(ack, ip, srcPort)
                    udp.sendNow(ack, ip, srcPort)
                    imageReceiver.onChunk(ip, info, pkt.extraExtRaw)
                    if (info.index <= 160 || info.index % 32 == 0) {
                        udp.sendNow(ack, ip, srcPort)
                    }
                }
            }
            FEIQ_SHAKE -> {
                Log.i(TAG, "收到 $ip 的飞秋原生抖一抖")
                _shakeEvents.tryEmit(ip)
            }
            FEIQ_SHAKE_ACK -> Log.i(TAG, "收到 $ip 的飞秋抖一抖确认")
            else -> Log.d(TAG, "未处理: ${Protocol.describe(pkt.command)} from $ip")
        }
    }

    private fun logCaptureIfUseful(pkt: PacketCodec.Packet, ip: String, srcPort: Int) {
        val shouldLog = when (pkt.commandBase) {
            FEIQ_SHAKE, FEIQ_SHAKE_ACK -> true
            FEIQ_IMG_CHUNK, FEIQ_IMG_ACK -> false
            Protocol.IPMSG_BR_ENTRY,
            Protocol.IPMSG_ANSENTRY,
            Protocol.IPMSG_BR_ABSENCE,
            Protocol.IPMSG_BR_EXIT,
            Protocol.IPMSG_SENDMSG,
            Protocol.IPMSG_RECVMSG -> false
            else -> true
        }
        if (!shouldLog) return
        val opts = pkt.command and 0xFFFFFF00.toInt()
        val main = pkt.extraMain.replace("\u0000", "\\0").take(200)
        val ext = pkt.extraExt.replace("\u0000", "\\0").take(120)
        val hex = pkt.extraRaw.toHex(limit = 256)
        val line = String.format(
            "from=%s:%d cmd=%d base=0x%02X opts=0x%06X desc=%s " +
                "pkt=%s enc=%s ver=%s user=%s host=%s " +
                "mainLen=%d extRawLen=%d extraRawLen=%d main=\"%s\" ext=\"%s\" extraHex=%s",
            ip, srcPort, pkt.command, pkt.commandBase, opts, Protocol.describe(pkt.command),
            pkt.packetNo, pkt.encoding, pkt.versionField, pkt.user, pkt.host,
            pkt.extraMain.length, pkt.extraExtRaw.size, pkt.extraRaw.size, main, ext, hex
        )
        Log.i(CAPTURE_TAG, line)
    }

    private fun logIncomingTextForEmojiCapture(pkt: PacketCodec.Packet, ip: String) {
        if (pkt.hasFile) return
        val raw = pkt.extraMain.replace("\n", "\\n").replace("\r", "\\r")
        val parsed = FeiqRichText.parse(pkt.extraMain)
        val text = parsed.tokenText.replace("\n", "\\n").replace("\r", "\\r")
        Log.i(
            EMOJI_TAG,
            "from=$ip pkt=${pkt.packetNo} raw=\"$raw\" parsed=\"$text\" font=\"${parsed.fontTagBody}\""
        )
    }

    private fun ByteArray.toHex(limit: Int): String {
        val n = minOf(size, limit)
        val sb = StringBuilder(n * 3 + 24)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            sb.append("%02X".format(this[i].toInt() and 0xFF))
        }
        if (size > limit) sb.append(" ...(+").append(size - limit).append(" bytes)")
        return sb.toString()
    }

    private fun upsertPeer(pkt: PacketCodec.Packet, ip: String) {
        val peer = Peer(
            ip = ip, user = pkt.user, host = pkt.host,
            nick = pkt.extraMain, group = pkt.extraExt,
            versionField = pkt.versionField,
        )
        synchronized(peerMap) {
            peerMap[ip] = peer
            _peers.value = peerMap.values.toList()
        }
    }

    private fun removePeer(ip: String) {
        synchronized(peerMap) {
            if (peerMap.remove(ip) != null) _peers.value = peerMap.values.toList()
        }
    }

}

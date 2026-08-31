package com.feiq.droid.net

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong

/**
 * IPMsg 报文编解码。
 *
 * 报文格式：  版本段 : 包编号 : 用户名 : 主机名 : 命令字 : 附加段
 * 飞秋私有版本段（实测）： 1_lbt6_0#128#<MAC>#0#0#0#4001#9
 *   —— 安卓端模仿此格式以"押近飞秋"，见 buildVersionField()。
 *
 * 编码：默认 GBK（兼容飞秋）；命令字带 UTF8OPT 时为 UTF-8。
 */
object PacketCodec {
    val GBK: Charset = Charset.forName("GBK")
    val UTF8: Charset = Charsets.UTF_8

    private val seq = AtomicLong(System.currentTimeMillis() % 1_000_000L)
    fun nextPacketNo(): Long = seq.incrementAndGet()

    /**
     * 构造飞秋风格的版本段。
     * 标准 IPMsg 只用 "1"；飞秋用 1_lbt6_0#128#<MAC>#0#0#0#4001#9。
     * 安卓 6+ 取不到真实 MAC，用稳定伪 MAC（基于 androidId 派生）即可，飞秋只是展示用。
     */
    fun buildVersionField(pseudoMac: String): String {
        // 仿飞秋：1_lbt6_0#128#<MAC>#0#0#0#4001#9
        return "1_lbt6_0#128#$pseudoMac#0#0#0#4001#9"
    }

    /** 标准版本段（与非飞秋的标准 IPMsg 客户端互通时用）。 */
    const val VERSION_STANDARD = "1"

    /**
     * 组装报文。
     * @param versionField 版本段（飞秋风格或 "1"）
     * @param cmd 已经 OR 好选项位的命令字
     * @param extra 附加段（已是字节）
     */
    fun build(
        versionField: String,
        user: String,
        host: String,
        cmd: Int,
        extra: ByteArray = ByteArray(0),
        utf8: Boolean = false,
    ): ByteArray {
        val realCmd = if (utf8) cmd or Protocol.IPMSG_UTF8OPT else cmd
        val head = "$versionField:${nextPacketNo()}:$user:$host:$realCmd:"
            .toByteArray(GBK)
        return head + extra
    }

    /** 文本附加段编码。 */
    fun encodeText(s: String, utf8: Boolean = false): ByteArray =
        s.toByteArray(if (utf8) UTF8 else GBK)

    data class Packet(
        val versionField: String,
        val packetNo: String,
        val user: String,
        val host: String,
        val command: Int,
        val extraMain: String,    // 附加段 \0 之前（正文/昵称）
        val extraExt: String,     // 附加段 \0 之后（分组/文件清单）
        val extraExtRaw: ByteArray,
        val extraRaw: ByteArray,
        val encoding: String,
    ) {
        val commandBase: Int get() = command and 0xFF
        val wantsRecvAck: Boolean get() = command and Protocol.IPMSG_SENDCHECKOPT != 0
        val hasFile: Boolean get() = command and Protocol.IPMSG_FILEATTACHOPT != 0
    }

    /**
     * 解析报文。头部 5 个冒号字段是 ASCII；附加段按命令字的 UTF8OPT 选 GBK/UTF-8。
     */
    fun parse(data: ByteArray, len: Int = data.size): Packet? {
        // 手动按字节找前 5 个 ':'，避免把附加段里的 ':' 算进去
        val idx = IntArray(5)
        var found = 0
        var i = 0
        while (i < len && found < 5) {
            if (data[i] == ':'.code.toByte()) {
                idx[found++] = i
            }
            i++
        }
        if (found < 5) return null

        fun ascii(a: Int, b: Int) = String(data, a, b - a, Charsets.US_ASCII)
        fun headText(a: Int, b: Int) = String(data, a, b - a, GBK)
        val versionField = ascii(0, idx[0])
        val packetNo = ascii(idx[0] + 1, idx[1])
        val user = headText(idx[1] + 1, idx[2])
        val host = headText(idx[2] + 1, idx[3])
        val cmdStr = ascii(idx[3] + 1, idx[4])
        val cmd = cmdStr.toIntOrNull() ?: return null

        val extraStart = idx[4] + 1
        val extraBytes = data.copyOfRange(extraStart, len)
        val utf8 = cmd and Protocol.IPMSG_UTF8OPT != 0
        val cs = if (utf8) UTF8 else GBK

        // 附加段按第一个 \0 分主段/扩展段
        val nul = extraBytes.indexOf(0)
        val mainBytes = if (nul < 0) extraBytes else extraBytes.copyOfRange(0, nul)
        val extRaw = if (nul < 0) ByteArray(0) else extraBytes.copyOfRange(nul + 1, extraBytes.size)
        // 扩展段可能还有多个 \0，取第一段作为分组名展示
        val extMainBytes = extRaw.indexOf(0).let { if (it < 0) extRaw else extRaw.copyOfRange(0, it) }

        return Packet(
            versionField = versionField,
            packetNo = packetNo,
            user = user,
            host = host,
            command = cmd,
            extraMain = String(mainBytes, cs),
            extraExt = String(extMainBytes, cs),
            extraExtRaw = extRaw,
            extraRaw = extraBytes,
            encoding = if (utf8) "utf-8" else "gbk",
        )
    }

    private fun ByteArray.indexOf(b: Int): Int {
        for (i in indices) if (this[i].toInt() == b) return i
        return -1
    }
}

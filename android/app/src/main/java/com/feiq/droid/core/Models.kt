package com.feiq.droid.core

/** 局域网里的一个用户（飞秋/本App）。 */
data class Peer(
    val ip: String,
    val user: String,
    val host: String,
    val nick: String,
    val group: String,
    val versionField: String,
    var lastSeen: Long = System.currentTimeMillis(),
) {
    /** 列表里展示的名字：优先昵称，退回用户名。 */
    val displayName: String get() = nick.ifBlank { user }.ifBlank { ip }
}

/** 一条聊天消息。 */
data class ChatMessage(
    val peerIp: String,
    val text: String,
    val outgoing: Boolean,
    val richStyle: String = "",
    val time: Long = System.currentTimeMillis(),
    var delivered: Boolean = false,   // 是否收到对方 RECVMSG 回执
)

/** 收到的文件附件通告（对方要发文件给我们）。 */
data class IncomingFile(
    val peerIp: String,
    val packetId: String,
    val fileId: Int,
    val name: String,
    val size: Long,
    val isDir: Boolean = false,
)

package com.feiq.droid.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.feiq.droid.ui.ChatActivity
import com.feiq.droid.net.FeiqRichText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MessageRepository(
    private val appContext: Context,
    private val engine: FeiqEngine,
    private val scope: CoroutineScope,
) {
    data class SearchHit(val peerIp: String, val peerName: String, val record: ChatRecord)

    val store = MessageStore(appContext)
    private val cache = HashMap<String, MutableList<ChatRecord>>()
    private val unread = HashMap<String, Int>()
    private val peerNames = HashMap<String, String>()
    private val peerGroups = HashMap<String, String>()
    private val pendingIncoming = HashMap<String, IncomingFile>()
    private val pendingInlineText = HashMap<String, ChatRecord>()

    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changed: SharedFlow<String> = _changed

    private fun loadKnownPeerNames() {
        try {
            val sp = appContext.getSharedPreferences("known_peers", Context.MODE_PRIVATE)
            sp.all.forEach { (k, v) -> if (v is String) peerNames[k] = v }
        } catch (_: Exception) {}
        try {
            val sp = appContext.getSharedPreferences("known_groups", Context.MODE_PRIVATE)
            sp.all.forEach { (k, v) -> if (v is String) peerGroups[k] = v }
        } catch (_: Exception) {}
    }

    private fun saveKnownPeerName(ip: String, name: String) {
        try {
            appContext.getSharedPreferences("known_peers", Context.MODE_PRIVATE)
                .edit().putString(ip, name).apply()
        } catch (_: Exception) {}
    }

    private fun saveKnownPeerGroup(ip: String, group: String) {
        try {
            appContext.getSharedPreferences("known_groups", Context.MODE_PRIVATE)
                .edit().putString(ip, group).apply()
        } catch (_: Exception) {}
    }

    fun addManualPeer(ip: String, name: String = ip) {
        peerNames[ip] = name
        saveKnownPeerName(ip, name)
        _changed.tryEmit(ip)
    }

    fun displayName(peerIp: String): String =
        peerNames[peerIp]?.takeIf { it.isNotBlank() } ?: peerIp

    fun peerGroup(peerIp: String): String =
        peerGroups[peerIp]?.takeIf { it.isNotBlank() } ?: "未分组"

    fun isPinned(peerIp: String) = Prefs.isPinned(appContext, peerIp)
    fun setPinned(peerIp: String, value: Boolean) = Prefs.setPinned(appContext, peerIp, value)
    fun isMuted(peerIp: String) = Prefs.isMuted(appContext, peerIp)
    fun setMuted(peerIp: String, value: Boolean) = Prefs.setMuted(appContext, peerIp, value)
    fun isBlocked(peerIp: String) = Prefs.isBlocked(appContext, peerIp)
    fun setBlocked(peerIp: String, value: Boolean) = Prefs.setBlocked(appContext, peerIp, value)
    fun fileRule(peerIp: String) = Prefs.fileRule(appContext, peerIp)
    fun setFileRule(peerIp: String, value: Int) = Prefs.setFileRule(appContext, peerIp, value)
    fun avatarPath(peerIp: String) = Prefs.peerAvatarPath(appContext, peerIp)
    fun setAvatarPath(peerIp: String, path: String) = Prefs.setPeerAvatarPath(appContext, peerIp, path)
    fun clearAvatar(peerIp: String) = Prefs.clearPeerAvatar(appContext, peerIp)
    fun selfAvatarPath() = Prefs.selfAvatarPath(appContext)
    fun setSelfAvatarPath(path: String) {
        Prefs.setSelfAvatarPath(appContext, path)
        _changed.tryEmit("")
    }
    fun clearSelfAvatar() {
        Prefs.clearSelfAvatar(appContext)
        _changed.tryEmit("")
    }

    fun conversationPeers(onlineIps: Set<String>): List<String> {
        val all = LinkedHashSet<String>()
        all.addAll(onlineIps)
        all.addAll(store.listPeers())
        all.addAll(peerNames.keys)
        return all.sortedWith(compareByDescending<String> { isPinned(it) }
            .thenByDescending { it in onlineIps }
            .thenByDescending { lastRecord(it)?.time ?: 0L })
    }

    fun deleteConversation(peerIp: String) {
        cache.remove(peerIp)
        unread.remove(peerIp)
        store.deletePeer(peerIp)
        try {
            appContext.getSharedPreferences("known_peers", Context.MODE_PRIVATE)
                .edit().remove(peerIp).apply()
        } catch (_: Exception) {}
        try {
            appContext.getSharedPreferences("known_groups", Context.MODE_PRIVATE)
                .edit().remove(peerIp).apply()
        } catch (_: Exception) {}
        peerNames.remove(peerIp)
        peerGroups.remove(peerIp)
        Prefs.clearSession(appContext, peerIp)
        _changed.tryEmit(peerIp)
    }

    fun records(peerIp: String): MutableList<ChatRecord> =
        cache.getOrPut(peerIp) { store.load(peerIp) }

    fun historyPage(peerIp: String, start: Int, limit: Int): List<ChatRecord> =
        store.loadPage(peerIp, start, limit)

    fun search(peerIp: String, query: String, limit: Int = 200): List<ChatRecord> =
        store.search(peerIp, query, limit)

    fun searchAll(query: String, limit: Int = 300): List<SearchHit> =
        store.searchAll(query, limit).map { (ip, rec) -> SearchHit(ip, displayName(ip), rec) }

    fun exportConversation(peerIp: String): String = store.exportText(peerIp)

    fun fileItems(): List<Pair<String, ChatRecord>> = store.allPeerFiles()

    fun lastRecord(peerIp: String): ChatRecord? =
        records(peerIp).lastOrNull { it.dir != ChatRecord.DIR_SYS }

    fun unreadCount(peerIp: String): Int = unread[peerIp] ?: 0

    fun clearUnread(peerIp: String) {
        if ((unread[peerIp] ?: 0) != 0) {
            unread[peerIp] = 0
            _changed.tryEmit(peerIp)
        }
    }

    fun start() {
        loadKnownPeerNames()
        scope.launch {
            engine.peers.collect { list ->
                list.forEach {
                    val n = it.displayName
                    if (peerNames[it.ip] != n) {
                        peerNames[it.ip] = n
                        saveKnownPeerName(it.ip, n)
                    }
                    val group = it.group.ifBlank { "未分组" }
                    if (peerGroups[it.ip] != group) {
                        peerGroups[it.ip] = group
                        saveKnownPeerGroup(it.ip, group)
                    }
                }
            }
        }
        scope.launch {
            engine.incoming.collect { msg ->
                if (msg.outgoing) return@collect
                if (Prefs.isBlocked(appContext, msg.peerIp)) return@collect
                val rec = ChatRecord(ChatRecord.DIR_IN, ChatRecord.KIND_TEXT, text = msg.text, richStyle = msg.richStyle)
                FeiqRichText.TOKEN.findAll(msg.text).forEach {
                    if (it.groupValues[1] == "inline") pendingInlineText["${msg.peerIp}|${it.groupValues[2]}"] = rec
                }
                appendIncoming(msg.peerIp, rec, msg.text.replace(FeiqRichText.TOKEN, "[表情]"))
            }
        }
        scope.launch {
            engine.inlineImages.collect { img ->
                if (Prefs.isBlocked(appContext, img.peerIp)) return@collect
                val path = try {
                    val f = File(store.imageDir(), "inline_${img.imageId}_${img.data.size}.img")
                    f.writeBytes(img.data)
                    f.absolutePath
                } catch (_: Exception) {
                    null
                }
                if (path != null) {
                    val rec = pendingInlineText.remove("${img.peerIp}|${img.imageId}")
                    if (rec != null) {
                        rec.text = rec.text.replace(
                            FeiqRichText.inlineToken(img.imageId),
                            FeiqRichText.imageToken(path)
                        )
                        touch(img.peerIp)
                    } else {
                        appendIncoming(img.peerIp, ChatRecord(ChatRecord.DIR_IN, ChatRecord.KIND_IMAGE, imagePath = path), "[图片]")
                    }
                }
            }
        }
        scope.launch {
            engine.delivered.collect { (ip, pktNo) ->
                val list = records(ip)
                val rec = list.lastOrNull { it.dir == ChatRecord.DIR_OUT && it.msgId == pktNo }
                    ?: list.lastOrNull { it.dir == ChatRecord.DIR_OUT && it.status == ChatRecord.STATUS_SENDING }
                if (rec != null && rec.status != ChatRecord.STATUS_SENT) {
                    rec.status = ChatRecord.STATUS_SENT
                    touch(ip)
                }
            }
        }
        scope.launch {
            engine.incomingFiles.collect { f ->
                if (Prefs.isBlocked(appContext, f.peerIp)) return@collect
                val rec = ChatRecord(
                    ChatRecord.DIR_IN, ChatRecord.KIND_FILE,
                    fileName = f.name, fileSize = f.size,
                    fileId = f.fileId, packetId = f.packetId,
                    fileStatus = ChatRecord.FS_PENDING,
                    isDir = f.isDir,
                )
                pendingIncoming["${f.peerIp}|${f.packetId}|${f.fileId}"] = f
                appendIncoming(f.peerIp, rec, "[文件] ${f.name}")
                if (Prefs.shouldAutoReceiveFile(appContext, f.peerIp)) acceptFile(f.peerIp, rec)
            }
        }
    }

    fun downloadDir(): File {
        val dir = File(appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "received")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun acceptFile(peerIp: String, rec: ChatRecord) {
        val key = "$peerIp|${rec.packetId}|${rec.fileId}"
        val f = pendingIncoming[key] ?: return
        if (rec.fileStatus == ChatRecord.FS_RECEIVING || rec.fileStatus == ChatRecord.FS_DONE) return
        rec.fileStatus = ChatRecord.FS_RECEIVING
        touch(peerIp)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    if (f.isDir) {
                        val dest = uniqueFile(downloadDir(), f.name)
                        dest.mkdirs()
                        val n = engine.downloadDir(f, dest)
                        if (n >= 0) dest.absolutePath else {
                            dest.deleteRecursively()
                            null
                        }
                    } else {
                        val dest = uniqueFile(downloadDir(), f.name)
                        val n = dest.outputStream().use { out -> engine.downloadFile(f, out) }
                        if (n >= 0) dest.absolutePath else {
                            dest.delete()
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (saved != null) {
                rec.fileStatus = ChatRecord.FS_DONE
                rec.filePath = saved
            } else {
                rec.fileStatus = ChatRecord.FS_FAILED
            }
            pendingIncoming.remove(key)
            touch(peerIp)
        }
    }

    fun rejectFile(peerIp: String, rec: ChatRecord) {
        rec.fileStatus = ChatRecord.FS_REJECTED
        pendingIncoming.remove("$peerIp|${rec.packetId}|${rec.fileId}")
        touch(peerIp)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base($i)$ext")
            i++
        }
        return f
    }

    fun append(peerIp: String, rec: ChatRecord) {
        if (Prefs.isBlocked(appContext, peerIp)) return
        records(peerIp).add(rec)
        store.save(peerIp, records(peerIp))
        _changed.tryEmit(peerIp)
    }

    fun appendSending(peerIp: String, rec: ChatRecord, timeoutMs: Long = 6000) {
        append(peerIp, rec)
        scope.launch {
            delay(timeoutMs)
            if (rec.status == ChatRecord.STATUS_SENDING) {
                rec.status = ChatRecord.STATUS_FAILED
                touch(peerIp)
            }
        }
    }

    fun deleteRecord(peerIp: String, rec: ChatRecord) {
        if (records(peerIp).remove(rec)) {
            store.save(peerIp, records(peerIp))
            _changed.tryEmit(peerIp)
        }
    }

    fun clearRecords(peerIp: String) {
        records(peerIp).clear()
        store.save(peerIp, records(peerIp))
        _changed.tryEmit(peerIp)
    }

    fun touch(peerIp: String) {
        store.save(peerIp, records(peerIp))
        _changed.tryEmit(peerIp)
    }

    private fun appendIncoming(peerIp: String, rec: ChatRecord, preview: String) {
        records(peerIp).add(rec)
        store.save(peerIp, records(peerIp))
        if (ChatActivity.currentPeer != peerIp) {
            unread[peerIp] = (unread[peerIp] ?: 0) + 1
            if (Prefs.notifyEnabled(appContext) && !Prefs.isMuted(appContext, peerIp)) notify(peerIp, preview)
        }
        _changed.tryEmit(peerIp)
    }

    private fun notify(peerIp: String, preview: String) {
        try {
            val name = peerNames[peerIp] ?: peerIp
            val intent = Intent(appContext, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_IP, peerIp)
                .putExtra(ChatActivity.EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = android.app.PendingIntent.getActivity(
                appContext, peerIp.hashCode(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val sound = Prefs.notifySound(appContext)
            val vibrate = Prefs.notifyVibrate(appContext)
            val channelId = channelFor(sound, vibrate)
            val b = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(name)
                .setContentText(preview)
                .setAutoCancel(true)
                .setContentIntent(pi)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                var defaults = 0
                if (vibrate) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
                if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
                b.setDefaults(defaults)
            }
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(peerIp.hashCode(), b.build())
        } catch (_: Exception) {}
    }

    private fun channelFor(sound: Boolean, vibrate: Boolean): String {
        val id = "msg_${if (sound) "s" else "n"}_${if (vibrate) "v" else "n"}"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(id) == null) {
                val label = "新消息" + (if (sound) "-声音" else "") + (if (vibrate) "-振动" else "")
                val ch = NotificationChannel(id, label, NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(vibrate)
                if (!sound) ch.setSound(null, null)
                mgr.createNotificationChannel(ch)
            }
        }
        return id
    }
}

package com.feiq.droid.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * 简单的聊天记录持久化：每个对端一个 JSON 文件，存在内部存储。
 * 免依赖（用 org.json），适合中小规模聊天记录。
 */
class MessageStore(context: Context) {
    private val dir = File(context.filesDir, "chat").apply { mkdirs() }
    private val imgDir = File(context.filesDir, "images").apply { mkdirs() }

    /** 内联图片持久化目录（cache 会被清，这里用 filesDir）。 */
    fun imageDir(): File = imgDir

    private fun fileFor(peerIp: String) = File(dir, sanitize(peerIp) + ".json")

    /** 列出磁盘上所有有聊天记录的对端 IP（供会话列表用）。 */
    fun listPeers(): List<String> {
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { it.name.removeSuffix(".json") }
            ?: emptyList()
    }

    /** 删除某对端的聊天文件。 */
    fun deletePeer(peerIp: String) {
        try { fileFor(peerIp).delete() } catch (_: Exception) {}
    }

    fun load(peerIp: String): MutableList<ChatRecord> {
        val f = fileFor(peerIp)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { ChatRecord.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w("MessageStore", "load ${f.name} failed: ${e.message}")
            mutableListOf()
        }
    }

    fun loadPage(peerIp: String, fromIndex: Int, limit: Int): List<ChatRecord> {
        val all = load(peerIp)
        if (all.isEmpty()) return emptyList()
        val start = fromIndex.coerceAtLeast(0).coerceAtMost(all.size)
        val end = (start + limit).coerceAtMost(all.size)
        return if (start >= end) emptyList() else all.subList(start, end)
    }

    fun search(peerIp: String, query: String, limit: Int = 200): List<ChatRecord> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return load(peerIp).asReversed().filter {
            it.text.contains(q, ignoreCase = true) ||
                it.fileName.contains(q, ignoreCase = true)
        }.take(limit)
    }

    fun searchAll(query: String, limit: Int = 300): List<Pair<String, ChatRecord>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, ChatRecord>>()
        listPeers().forEach { ip ->
            load(ip).asReversed().forEach { rec ->
                if (rec.text.contains(q, ignoreCase = true) ||
                    rec.fileName.contains(q, ignoreCase = true)
                ) {
                    out.add(ip to rec)
                }
            }
        }
        return out.sortedByDescending { it.second.time }.take(limit)
    }

    fun exportText(peerIp: String): String {
        val sb = StringBuilder()
        load(peerIp).forEach { r ->
            sb.append('[').append(r.time).append("] ")
            when (r.dir) {
                ChatRecord.DIR_OUT -> sb.append("OUT ")
                ChatRecord.DIR_IN -> sb.append("IN ")
                else -> sb.append("SYS ")
            }
            when (r.kind) {
                ChatRecord.KIND_FILE -> sb.append("[FILE] ").append(r.fileName)
                ChatRecord.KIND_IMAGE -> sb.append("[IMAGE]").append(r.imagePath ?: "")
                else -> sb.append(r.text)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun allPeerFiles(): List<Pair<String, ChatRecord>> {
        val out = ArrayList<Pair<String, ChatRecord>>()
        listPeers().forEach { ip ->
            load(ip).forEach { rec ->
                if (rec.kind == ChatRecord.KIND_FILE) out.add(ip to rec)
            }
        }
        return out
    }

    /** 覆盖保存整份记录（追加后调用；记录量不大，整写简单可靠）。 */
    fun save(peerIp: String, list: List<ChatRecord>) {
        try {
            val arr = JSONArray()
            list.takeLast(MAX_KEEP).forEach { arr.put(it.toJson()) }
            fileFor(peerIp).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w("MessageStore", "save failed: ${e.message}")
        }
    }

    private fun sanitize(ip: String) = ip.replace(Regex("[^0-9A-Za-z._-]"), "_")

    companion object { private const val MAX_KEEP = 50000 }
}

package com.feiq.droid.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.ChatRecord
import com.feiq.droid.core.NetworkInfo
import com.feiq.droid.core.Prefs
import com.feiq.droid.databinding.ActivityContactGroupsBinding
import com.feiq.droid.net.FeiqRichText

class ContactGroupsActivity : BaseActivity() {
    private lateinit var b: ActivityContactGroupsBinding
    private lateinit var adapter: GroupAdapter
    private val rows = mutableListOf<Row>()
    private val collapsed = mutableSetOf<String>()

    private data class PeerRow(val ip: String, val name: String, val group: String, val online: Boolean)
    private sealed class Row {
        data class Header(val group: String, val total: Int, val online: Int) : Row()
        data class Peer(val item: PeerRow) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityContactGroupsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }
        adapter = GroupAdapter()
        b.groupList.adapter = adapter
        b.groupList.setOnItemClickListener { _, _, pos, _ ->
            when (val row = rows[pos]) {
                is Row.Header -> {
                    if (collapsed.contains(row.group)) collapsed.remove(row.group) else collapsed.add(row.group)
                    rebuild()
                }
                is Row.Peer -> openChat(row.item)
            }
        }
        b.groupList.setOnItemLongClickListener { _, _, pos, _ ->
            (rows.getOrNull(pos) as? Row.Peer)?.let { showPeerMenu(it.item) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        if (!App.isStarted()) return
        val selfIp = NetworkInfo.localIp() ?: "未知"
        b.selfInfo.text = "自己：${Prefs.nick(this)}  ·  ${Prefs.group(this)}  ·  $selfIp"
        val onlineIps = App.engine().peers.value.map { it.ip }.toSet()
        val peers = App.repo().conversationPeers(onlineIps).map { ip ->
            val rawGroup = App.repo().peerGroup(ip)
            val group = when {
                App.repo().isBlocked(ip) -> "黑名单"
                rawGroup.isBlank() || rawGroup == "未分组" -> "我的好友"
                else -> rawGroup
            }
            PeerRow(ip, App.repo().displayName(ip), group, ip in onlineIps)
        }
        val grouped = peers.groupBy { it.group }.toSortedMap(compareBy<String> { it == "黑名单" }.thenBy { it })
        rows.clear()
        grouped.forEach { (group, members) ->
            rows.add(Row.Header(group, members.size, members.count { it.online }))
            if (!collapsed.contains(group)) rows.addAll(members.sortedWith(compareByDescending<PeerRow> { it.online }.thenBy { it.name }).map { Row.Peer(it) })
        }
        adapter.notifyDataSetChanged()
    }

    private fun openChat(peer: PeerRow) {
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_IP, peer.ip)
            .putExtra(ChatActivity.EXTRA_NAME, peer.name))
    }

    private fun showPeerMenu(peer: PeerRow) {
        val repo = App.repo()
        FeiqBottomSheet.menu(this, peer.name, "${peer.group}  ·  ${peer.ip}", listOf(
            FeiqBottomSheet.Action("打开聊天", R.drawable.ic_chat) { openChat(peer) },
            FeiqBottomSheet.Action(if (repo.isPinned(peer.ip)) "取消置顶" else "置顶", R.drawable.ic_badge) {
                repo.setPinned(peer.ip, !repo.isPinned(peer.ip)); rebuild()
            },
            FeiqBottomSheet.Action(if (repo.isMuted(peer.ip)) "关闭免打扰" else "免打扰", R.drawable.ic_settings) {
                repo.setMuted(peer.ip, !repo.isMuted(peer.ip)); rebuild()
            },
            FeiqBottomSheet.Action(if (repo.isBlocked(peer.ip)) "移出黑名单" else "加入黑名单", R.drawable.ic_delete, danger = !repo.isBlocked(peer.ip)) {
                repo.setBlocked(peer.ip, !repo.isBlocked(peer.ip)); rebuild()
            },
        ))
    }

    private fun showGroupMessage(group: String) {
        val ips = rows.mapNotNull { (it as? Row.Peer)?.item?.takeIf { p -> p.group == group }?.ip }
        if (ips.isEmpty()) {
            toast("该分组没有联系人")
            return
        }
        FeiqBottomSheet.input(
            this,
            "发送到 $group",
            "会逐个发送普通飞秋消息，不是私有群聊协议。",
            hint = "输入消息",
            confirmText = "发送",
            onConfirm = { text ->
                if (text.isBlank() || !App.isStarted()) return@input
                App.engine().sendMessageToMany(ips, text, currentRichStyle()).forEach { (ip, pktNo) ->
                    App.repo().appendSending(ip, ChatRecord(ChatRecord.DIR_OUT, ChatRecord.KIND_TEXT, text = text, msgId = pktNo, status = ChatRecord.STATUS_SENDING))
                }
                toast("已发送到分组成员")
            }
        )
    }

    private fun currentRichStyle(): FeiqRichText.FontStyle? {
        if (!Prefs.richTextEnabled(this)) return null
        return FeiqRichText.FontStyle(
            height = Prefs.richHeight(this),
            weight = if (Prefs.richBold(this)) 700 else 400,
            italic = Prefs.richItalic(this),
            underline = Prefs.richUnderline(this),
            colorRef = Prefs.richColor(this),
        )
    }

    private inner class GroupAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val v = convertView ?: LayoutInflater.from(this@ContactGroupsActivity).inflate(R.layout.item_group_header, parent, false)
                    v.findViewById<TextView>(R.id.groupArrow).text = if (collapsed.contains(row.group)) "▸" else "▾"
                    v.findViewById<TextView>(R.id.groupTitle).text = "${row.group} (${row.total})  在线 ${row.online}"
                    v.findViewById<TextView>(R.id.groupAction).setOnClickListener { showGroupMessage(row.group) }
                    v
                }
                is Row.Peer -> {
                    val v = convertView ?: LayoutInflater.from(this@ContactGroupsActivity).inflate(R.layout.item_group_peer, parent, false)
                    val p = row.item
                    val avatar = v.findViewById<TextView>(R.id.avatar)
                    avatar.text = UiUtil.initial(p.name)
                    val bg = (avatar.background?.mutate() as? GradientDrawable) ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    bg.setColor(if (p.online) UiUtil.avatarColor(p.name) else 0xFFAAAAAA.toInt())
                    avatar.background = bg
                    v.findViewById<View>(R.id.onlineDot).visibility = if (p.online) View.VISIBLE else View.GONE
                    v.findViewById<TextView>(R.id.peerName).text = p.name
                    val preview = v.findViewById<TextView>(R.id.peerPreview)
                    val last = App.repo().lastRecord(p.ip)
                    if (last != null) PreviewRenderer.bind(preview, last) else preview.text = "${p.ip}  ·  ${if (p.online) "在线" else "离线"}"
                    v
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

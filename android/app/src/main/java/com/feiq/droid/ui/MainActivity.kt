package com.feiq.droid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.AvatarStore
import com.feiq.droid.core.ChatRecord
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.FeiqService
import com.feiq.droid.core.NetworkInfo
import com.feiq.droid.core.Prefs
import com.feiq.droid.databinding.ActivityMainBinding
import com.feiq.droid.net.FeiqRichText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding

    private data class Conv(val ip: String, val name: String, val group: String, val online: Boolean)

    private sealed class MainRow {
        data class Section(val title: String) : MainRow()
        data class Peer(val conv: Conv) : MainRow()
    }

    private val allConvs = mutableListOf<Conv>()
    private val rows = mutableListOf<MainRow>()
    private lateinit var adapter: ConvAdapter
    private var onlineIps: Set<String> = emptySet()
    private var searchQuery = ""
    private var pendingShare: Intent? = null
    private var avatarTargetIp: String? = null
    private var avatarTargetSelf = false
    private var cameraAvatarUri: Uri? = null
    private val avatarClicks = HashMap<String, Long>()
    private var showingMeTab: Boolean? = null

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val ip = avatarTargetIp
            val uri = res.data?.data
            if (avatarTargetSelf && uri != null) {
                saveSelfAvatar(uri)
                rebuildConvs()
            } else if (ip != null && uri != null) {
                saveAvatar(ip, uri)
                rebuildConvs()
            }
        }
    }

    private val takeAvatar = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraAvatarUri
        if (ok && uri != null) {
            val ip = avatarTargetIp
            if (avatarTargetSelf) saveSelfAvatar(uri) else if (ip != null) saveAvatar(ip, uri)
            rebuildConvs()
        }
        cameraAvatarUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ConvAdapter()
        binding.peerList.adapter = adapter
        binding.peerList.setOnItemClickListener { _, _, pos, _ ->
            (rows.getOrNull(pos) as? MainRow.Peer)?.let { openChat(it.conv) }
        }
        binding.peerList.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos) as? MainRow.Peer ?: return@setOnItemLongClickListener true
            showConvMenu(row.conv)
            true
        }
        setupSearch()
        setupBottomCenter()

        captureShareIntent(intent)
        requestNotifPermissionThenStart()
        observePeers()
        observeChanged()
        observeShake()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureShareIntent(intent)
        if (App.isStarted()) showSharePeerPicker()
    }

    override fun onResume() {
        super.onResume()
        rebuildConvs()
        updateStatus()
        updateMeProfile()
        if (App.isStarted() && pendingShare != null) binding.root.post { showSharePeerPicker() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> if (App.isStarted()) {
                App.engine().refresh()
                toast("\u5df2\u5237\u65b0")
            }
            R.id.action_files -> startActivity(Intent(this, FileManagerActivity::class.java))
            R.id.action_global_search -> showGlobalSearchDialog()
            R.id.action_group_send -> showGroupDialog()
            R.id.action_add_ip -> showAddIpDialog()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun captureShareIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) pendingShare = i
    }

    private fun showSharePeerPicker() {
        val share = pendingShare ?: return
        val peers = visiblePeers()
        if (peers.isEmpty()) {
            toast("\u5148\u6dfb\u52a0\u6216\u53d1\u73b0\u4e00\u4e2a\u8054\u7cfb\u4eba")
            return
        }
        val labels = peers.map { "${it.name}  ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\u5206\u4eab\u5230\u98de\u79cb")
            .setItems(labels) { _, which ->
                sendSharedTo(peers[which].ip, share)
                pendingShare = null
            }
            .setNegativeButton("\u53d6\u6d88", null)
            .show()
    }

    private fun sendSharedTo(peerIp: String, share: Intent) {
        if (!App.isStarted()) return
        val text = share.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val style = currentRichStyle()
            val pktNo = App.engine().sendMessage(peerIp, text, style)
            App.repo().appendSending(peerIp, ChatRecord(
                ChatRecord.DIR_OUT, ChatRecord.KIND_TEXT, text = text,
                msgId = pktNo, status = ChatRecord.STATUS_SENDING,
                richStyle = style?.tagBody().orEmpty()
            ))
        }
        val uris = collectUris(share)
        if (uris.isNotEmpty()) sendUrisAsFiles(peerIp, uris)
        toast("\u5df2\u53d1\u9001")
    }

    private fun collectUris(data: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        (data.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
        data.data?.let { uris.add(it) }
        return uris.distinct()
    }

    private fun sendUrisAsFiles(peerIp: String, uris: List<Uri>) {
        val items = uris.map { uri ->
            val (name, size) = queryNameSize(uri)
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            FeiqEngine.SendItem(
                name = name,
                size = size,
                mtime = System.currentTimeMillis() / 1000,
                openStream = { contentResolver.openInputStream(uri)!! },
            )
        }
        items.forEachIndexed { idx, item ->
            App.repo().append(peerIp, ChatRecord(ChatRecord.DIR_OUT, ChatRecord.KIND_FILE,
                fileName = item.name, fileSize = item.size,
                filePath = uris[idx].toString(), fileStatus = ChatRecord.FS_DONE))
        }
        App.engine().sendFiles(peerIp, items)
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        return name to size
    }

    private fun openChat(c: Conv) {
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_IP, c.ip)
            .putExtra(ChatActivity.EXTRA_NAME, c.name))
    }

    private fun openChat(ip: String, name: String, targetId: String? = null) {
        val intent = Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_IP, ip)
            .putExtra(ChatActivity.EXTRA_NAME, name)
        if (!targetId.isNullOrBlank()) intent.putExtra(ChatActivity.EXTRA_TARGET_ID, targetId)
        startActivity(intent)
    }

    private fun showGlobalSearchDialog() {
        startActivity(Intent(this, GlobalSearchActivity::class.java))
    }

    private fun showGlobalSearchResults(q: String) {
        val results = App.repo().searchAll(q)
        if (results.isEmpty()) {
            toast("没有找到")
            return
        }
        val labels = results.map {
            "${it.peerName}  ${UiUtil.chatTime(it.record.time)}\n${it.record.preview().take(80)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("全局搜索结果")
            .setItems(labels) { _, which ->
                val hit = results[which]
                openChat(hit.peerIp, hit.peerName, hit.record.id)
            }
            .show()
    }

    private fun showGroupDialog() {
        startActivity(Intent(this, ContactGroupsActivity::class.java))
    }

    private fun showGroupMembers(group: String, members: List<Conv>) {
        if (members.isEmpty()) return
        val labels = members.map {
            "${if (it.online) "在线" else "离线"}  ${it.name}  ${it.ip}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(group)
            .setItems(labels) { _, which -> openChat(members[which]) }
            .setPositiveButton("分组消息") { _, _ ->
                showGroupMessageInput(group, members.map { it.ip })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGroupMessageInput(group: String, ips: List<String>) {
        val input = EditText(this).apply {
            hint = "输入发给该分组成员的消息"
            minLines = 3
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("$group (${ips.size})")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                val style = currentRichStyle()
                App.engine().sendMessageToMany(ips, text, style).forEach { (ip, pktNo) ->
                    App.repo().appendSending(ip, ChatRecord(
                        ChatRecord.DIR_OUT, ChatRecord.KIND_TEXT,
                        text = text, msgId = pktNo, status = ChatRecord.STATUS_SENDING,
                        richStyle = style?.tagBody().orEmpty()
                    ))
                }
                toast("已发送到分组成员")
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun showAddIpDialog() {
        FeiqBottomSheet.input(
            this,
            "添加 IP",
            "广播被路由器或防火墙挡住时，可以手动指定对端地址。",
            hint = "192.168.2.148",
            confirmText = "添加",
            inputType = android.text.InputType.TYPE_CLASS_PHONE,
            onConfirm = { ip ->
                if (!isValidIpv4(ip)) {
                    toast("IP \u683c\u5f0f\u4e0d\u6b63\u786e")
                    return@input
                }
                App.repo().addManualPeer(ip)
                if (App.isStarted()) App.engine().probePeer(ip)
                rebuildConvs()
                toast("\u5df2\u6dfb\u52a0\u5e76\u63a2\u6d4b $ip")
            }
        )
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                (it.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun showConvMenu(c: Conv) {
        val repo = App.repo()
        FeiqBottomSheet.menu(this, c.name, "${c.group}  ·  ${c.ip}", listOf(
            FeiqBottomSheet.Action("打开聊天", R.drawable.ic_chat) { openChat(c) },
            FeiqBottomSheet.Action(if (repo.isPinned(c.ip)) "取消置顶" else "置顶", R.drawable.ic_badge) { togglePin(c) },
            FeiqBottomSheet.Action(if (repo.isMuted(c.ip)) "关闭免打扰" else "免打扰", R.drawable.ic_settings) { toggleMute(c) },
            FeiqBottomSheet.Action(if (repo.isBlocked(c.ip)) "移出黑名单" else "加入黑名单", R.drawable.ic_delete, danger = !repo.isBlocked(c.ip)) { toggleBlock(c) },
            FeiqBottomSheet.Action("自动接收规则", R.drawable.ic_file) { showFileRule(c) },
            FeiqBottomSheet.Action("自定义头像", R.drawable.ic_avatar) { pickAvatarFor(c) },
            FeiqBottomSheet.Action("备份导出", R.drawable.ic_file) { exportConversation(c) },
            FeiqBottomSheet.Action("删除会话", R.drawable.ic_delete, danger = true) { confirmDelete(c) },
        ))
    }

    private fun togglePin(c: Conv) {
        App.repo().setPinned(c.ip, !App.repo().isPinned(c.ip))
        rebuildConvs()
    }

    private fun toggleMute(c: Conv) {
        App.repo().setMuted(c.ip, !App.repo().isMuted(c.ip))
        rebuildConvs()
    }

    private fun toggleBlock(c: Conv) {
        App.repo().setBlocked(c.ip, !App.repo().isBlocked(c.ip))
        rebuildConvs()
    }

    private fun showFileRule(c: Conv) {
        val labels = listOf("跟随全局", "每次询问", "自动接收", "永不接收")
        FeiqBottomSheet.menu(this, "文件接收规则", c.name, labels.mapIndexed { idx, label ->
            FeiqBottomSheet.Action(
                label = if (idx == App.repo().fileRule(c.ip)) "$label  · 当前" else label,
                iconRes = R.drawable.ic_file,
                danger = idx == 3,
            ) { App.repo().setFileRule(c.ip, idx) }
        })
    }

    private fun pickAvatarFor(c: Conv) {
        avatarTargetIp = c.ip
        avatarTargetSelf = false
        showAvatarSourceMenu("自定义头像")
    }

    private fun showSelfAvatarMenu() {
        avatarTargetIp = null
        avatarTargetSelf = true
        val hasAvatar = App.repo().selfAvatarPath().isNotBlank()
        val actions = mutableListOf(
            FeiqBottomSheet.Action("拍照", R.drawable.ic_avatar) { takeAvatarPhoto() },
            FeiqBottomSheet.Action("从相册选择", R.drawable.ic_image) { pickAvatarFromAlbum() },
        )
        if (hasAvatar) actions.add(FeiqBottomSheet.Action("清除头像", R.drawable.ic_delete, danger = true) {
            App.repo().clearSelfAvatar()
            rebuildConvs()
            toast("头像已清除")
        })
        FeiqBottomSheet.menu(this, "本机头像", "仅用于手机端本机显示，电脑端头像协议未确认。", actions)
    }

    private fun showAvatarSourceMenu(title: String) {
        FeiqBottomSheet.menu(this, title, null, listOf(
            FeiqBottomSheet.Action("拍照", R.drawable.ic_avatar) { takeAvatarPhoto() },
            FeiqBottomSheet.Action("从相册选择", R.drawable.ic_image) { pickAvatarFromAlbum() },
        ))
    }

    private fun pickAvatarFromAlbum() {
        pickAvatar.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        })
    }

    private fun takeAvatarPhoto() {
        try {
            val dir = File(filesDir, "camera").apply { mkdirs() }
            val f = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
            cameraAvatarUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            cameraAvatarUri?.let { takeAvatar.launch(it) }
        } catch (e: Exception) {
            toast("无法打开相机: ${e.message}")
        }
    }

    private fun saveAvatar(peerIp: String, uri: Uri) {
        try {
            val path = AvatarStore.savePeerAvatar(this, peerIp, uri) ?: throw IllegalStateException("图片无法读取")
            App.repo().setAvatarPath(peerIp, path)
            toast("\u5934\u50cf\u5df2\u66f4\u65b0")
        } catch (e: Exception) {
            toast("\u5934\u50cf\u4fdd\u5b58\u5931\u8d25: ${e.message}")
        }
    }

    private fun saveSelfAvatar(uri: Uri) {
        try {
            val path = AvatarStore.saveSelfAvatar(this, uri) ?: throw IllegalStateException("图片无法读取")
            App.repo().setSelfAvatarPath(path)
            toast("本机头像已更新，仅本机显示")
        } catch (e: Exception) {
            toast("头像保存失败: ${e.message}")
        }
    }

    private fun onAvatarTapped(c: Conv) {
        val now = System.currentTimeMillis()
        val last = avatarClicks[c.ip] ?: 0L
        avatarClicks[c.ip] = now
        if (now - last <= 450L) {
            if (!App.isStarted()) return
            App.engine().sendShake(c.ip)
            playShake()
            toast("已发送抖一抖")
        }
    }

    private fun exportConversation(c: Conv) {
        try {
            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "backup").apply { mkdirs() }
            val f = File(dir, "chat_${c.ip}_${System.currentTimeMillis()}.txt")
            f.writeText(App.repo().exportConversation(c.ip))
            toast("\u5df2\u5bfc\u51fa: ${f.name}")
        } catch (e: Exception) {
            toast("\u5bfc\u51fa\u5931\u8d25: ${e.message}")
        }
    }

    private fun confirmDelete(c: Conv) {
        FeiqBottomSheet.menu(this, "删除会话", "将删除与 ${c.name} 的聊天记录。", listOf(
            FeiqBottomSheet.Action("删除", R.drawable.ic_delete, danger = true) {
                App.repo().deleteConversation(c.ip)
                rebuildConvs()
            },
        ))
    }

    private fun requestNotifPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startEngine()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startEngine()
    }

    private fun startEngine() {
        FeiqService.start(this, Prefs.nick(this), Prefs.group(this))
        updateStatus()
        binding.root.postDelayed({
            if (App.isStarted() && pendingShare != null) showSharePeerPicker()
        }, 400)
    }

    private fun updateStatus() {
        val ip = NetworkInfo.localIp() ?: "\u672a\u77e5"
        binding.statusText.text = "${Prefs.nick(this)} · $ip · 端口 ${Prefs.port(this)}"
        binding.headerOnline.text = "\u5728\u7ebf ${onlineIps.size}"
        updateMeProfile()
    }

    private fun setupBottomCenter() {
        binding.tabChats.setOnClickListener { showMainTab(showMe = false) }
        binding.tabMe.setOnClickListener { showMainTab(showMe = true) }
        binding.cardFiles.setOnClickListener { startActivity(Intent(this, FileManagerActivity::class.java)) }
        binding.cardGroups.setOnClickListener { showGroupDialog() }
        binding.cardMyGroup.setOnClickListener { showMyGroupDialog() }
        binding.cardAddIp.setOnClickListener { showAddIpDialog() }
        binding.cardProfile.setOnClickListener { showProfileMenu() }
        binding.cardSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.rowGlobalSearch.setOnClickListener { showGlobalSearchDialog() }
        binding.rowRefresh.setOnClickListener {
            if (App.isStarted()) {
                App.engine().refresh()
                toast("已刷新")
            }
        }
        showMainTab(showMe = false)
        updateMeProfile()
    }

    private fun showMainTab(showMe: Boolean) {
        if (showingMeTab == showMe) return
        showingMeTab = showMe
        binding.chatPage.visibility = if (showMe) View.GONE else View.VISIBLE
        binding.mePage.visibility = if (showMe) View.VISIBLE else View.GONE
        val selectedColor = androidx.core.content.ContextCompat.getColor(this, R.color.primary)
        val normalColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
        setBottomTab(binding.tabChats, binding.tabChatsIcon, binding.tabChatsLabel, !showMe, selectedColor, normalColor)
        setBottomTab(binding.tabMe, binding.tabMeIcon, binding.tabMeLabel, showMe, selectedColor, normalColor)
    }

    private fun setBottomTab(
        tab: View,
        icon: ImageView,
        label: TextView,
        selected: Boolean,
        selectedColor: Int,
        normalColor: Int
    ) {
        val color = if (selected) selectedColor else normalColor
        tab.setBackgroundResource(if (selected) R.drawable.bg_nav_item_selected else android.R.color.transparent)
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    private fun updateMeProfile() {
        if (!::binding.isInitialized) return
        binding.meNick.text = Prefs.nick(this)
        val ip = NetworkInfo.localIp() ?: "未知"
        binding.meMeta.text = "${Prefs.group(this)}  ·  $ip  ·  端口 ${Prefs.port(this)}"
        binding.meAvatar.text = UiUtil.initial(Prefs.nick(this))
    }

    private fun showProfileMenu() {
        FeiqBottomSheet.menu(this, "个人信息", "${Prefs.nick(this)}  ·  ${Prefs.group(this)}  ·  端口 ${Prefs.port(this)}", listOf(
            FeiqBottomSheet.Action("修改头像", R.drawable.ic_avatar) { showSelfAvatarMenu() },
            FeiqBottomSheet.Action("修改昵称", R.drawable.ic_edit) { showNickDialog() },
            FeiqBottomSheet.Action("修改分组", R.drawable.ic_badge) { showMyGroupDialog() },
            FeiqBottomSheet.Action("协议端口", R.drawable.ic_settings) { showPortDialogFromProfile() },
        ))
    }

    private fun showNickDialog() {
        FeiqBottomSheet.input(
            this,
            "修改昵称",
            "电脑端飞秋会用这个名称显示你的手机。",
            hint = "昵称",
            value = Prefs.nick(this),
            confirmText = "保存",
            onConfirm = { raw ->
                val nick = raw.trim()
                if (nick.isBlank()) {
                    toast("昵称不能为空")
                    return@input
                }
                Prefs.setNick(this, nick)
                if (App.isStarted()) {
                    App.engine().updateProfile(nick, Prefs.group(this))
                    App.engine().refresh()
                }
                updateStatus()
                toast("昵称已更新")
            }
        )
    }

    private fun showPortDialogFromProfile() {
        FeiqBottomSheet.input(
            this,
            "协议端口",
            "修改后会重启网络监听并重新广播。",
            hint = "2425",
            value = Prefs.port(this).toString(),
            confirmText = "保存",
            inputType = android.text.InputType.TYPE_CLASS_NUMBER,
            onConfirm = { raw ->
                val port = raw.trim().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    toast("端口范围应为 1-65535")
                    return@input
                }
                if (port == Prefs.port(this)) return@input
                Prefs.setPort(this, port)
                if (App.isStarted()) App.engine().restartNetwork()
                updateStatus()
                toast("端口已更新并重新上线")
            }
        )
    }

    private fun showMyGroupDialog() {
        FeiqBottomSheet.input(
            this,
            "我的分组",
            "这里会写入飞秋上线广播的分组字段，电脑端按这个字段归类。",
            hint = "例如：安卓组",
            value = Prefs.group(this),
            confirmText = "保存",
            onConfirm = { raw ->
                val group = raw.ifBlank { "我的好友" }
                Prefs.setGroup(this, group)
                if (App.isStarted()) {
                    App.engine().updateProfile(Prefs.nick(this), group)
                    App.engine().refresh()
                }
                updateStatus()
                toast("分组已更新并重新广播")
            }
        )
    }

    private fun observePeers() {
        lifecycleScope.launch {
            while (!App.isStarted()) kotlinx.coroutines.delay(150)
            App.engine().peers.collectLatest { list ->
                onlineIps = list.map { it.ip }.toHashSet()
                rebuildConvs()
                updateStatus()
            }
        }
    }

    private fun observeChanged() {
        lifecycleScope.launch {
            while (!App.isStarted()) kotlinx.coroutines.delay(150)
            App.repo().changed.collectLatest { rebuildConvs() }
        }
    }

    private fun observeShake() {
        lifecycleScope.launch {
            while (!App.isStarted()) kotlinx.coroutines.delay(150)
            App.engine().shakeEvents.collectLatest {
                playShake()
                toast("收到抖一抖")
            }
        }
    }

    private fun playShake() {
        vibrateShort()
        val v = binding.root
        v.animate().cancel()
        v.translationX = 0f
        v.animate().translationX(18f).setDuration(45).withEndAction {
            v.animate().translationX(-18f).setDuration(45).withEndAction {
                v.animate().translationX(10f).setDuration(45).withEndAction {
                    v.animate().translationX(0f).setDuration(45).start()
                }.start()
            }.start()
        }.start()
    }

    private fun vibrateShort() {
        try {
            val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(180)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun rebuildConvs() {
        if (!App.isStarted()) return
        val repo = App.repo()
        allConvs.clear()
        allConvs.addAll(repo.conversationPeers(onlineIps).map { ip ->
            Conv(ip = ip, name = repo.displayName(ip), group = repo.peerGroup(ip), online = ip in onlineIps)
        })
        rebuildRows()
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                rebuildRows()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun rebuildRows() {
        if (!::adapter.isInitialized) return
        val repo = App.repo()
        val filtered = allConvs.filter { c ->
            val q = searchQuery
            val last = repo.lastRecord(c.ip)?.preview().orEmpty()
            val matchesQuery = q.isBlank() ||
                c.name.contains(q, ignoreCase = true) ||
                c.ip.contains(q, ignoreCase = true) ||
                c.group.contains(q, ignoreCase = true) ||
                last.contains(q, ignoreCase = true)
            matchesQuery
        }
        val conversations = filtered.filter { c ->
            repo.lastRecord(c.ip) != null || repo.unreadCount(c.ip) > 0 || repo.isPinned(c.ip)
        }
        val conversationIps = conversations.map { it.ip }.toHashSet()
        val onlineNew = filtered.filter { it.online && it.ip !in conversationIps }
        val usedIps = conversationIps + onlineNew.map { it.ip }.toSet()
        val others = filtered.filter { it.ip !in usedIps }

        rows.clear()
        appendSection("会话", conversations)
        appendSection("在线新联系人", onlineNew)
        appendSection("其他联系人", others)
        adapter.notifyDataSetChanged()
        binding.emptyView.visibility = if (rows.none { it is MainRow.Peer }) View.VISIBLE else View.GONE
        binding.peerList.visibility = if (rows.none { it is MainRow.Peer }) View.GONE else View.VISIBLE
    }

    private fun appendSection(title: String, peers: List<Conv>) {
        if (peers.isEmpty()) return
        rows.add(MainRow.Section("$title  ${peers.size}"))
        rows.addAll(peers.map { MainRow.Peer(it) })
    }

    private fun visiblePeers(): List<Conv> {
        if (rows.isEmpty()) rebuildRows()
        return rows.mapNotNull { (it as? MainRow.Peer)?.conv }
    }

    private inner class ConvAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is MainRow.Section) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is MainRow.Peer

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is MainRow.Section) {
                val v = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_list_section, parent, false)
                v.findViewById<TextView>(R.id.sectionTitle).text = row.title
                return v
            }
            val v = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_peer, parent, false)
            val c = (row as MainRow.Peer).conv
            val repo = App.repo()

            val avatarText = v.findViewById<TextView>(R.id.avatar)
            val avatarImage = v.findViewById<ImageView>(R.id.avatarImage)
            avatarText.setOnClickListener { onAvatarTapped(c) }
            avatarImage.setOnClickListener { onAvatarTapped(c) }
            val avatarPath = repo.avatarPath(c.ip)
            if (avatarPath.isNotBlank()) {
                avatarImage.setImageBitmap(BitmapFactory.decodeFile(avatarPath))
                avatarImage.visibility = View.VISIBLE
                avatarText.visibility = View.GONE
            } else {
                avatarImage.visibility = View.GONE
                avatarText.visibility = View.VISIBLE
                avatarText.text = UiUtil.initial(c.name)
                val bg = (avatarText.background?.mutate() as? GradientDrawable)
                    ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
                bg.setColor(if (c.online) UiUtil.avatarColor(c.name) else 0xFFAAAAAA.toInt())
                avatarText.background = bg
            }

            v.findViewById<View>(R.id.onlineDot).visibility = if (c.online) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.peerName).text = buildString {
                if (repo.isPinned(c.ip)) append("[\u9876] ")
                append(c.name)
                if (repo.isMuted(c.ip)) append("  \u514d\u6253\u6270")
                if (repo.isBlocked(c.ip)) append("  \u9ed1\u540d\u5355")
            }

            val last = repo.lastRecord(c.ip)
            val preview = v.findViewById<TextView>(R.id.peerPreview)
            if (last != null) PreviewRenderer.bind(preview, last) else preview.text = "${c.group}  ${if (c.online) "\u5728\u7ebf" else "\u79bb\u7ebf"}"
            v.findViewById<TextView>(R.id.peerTime).text = last?.let { UiUtil.listTime(it.time) } ?: ""

            val badge = v.findViewById<TextView>(R.id.unreadBadge)
            val unread = repo.unreadCount(c.ip)
            if (unread > 0) {
                badge.visibility = View.VISIBLE
                badge.text = if (unread > 99) "99+" else unread.toString()
            } else {
                badge.visibility = View.GONE
            }
            return v
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

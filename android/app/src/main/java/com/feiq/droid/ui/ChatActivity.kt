package com.feiq.droid.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spannable
import android.text.Spanned
import android.text.Editable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.appcompat.app.AppCompatDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.ChatRecord
import com.feiq.droid.core.FeiqEngine
import com.feiq.droid.core.Prefs
import com.feiq.droid.net.DirFileCodec
import com.feiq.droid.net.FeiqRichText
import com.feiq.droid.net.Protocol
import com.feiq.droid.databinding.ActivityChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLConnection

class ChatActivity : BaseActivity() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_NAME = "name"
        const val EXTRA_TARGET_ID = "target_id"
        private const val PAGE_SIZE = 120
        @Volatile var currentPeer: String? = null
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var peerIp: String
    private lateinit var adapter: ChatAdapter
    private var records: MutableList<ChatRecord> = mutableListOf()
    private var loadedCount = PAGE_SIZE
    private var pendingTargetId: String? = null
    private var highlightedRecordId: String? = null
    private var handlingTargetJump = false
    private val avatarClicks = HashMap<String, Long>()
    private var panelMode = PanelMode.NONE

    private enum class PanelMode { NONE, EMOJI, ACTIONS }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.let { collectAndSend(it) }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { sendFolder(it) }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { sendImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerIp = intent.getStringExtra(EXTRA_IP) ?: ""
        pendingTargetId = intent.getStringExtra(EXTRA_TARGET_ID)
        setSupportActionBar(binding.toolbar)
        val peerName = intent.getStringExtra(EXTRA_NAME) ?: peerIp
        supportActionBar?.title = peerName
        binding.peerMetaTitle.text = peerName
        binding.peerMetaSub.text = "局域网直连  $peerIp  ·  端口 ${Prefs.port(this)}"
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ChatAdapter()
        binding.msgList.adapter = adapter
        binding.btnEmoji.setOnClickListener { toggleEmojiPanel() }
        binding.btnPlusSend.setOnClickListener {
            if (binding.input.text.toString().trim().isNotEmpty()) doSend() else toggleActionPanel()
        }
        binding.input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && panelMode != PanelMode.NONE) hidePanel() }
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSendButton()
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.actionImage.setOnClickListener { hidePanel(); pickImageToSend() }
        binding.actionFile.setOnClickListener { hidePanel(); pickFilesToSend() }
        binding.actionFolder.setOnClickListener { hidePanel(); pickFolderToSend() }
        setupEmojiGrid()
        updateSendButton()

        refresh(scrollToBottom = pendingTargetId == null)
        pendingTargetId?.let { id ->
            handlingTargetJump = true
            binding.msgList.post { jumpToRecordId(id, highlight = true) }
        }
        observeChanged()
        observeProgress()
        observeShake()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_load_older -> loadOlder()
            R.id.action_search -> showSearchDialog()
            R.id.action_export -> exportConversation()
            R.id.action_rich_text -> showRichTextDialog()
            R.id.action_file_rule -> showFileRuleDialog()
            R.id.action_clear -> confirmClear()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        currentPeer = peerIp
        App.repo().clearUnread(peerIp)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(peerIp.hashCode())
        } catch (_: Exception) {}
        if (!handlingTargetJump) refresh(scrollToBottom = false)
    }

    override fun onPause() {
        super.onPause()
        if (currentPeer == peerIp) currentPeer = null
    }

    private fun refresh(scrollToBottom: Boolean) {
        val all = App.repo().records(peerIp)
        loadedCount = loadedCount.coerceAtMost(all.size).coerceAtLeast(minOf(PAGE_SIZE, all.size))
        records = all.takeLast(loadedCount).toMutableList()
        adapter.notifyDataSetChanged()
        if (records.isNotEmpty() && scrollToBottom) binding.msgList.setSelection(records.size - 1)
    }

    private fun observeChanged() {
        lifecycleScope.launch {
            App.repo().changed.collectLatest { ip ->
                if (ip == peerIp) refresh(scrollToBottom = true)
            }
        }
    }

    private fun observeShake() {
        lifecycleScope.launch {
            App.engine().shakeEvents.collectLatest { ip ->
                if (ip == peerIp) playShake()
            }
        }
    }

    private fun loadOlder() {
        val total = App.repo().records(peerIp).size
        if (loadedCount >= total) {
            toast("\u5df2\u7ecf\u5230\u6700\u65e9\u7684\u8bb0\u5f55")
            return
        }
        loadedCount = (loadedCount + PAGE_SIZE).coerceAtMost(total)
        refresh(scrollToBottom = false)
        binding.msgList.setSelection(0)
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "\u641c\u7d22\u6d88\u606f\u6216\u6587\u4ef6\u540d"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("\u6d88\u606f\u641c\u7d22")
            .setView(input)
            .setPositiveButton("\u641c\u7d22") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) showSearchResults(q)
            }
            .setNegativeButton("\u53d6\u6d88", null)
            .show()
    }

    private fun showSearchResults(q: String) {
        val results = App.repo().search(peerIp, q)
        if (results.isEmpty()) {
            toast("\u6ca1\u6709\u627e\u5230")
            return
        }
        val labels = results.map {
            "${UiUtil.chatTime(it.time)}  ${it.preview().take(60)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("\u641c\u7d22\u7ed3\u679c")
            .setItems(labels) { _, which ->
                jumpToRecord(results[which])
            }
            .show()
    }

    private fun jumpToRecord(rec: ChatRecord) {
        jumpToRecordId(rec.id, highlight = true)
    }

    private fun jumpToRecordId(id: String, highlight: Boolean = false) {
        val all = App.repo().records(peerIp)
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) {
            handlingTargetJump = false
            return
        }
        val halfPage = PAGE_SIZE / 2
        loadedCount = (all.size - idx + halfPage).coerceAtLeast(PAGE_SIZE).coerceAtMost(all.size)
        refresh(scrollToBottom = false)
        val visibleIdx = records.indexOfFirst { it.id == id }
        if (visibleIdx >= 0) {
            if (highlight) {
                highlightedRecordId = id
                adapter.notifyDataSetChanged()
                binding.msgList.postDelayed({
                    if (highlightedRecordId == id) {
                        highlightedRecordId = null
                        adapter.notifyDataSetChanged()
                    }
                }, 1800)
            }
            binding.msgList.post { centerRecordInList(visibleIdx) }
            binding.msgList.postDelayed({ centerRecordInList(visibleIdx) }, 120)
        }
        pendingTargetId = null
        handlingTargetJump = false
    }

    private fun centerRecordInList(visibleIdx: Int) {
        if (records.isEmpty()) return
        val listHeight = binding.msgList.height - binding.msgList.paddingTop - binding.msgList.paddingBottom
        if (listHeight <= 0) {
            binding.msgList.setSelection(visibleIdx)
            return
        }
        val estimatedRowHeight = dp(88).coerceAtLeast(1)
        val offset = (listHeight / 2 - estimatedRowHeight / 2).coerceAtLeast(0)
        binding.msgList.setSelectionFromTop(visibleIdx, offset)
    }

    private fun exportConversation() {
        try {
            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "backup").apply { mkdirs() }
            val f = File(dir, "chat_${peerIp}_${System.currentTimeMillis()}.txt")
            f.writeText(App.repo().exportConversation(peerIp))
            toast("\u5df2\u5bfc\u51fa: ${f.name}")
        } catch (e: Exception) {
            toast("\u5bfc\u51fa\u5931\u8d25: ${e.message}")
        }
    }

    private fun showFileRuleDialog() {
        val labels = listOf("跟随全局", "每次询问", "自动接收", "永不接收")
        FeiqBottomSheet.menu(this, "文件接收规则", supportActionBar?.title?.toString(), labels.mapIndexed { idx, label ->
            FeiqBottomSheet.Action(
                label = if (idx == App.repo().fileRule(peerIp)) "$label  ·  当前" else label,
                iconRes = R.drawable.ic_file,
                danger = idx == 3,
            ) { App.repo().setFileRule(peerIp, idx) }
        })
    }

    private fun showRichTextDialog() {
        val labels = arrayOf("启用飞秋字体标签", "粗体", "斜体", "下划线", "蓝色", "黑色", "大字", "标准字")
        val checked = booleanArrayOf(
            Prefs.richTextEnabled(this),
            Prefs.richBold(this),
            Prefs.richItalic(this),
            Prefs.richUnderline(this),
            Prefs.richColor(this) == 8404992,
            Prefs.richColor(this) == 0,
            Prefs.richHeight(this) <= -20,
            Prefs.richHeight(this) > -20,
        )
        AlertDialog.Builder(this)
            .setTitle("文字格式")
            .setMultiChoiceItems(labels, checked) { _, which, value ->
                when (which) {
                    0 -> Prefs.setRichTextEnabled(this, value)
                    1 -> Prefs.setRichBold(this, value)
                    2 -> Prefs.setRichItalic(this, value)
                    3 -> Prefs.setRichUnderline(this, value)
                    4 -> if (value) Prefs.setRichColor(this, 8404992)
                    5 -> if (value) Prefs.setRichColor(this, 0)
                    6 -> if (value) Prefs.setRichHeight(this, -22)
                    7 -> if (value) Prefs.setRichHeight(this, -16)
                }
            }
            .setPositiveButton("完成", null)
            .show()
    }

    private fun confirmClear() {
        FeiqBottomSheet.menu(this, "清空聊天记录", "确定清空与此用户的全部聊天记录？", listOf(
            FeiqBottomSheet.Action("清空", R.drawable.ic_delete, danger = true) {
                App.repo().clearRecords(peerIp)
                loadedCount = PAGE_SIZE
                refresh(scrollToBottom = true)
            },
        ))
    }

    private fun showItemMenu(rec: ChatRecord, anchor: View) {
        if (rec.dir == ChatRecord.DIR_SYS) return
        val actions = mutableListOf<MessageAction>()
        if (rec.kind == ChatRecord.KIND_TEXT) {
            actions.add(MessageAction("复制") {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("msg", PreviewRenderer.plainCopy(rec.text)))
                toast("已复制")
            })
            actions.add(MessageAction("选择") { showSelectableCopyDialog(rec) })
        }
        if (rec.kind == ChatRecord.KIND_FILE && rec.fileStatus == ChatRecord.FS_DONE && rec.filePath != null) {
            actions.add(MessageAction("打开") { openFile(rec.filePath!!) })
        }
        if (rec.dir == ChatRecord.DIR_OUT && rec.status == ChatRecord.STATUS_FAILED) {
            actions.add(MessageAction("重发") { resend(rec) })
        }
        actions.add(MessageAction("删除", danger = true) { App.repo().deleteRecord(peerIp, rec) })
        showMessageActionPopover(anchor, actions)
    }

    private data class MessageAction(
        val label: String,
        val danger: Boolean = false,
        val run: () -> Unit,
    )

    private fun showMessageActionPopover(anchor: View, actions: List<MessageAction>) {
        if (actions.isEmpty()) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundResource(R.drawable.bg_message_popover)
        }
        val popup = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 10f * resources.displayMetrics.density
        }
        actions.forEach { action ->
            box.addView(TextView(this).apply {
                text = action.label
                minWidth = dp(48)
                minHeight = dp(36)
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                textSize = 14f
                setTextColor(if (action.danger) 0xFFFF6B6B.toInt() else 0xFFFFFFFF.toInt())
                setOnClickListener {
                    popup.dismiss()
                    action.run()
                }
            })
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width / 2 - box.measuredWidth / 2)
            .coerceIn(dp(8), resources.displayMetrics.widthPixels - box.measuredWidth - dp(8))
        val y = (loc[1] - box.measuredHeight - dp(8)).coerceAtLeast(dp(24))
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, x, y)
    }

    private fun showSelectableCopyDialog(rec: ChatRecord) {
        val plain = PreviewRenderer.plainCopy(rec.text)
        val dialog = AppCompatDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundResource(R.drawable.bg_selection_dialog)
        }
        root.addView(TextView(this).apply {
            text = "选择复制"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_primary))
        })
        val editor = EditText(this).apply {
            setText(plain)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 9
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, dp(14))
            background = null
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_primary))
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val footer = LinearLayout(this).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
        }
        footer.addView(TextView(this).apply {
            text = "取消"
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_secondary))
            setOnClickListener { dialog.dismiss() }
        })
        footer.addView(TextView(this).apply {
            text = "复制"
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.primary))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener {
                val s = editor.selectionStart.coerceAtLeast(0)
                val e = editor.selectionEnd.coerceAtLeast(s)
                val copied = if (s != e) editor.text.substring(s, e) else plain
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("msg", copied))
                dialog.dismiss()
                toast("已复制")
            }
        })
        root.addView(footer)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        editor.requestFocus()
        editor.selectAll()
    }
    private fun doSend() {
        val text = binding.input.text.toString().trim()
        if (text.isEmpty() || !App.isStarted()) return
        if (App.repo().isBlocked(peerIp)) {
            toast("\u5df2\u62c9\u9ed1\uff0c\u9700\u5148\u79fb\u51fa\u9ed1\u540d\u5355")
            return
        }
        val style = currentRichStyle()
        val mixed = buildMixedOutgoing(text)
        val pktNo = if (mixed.images.isEmpty()) {
            App.engine().sendMessage(peerIp, mixed.wireText, style)
        } else {
            App.engine().sendMixedMessage(peerIp, mixed.wireText, mixed.images, style)
        }
        if (text != "/imgtest") {
            App.repo().appendSending(peerIp, ChatRecord(
                ChatRecord.DIR_OUT, ChatRecord.KIND_TEXT, text = mixed.displayText,
                msgId = pktNo, status = ChatRecord.STATUS_SENDING,
                richStyle = style?.tagBody().orEmpty()
            ))
        }
        binding.input.setText("")
        updateSendButton()
    }

    private fun resend(rec: ChatRecord) {
        if (!App.isStarted()) return
        App.repo().deleteRecord(peerIp, rec)
        val style = FeiqRichText.parseStyle(rec.richStyle) ?: currentRichStyle()
        val mixed = buildMixedOutgoing(rec.text)
        val pktNo = if (mixed.images.isEmpty()) {
            App.engine().sendMessage(peerIp, mixed.wireText, style)
        } else {
            App.engine().sendMixedMessage(peerIp, mixed.wireText, mixed.images, style)
        }
        App.repo().appendSending(peerIp, ChatRecord(
            ChatRecord.DIR_OUT, ChatRecord.KIND_TEXT, text = mixed.displayText,
            msgId = pktNo, status = ChatRecord.STATUS_SENDING,
            richStyle = style?.tagBody().orEmpty()
        ))
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

    private data class MixedOutgoing(
        val wireText: String,
        val displayText: String,
        val images: List<FeiqEngine.InlineImageSend>,
    )

    private fun buildMixedOutgoing(raw: String): MixedOutgoing {
        val images = ArrayList<FeiqEngine.InlineImageSend>()
        val wire = StringBuffer()
        val display = StringBuffer()
        var last = 0
        FeiqRichText.TOKEN.findAll(raw).forEach { m ->
            if (m.groupValues[1] != "emoji") return@forEach
            wire.append(raw.substring(last, m.range.first))
            display.append(raw.substring(last, m.range.first))
            val name = m.groupValues[2]
            val code = Emoji.codeFor(name)
            if (code != null) {
                wire.append(code)
                display.append(FeiqRichText.emojiToken(name))
            } else {
                val imageId = java.lang.Long.toHexString(System.nanoTime() and 0xFFFFFFFFL).padStart(8, '0').takeLast(8)
                val bytes = Emoji.rawBytes(this, name)
                val path = copyEmojiToHistory(name)
                if (bytes != null && path != null) {
                    wire.append("/~#>").append(imageId).append("<B~")
                    display.append(FeiqRichText.imageToken(path))
                    images.add(FeiqEngine.InlineImageSend(imageId, bytes))
                } else {
                    display.append("[表情]")
                }
            }
            last = m.range.last + 1
        }
        wire.append(raw.substring(last))
        display.append(raw.substring(last))
        return MixedOutgoing(wire.toString(), display.toString(), images)
    }

    private fun copyEmojiToHistory(name: String): String? = try {
        val src = assets.open("emoji/$name").use { it.readBytes() }
        val f = File(App.repo().store.imageDir(), "emoji_${System.currentTimeMillis()}_$name")
        f.writeBytes(src)
        f.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun setupEmojiGrid() {
        val grid = binding.emojiGrid
        val emojis = Emoji.list(this)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = emojis.size + 1
            override fun getItem(position: Int) = if (position < emojis.size) emojis[position] else "__backspace__"
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_emoji, parent, false)
                val tv = v.findViewById<TextView>(R.id.emojiImage)
                tv.setBackgroundResource(android.R.color.transparent)
                tv.alpha = 1f
                tv.contentDescription = "表情"
                val drawable = if (p >= emojis.size) {
                    tv.contentDescription = "鍒犻櫎"
                    tv.setBackgroundResource(R.drawable.bg_emoji_backspace)
                    androidx.core.content.ContextCompat.getDrawable(this@ChatActivity, R.drawable.ic_backspace)
                } else {
                    Emoji.loadAnimated(this@ChatActivity, emojis[p], tv) ?: Emoji.load(this@ChatActivity, emojis[p])
                }
                val size = (34 * resources.displayMetrics.density).toInt()
                drawable?.setBounds(0, 0, size, size)
                tv.setCompoundDrawables(null, drawable, null, null)
                if (p < emojis.size) tv.alpha = if (Prefs.isFavoriteEmoji(this@ChatActivity, emojis[p])) 1f else 0.86f
                return v
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= emojis.size) deleteInputBeforeCursor() else insertEmoji(emojis[pos])
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos >= emojis.size) return@setOnItemLongClickListener true
            val added = Prefs.toggleFavoriteEmoji(this, emojis[pos])
            toast(if (added) "\u5df2\u6536\u85cf" else "\u5df2\u53d6\u6d88\u6536\u85cf")
            setupEmojiGrid()
            true
        }
    }

    private fun deleteInputBeforeCursor() {
        val editable = binding.input.text ?: return
        val start = binding.input.selectionStart.coerceAtLeast(0)
        val end = binding.input.selectionEnd.coerceAtLeast(start)
        if (start != end) {
            editable.delete(start, end)
        } else if (start > 0) {
            val raw = editable.toString()
            val token = FeiqRichText.TOKEN.findAll(raw)
                .firstOrNull { it.range.first < start && it.range.last + 1 == start }
            if (token != null) {
                editable.delete(token.range.first, token.range.last + 1)
            } else {
                val deleteStart = Character.offsetByCodePoints(raw, start, -1)
                editable.delete(deleteStart, start)
            }
        }
        renderInputEmojiSpans()
        updateSendButton()
    }

    private fun insertEmoji(name: String) {
        val token = FeiqRichText.emojiToken(name)
        val start = binding.input.selectionStart.coerceAtLeast(0)
        val end = binding.input.selectionEnd.coerceAtLeast(start)
        binding.input.text.replace(start, end, token)
        renderInputEmojiSpans()
        updateSendButton()
    }

    private fun renderInputEmojiSpans() {
        val editable = binding.input.text ?: return
        editable.getSpans(0, editable.length, ImageSpan::class.java).forEach { editable.removeSpan(it) }
        FeiqRichText.TOKEN.findAll(editable.toString()).forEach { m ->
            if (m.groupValues[1] == "emoji") {
                emojiSpan(m.groupValues[2], 30)?.let { span ->
                    editable.setSpan(span, m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun emojiSpan(name: String, dp: Int): ImageSpan? {
        val drawable = Emoji.loadAnimated(this, name, binding.input) ?: Emoji.load(this, name) ?: return null
        val size = (dp * resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, size, size)
        return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    }

    private fun imageFileSpan(path: String, dp: Int): ImageSpan? {
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val size = (dp * resources.displayMetrics.density).toInt()
        val drawable = BitmapDrawable(resources, bmp)
        drawable.setBounds(0, 0, size, size)
        return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    }

    private fun toggleEmojiPanel() {
        if (panelMode == PanelMode.EMOJI) {
            hidePanel()
            showKeyboard()
        } else {
            hideKeyboard()
            panelMode = PanelMode.EMOJI
            binding.bottomPanel.visibility = View.VISIBLE
            binding.emojiGrid.visibility = View.VISIBLE
            binding.actionPanel.visibility = View.GONE
            binding.actionPanelHeader.visibility = View.GONE
            binding.btnEmoji.setImageResource(R.drawable.ic_keyboard)
            keepLatestVisible()
        }
    }

    private fun toggleActionPanel() {
        if (panelMode == PanelMode.ACTIONS) {
            hidePanel()
            showKeyboard()
        } else {
            hideKeyboard()
            panelMode = PanelMode.ACTIONS
            binding.bottomPanel.visibility = View.VISIBLE
            binding.emojiGrid.visibility = View.GONE
            binding.actionPanel.visibility = View.VISIBLE
            binding.actionPanelHeader.visibility = View.VISIBLE
            binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
            keepLatestVisible()
        }
    }

    private fun hidePanel() {
        panelMode = PanelMode.NONE
        binding.bottomPanel.visibility = View.GONE
        binding.emojiGrid.visibility = View.GONE
        binding.actionPanel.visibility = View.GONE
        binding.actionPanelHeader.visibility = View.GONE
        binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
    }

    private fun updateSendButton() {
        val hasText = binding.input.text.toString().trim().isNotEmpty()
        binding.btnPlusSendIcon.visibility = if (hasText) View.GONE else View.VISIBLE
        binding.btnPlusSendText.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.btnPlusSend.background = androidx.core.content.ContextCompat.getDrawable(
            this,
            if (hasText) android.R.color.transparent else R.drawable.bg_icon_button
        )
    }

    private fun keepLatestVisible() {
        if (records.isNotEmpty()) binding.msgList.post { binding.msgList.setSelection(records.size - 1) }
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.input.windowToken, 0)
        } catch (_: Exception) {}
        binding.input.clearFocus()
    }

    private fun showKeyboard() {
        binding.input.requestFocus()
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
    }

    private fun pickImageToSend() {
        val pick = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            pickImage.launch(pick)
        } catch (_: Exception) {
            pickImage.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            })
        }
    }

    private fun sendImage(uri: Uri) {
        if (!App.isStarted()) return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                    var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@withContext raw
                    val maxEdge = 1600
                    val scale = maxOf(bmp.width, bmp.height).toFloat() / maxEdge
                    if (scale > 1f) {
                        bmp = android.graphics.Bitmap.createScaledBitmap(
                            bmp, (bmp.width / scale).toInt(), (bmp.height / scale).toInt(), true
                        )
                    }
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                } catch (_: Exception) {
                    null
                }
            } ?: run {
                toast("\u8bfb\u53d6\u56fe\u7247\u5931\u8d25")
                return@launch
            }
            val path = withContext(Dispatchers.IO) {
                try {
                    val f = File(App.repo().store.imageDir(), "sent_${System.currentTimeMillis()}.img")
                    f.writeBytes(bytes)
                    f.absolutePath
                } catch (_: Exception) {
                    null
                }
            }
            App.engine().sendInlineImage(peerIp, bytes)
            if (path != null) App.repo().append(peerIp, ChatRecord(
                ChatRecord.DIR_OUT, ChatRecord.KIND_IMAGE, imagePath = path
            ))
        }
    }

    private fun pickFilesToSend() {
        pickFiles.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
    }

    private fun pickFolderToSend() {
        pickFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun sendFolder(uri: Uri) {
        if (!App.isStarted()) return
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) {
                try {
                    val tree = buildDirEntry(uri)
                    val bytes = ByteArrayOutputStream().use { out ->
                        DirFileCodec.writeTree(tree, out)
                        out.toByteArray()
                    }
                    Triple(tree.name, DirFileCodec.totalFileBytes(tree), bytes)
                } catch (e: Exception) {
                    null
                }
            } ?: run {
                toast("文件夹读取失败")
                return@launch
            }
            val item = FeiqEngine.SendItem(
                name = built.first,
                size = built.second,
                mtime = System.currentTimeMillis() / 1000,
                openStream = { ByteArrayInputStream(built.third) },
                attr = Protocol.IPMSG_FILE_DIR,
            )
            App.repo().append(peerIp, ChatRecord(
                ChatRecord.DIR_OUT, ChatRecord.KIND_FILE,
                fileName = item.name, fileSize = item.size,
                filePath = uri.toString(), fileStatus = ChatRecord.FS_DONE,
                isDir = true,
            ))
            App.engine().sendFiles(peerIp, listOf(item))
        }
    }

    private fun collectAndSend(data: Intent) {
        if (!App.isStarted()) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (n in 0 until clip.itemCount) uris.add(clip.getItemAt(n).uri) }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
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

    private fun buildDirEntry(treeUri: Uri): DirFileCodec.Entry {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return buildDocEntry(treeUri, rootId)
    }

    private fun buildDocEntry(treeUri: Uri, docId: String): DirFileCodec.Entry {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val info = queryDocInfo(docUri)
        if (info.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            val children = ArrayList<DirFileCodec.Entry>()
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (c.moveToNext()) {
                    val childId = c.getString(idCol)
                    children.add(buildDocEntry(treeUri, childId))
                }
            }
            return DirFileCodec.Entry(info.name, 0, Protocol.IPMSG_FILE_DIR, children = children)
        }
        return DirFileCodec.Entry(
            name = info.name,
            size = info.size,
            attr = Protocol.IPMSG_FILE_REGULAR,
            open = { contentResolver.openInputStream(docUri)!! },
        )
    }

    private data class DocInfo(val name: String, val size: Long, val mime: String)

    private fun queryDocInfo(uri: Uri): DocInfo {
        var name = "folder"
        var size = 0L
        var mime = ""
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val si = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
                if (mi >= 0) mime = c.getString(mi) ?: mime
            }
        }
        return DocInfo(name, size, mime)
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

    private fun observeProgress() {
        lifecycleScope.launch {
            App.engine().fileProgress.collectLatest { p ->
                if (p.total > 0) {
                    val pct = (p.done * 100 / p.total).toInt()
                    val dir = if (p.outgoing) "\u53d1\u9001" else "\u63a5\u6536"
                    binding.statusLine.visibility = View.VISIBLE
                    binding.statusLine.text = "$dir \u6587\u4ef6 $pct%"
                    if (p.done >= p.total) binding.statusLine.postDelayed({
                        binding.statusLine.visibility = View.GONE
                    }, 1500)
                }
            }
        }
    }

    private inner class ChatAdapter : BaseAdapter() {
        override fun getCount() = records.size
        override fun getItem(position: Int) = records[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@ChatActivity).inflate(R.layout.item_chat_message, parent, false)
            val timeSep = v.findViewById<TextView>(R.id.timeSep)
            val row = v.findViewById<LinearLayout>(R.id.row)
            val bubble = v.findViewById<LinearLayout>(R.id.bubble)
            val tv = v.findViewById<TextView>(R.id.msgText)
            val iv = v.findViewById<ImageView>(R.id.msgImage)
            val fileCard = v.findViewById<LinearLayout>(R.id.fileCard)
            val statusIcon = v.findViewById<TextView>(R.id.statusIcon)
            val avatarInBox = v.findViewById<View>(R.id.avatarInBox)
            val avatarOutBox = v.findViewById<View>(R.id.avatarOutBox)
            val avatarInImage = v.findViewById<ImageView>(R.id.avatarInImage)
            val avatarInText = v.findViewById<TextView>(R.id.avatarInText)
            val avatarOutImage = v.findViewById<ImageView>(R.id.avatarOutImage)
            val avatarOutText = v.findViewById<TextView>(R.id.avatarOutText)
            val rec = records[position]

            val prev = if (position > 0) records[position - 1] else null
            if (prev == null || rec.time - prev.time > 5 * 60 * 1000) {
                timeSep.visibility = View.VISIBLE
                timeSep.text = UiUtil.chatTime(rec.time)
            } else {
                timeSep.visibility = View.GONE
            }

            statusIcon.visibility = View.GONE
            statusIcon.setOnClickListener(null)
            if (rec.dir == ChatRecord.DIR_OUT) when (rec.status) {
                ChatRecord.STATUS_SENDING -> {
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.text = "..."
                    statusIcon.setTextColor(0xFF999999.toInt())
                }
                ChatRecord.STATUS_FAILED -> {
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.text = "!"
                    statusIcon.setTextColor(0xFFFA5151.toInt())
                    statusIcon.setOnClickListener { resend(rec) }
                }
            }

            val primaryColor = androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_primary)
            val secondaryColor = androidx.core.content.ContextCompat.getColor(this@ChatActivity, R.color.text_secondary)
            when (rec.dir) {
                ChatRecord.DIR_SYS -> {
                    row.gravity = Gravity.CENTER
                    avatarInBox.visibility = View.GONE
                    avatarOutBox.visibility = View.GONE
                    bubble.setBackgroundColor(0x00000000)
                    tv.setTextColor(secondaryColor)
                    tv.textSize = 12f
                }
                ChatRecord.DIR_OUT -> {
                    row.gravity = Gravity.END
                    avatarInBox.visibility = View.GONE
                    avatarOutBox.visibility = View.VISIBLE
                    bindAvatar(avatarOutImage, avatarOutText, App.repo().selfAvatarPath(), Prefs.nick(this@ChatActivity))
                    avatarOutBox.setOnClickListener { onAvatarTapped() }
                    bubble.setBackgroundResource(if (rec.id == highlightedRecordId) R.drawable.bubble_highlight else R.drawable.bubble_out)
                    tv.setTextColor(primaryColor)
                    tv.textSize = 16f
                }
                else -> {
                    row.gravity = Gravity.START
                    avatarInBox.visibility = View.VISIBLE
                    avatarOutBox.visibility = View.GONE
                    bindAvatar(avatarInImage, avatarInText, App.repo().avatarPath(peerIp), supportActionBar?.title?.toString() ?: peerIp)
                    avatarInBox.setOnClickListener { onAvatarTapped() }
                    bubble.setBackgroundResource(if (rec.id == highlightedRecordId) R.drawable.bubble_highlight else R.drawable.bubble_in)
                    tv.setTextColor(primaryColor)
                    tv.textSize = 16f
                }
            }

            tv.visibility = View.GONE
            iv.visibility = View.GONE
            fileCard.visibility = View.GONE
            bubble.setOnClickListener(null)
            bubble.setOnLongClickListener { showItemMenu(rec, bubble); true }
            tv.setOnLongClickListener { showItemMenu(rec, bubble); true }
            iv.setOnLongClickListener { showItemMenu(rec, bubble); true }
            fileCard.setOnLongClickListener { showItemMenu(rec, bubble); true }

            when (rec.kind) {
                ChatRecord.KIND_IMAGE -> {
                    iv.visibility = View.VISIBLE
                    try {
                        iv.setImageBitmap(BitmapFactory.decodeFile(rec.imagePath))
                    } catch (_: Exception) {
                        iv.setImageDrawable(null)
                    }
                    rec.imagePath?.let { p -> iv.setOnClickListener { showFullImage(p) } }
                }
                ChatRecord.KIND_FILE -> bindFileCard(v, bubble, rec)
                else -> {
                    tv.visibility = View.VISIBLE
                    bindRichText(tv, rec)
                }
            }
            return v
        }
    }

    private fun bindRichText(tv: TextView, rec: ChatRecord) {
        val style = FeiqRichText.parseStyle(rec.richStyle)
        val text = SpannableString(rec.text)
        val end = text.length
        FeiqRichText.TOKEN.findAll(rec.text).forEach { m ->
            val kind = m.groupValues[1]
            val value = m.groupValues[2]
            val span = when (kind) {
                "emoji" -> PreviewRenderer.emojiSpan(this, tv, value, 34, animated = true)
                "image" -> imageFileSpan(value, 34)
                else -> null
            }
            if (span != null) {
                text.setSpan(span, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (end > 0) {
            style?.let {
                val typeface = when {
                    it.weight >= 600 && it.italic -> Typeface.BOLD_ITALIC
                    it.weight >= 600 -> Typeface.BOLD
                    it.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                if (typeface != Typeface.NORMAL) text.setSpan(StyleSpan(typeface), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (it.underline) text.setSpan(UnderlineSpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (it.strikeOut) text.setSpan(StrikethroughSpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(colorRefToAndroid(it.colorRef)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        tv.text = text
        tv.textSize = style?.let { (-it.height).coerceIn(10, 28).toFloat() } ?: 16f
    }

    private fun colorRefToAndroid(colorRef: Int): Int {
        val c = colorRef.coerceIn(0, 0xFFFFFF)
        return Color.rgb(c and 0xFF, (c shr 8) and 0xFF, (c shr 16) and 0xFF)
    }

    private fun bindAvatar(image: ImageView, text: TextView, path: String, name: String) {
        if (path.isNotBlank()) {
            image.setImageBitmap(BitmapFactory.decodeFile(path))
            image.visibility = View.VISIBLE
            text.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            text.visibility = View.VISIBLE
            text.text = UiUtil.initial(name)
            val bg = (text.background?.mutate() as? GradientDrawable)
                ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
            bg.setColor(UiUtil.avatarColor(name))
            text.background = bg
        }
    }

    private fun onAvatarTapped() {
        val now = System.currentTimeMillis()
        val last = avatarClicks[peerIp] ?: 0L
        avatarClicks[peerIp] = now
        if (now - last <= 450L) {
            if (!App.isStarted()) return
            App.engine().sendShake(peerIp)
            playShake()
            toast("已发送抖一抖")
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

    private fun bindFileCard(v: View, bubble: LinearLayout, rec: ChatRecord) {
        v.findViewById<LinearLayout>(R.id.fileCard).visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.fileName).text = rec.fileName
        v.findViewById<TextView>(R.id.fileIcon).text = if (rec.isDir) "夹" else "文"
        val sizeTv = v.findViewById<TextView>(R.id.fileSize)
        val actions = v.findViewById<LinearLayout>(R.id.fileActions)
        val sizeStr = if (rec.isDir) "文件夹" else UiUtil.formatSize(rec.fileSize)
        sizeTv.text = when (rec.fileStatus) {
            ChatRecord.FS_PENDING -> "$sizeStr | \u5f85\u63a5\u6536"
            ChatRecord.FS_RECEIVING -> "$sizeStr | \u63a5\u6536\u4e2d"
            ChatRecord.FS_DONE -> "$sizeStr | \u70b9\u51fb\u6253\u5f00"
            ChatRecord.FS_FAILED -> "$sizeStr | \u5931\u8d25"
            ChatRecord.FS_REJECTED -> "$sizeStr | \u5df2\u62d2\u7edd"
            else -> sizeStr
        }
        if (rec.dir == ChatRecord.DIR_IN && rec.fileStatus == ChatRecord.FS_PENDING) {
            actions.visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.btnAccept).setOnClickListener { App.repo().acceptFile(peerIp, rec) }
            v.findViewById<TextView>(R.id.btnReject).setOnClickListener { App.repo().rejectFile(peerIp, rec) }
        } else {
            actions.visibility = View.GONE
        }
        if (rec.fileStatus == ChatRecord.FS_DONE && rec.filePath != null) {
            bubble.setOnClickListener { openFile(rec.filePath!!) }
        }
    }

    private fun showFullImage(path: String) {
        val imgPaths = ArrayList(records.filter { it.kind == ChatRecord.KIND_IMAGE && it.imagePath != null }.map { it.imagePath!! })
        val idx = imgPaths.indexOf(path).coerceAtLeast(0)
        startActivity(Intent(this, GalleryActivity::class.java)
            .putStringArrayListExtra(GalleryActivity.EXTRA_PATHS, imgPaths)
            .putExtra(GalleryActivity.EXTRA_INDEX, idx))
    }

    private fun openFile(path: String) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val mime = contentResolver.getType(uri) ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment.orEmpty()) ?: "*/*"
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(i, "\u6253\u5f00\u6587\u4ef6"))
                return
            }
            val f = File(path)
            if (f.isDirectory) {
                toast("文件夹已保存: ${f.name}")
                return
            }
            if (!f.exists()) {
                toast("\u6587\u4ef6\u4e0d\u5b58\u5728")
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "\u6253\u5f00\u6587\u4ef6"))
        } catch (e: Exception) {
            toast("\u65e0\u6cd5\u6253\u5f00: ${e.message}")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

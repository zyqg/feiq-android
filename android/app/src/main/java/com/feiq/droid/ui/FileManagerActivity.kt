package com.feiq.droid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.ChatRecord
import java.io.File
import java.net.URLConnection

class FileManagerActivity : BaseActivity() {
    private data class FileRow(val peerIp: String, val peerName: String, val rec: ChatRecord)

    private enum class FilterMode(val label: String) {
        ALL("全部"),
        IN("收到"),
        OUT("发出"),
        DONE("已完成"),
        PENDING("待接收"),
    }

    private val allRows = mutableListOf<FileRow>()
    private val rows = mutableListOf<FileRow>()
    private val selectedIds = LinkedHashSet<String>()
    private var filterMode = FilterMode.ALL
    private var query = ""
    private lateinit var adapter: FileAdapter
    private lateinit var summary: TextView
    private lateinit var bulkBar: LinearLayout
    private lateinit var filterBar: LinearLayout
    private lateinit var emptyView: View
    private lateinit var fileList: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        findViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }
        summary = findViewById(R.id.summary)
        filterBar = findViewById(R.id.filterBar)
        bulkBar = findViewById(R.id.bulkBar)
        emptyView = findViewById(R.id.emptyView)

        adapter = FileAdapter()
        fileList = findViewById<ListView>(R.id.fileList).apply {
            adapter = this@FileManagerActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                openRow(rows[pos])
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                toggleSelected(rows[pos])
                true
            }
        }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        buildFilterBar()
        buildBulkBar()
        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun buildFilterBar() {
        filterBar.removeAllViews()
        FilterMode.values().forEach { mode ->
            val chip = TextView(this).apply {
                text = mode.label
                minHeight = dp(34)
                minWidth = dp(58)
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (mode == filterMode) 0xFFFFFFFF.toInt() else ContextCompat.getColor(this@FileManagerActivity, R.color.text_primary))
                setBackgroundResource(if (mode == filterMode) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                setOnClickListener {
                    filterMode = mode
                    buildFilterBar()
                    applyFilter()
                }
            }
            filterBar.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
    }

    private fun buildBulkBar() {
        bulkBar.removeAllViews()
        val buttons = listOf(
            "全选当前" to { selectCurrent() },
            "取消选择" to { clearSelected() },
            "删除所选" to { confirmDeleteSelected() },
            "清理失效" to { confirmCleanMissing() },
        )
        buttons.forEach { (label, action) ->
            val btn = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                minHeight = dp(34)
                setTextColor(if (label.contains("删除") || label.contains("清理")) {
                    ContextCompat.getColor(this@FileManagerActivity, R.color.danger)
                } else {
                    ContextCompat.getColor(this@FileManagerActivity, R.color.primary)
                })
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { action() }
            }
            bulkBar.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                setMargins(dp(5), 0, dp(5), 0)
            })
        }
    }

    private fun loadFiles() {
        if (!App.isStarted()) return
        allRows.clear()
        allRows.addAll(App.repo().fileItems().map { (ip, rec) ->
            FileRow(ip, App.repo().displayName(ip), rec)
        }.sortedByDescending { it.rec.time })
        applyFilter()
    }

    private fun applyFilter() {
        rows.clear()
        rows.addAll(allRows.filter { row ->
            val matchesFilter = when (filterMode) {
                FilterMode.ALL -> true
                FilterMode.IN -> row.rec.dir == ChatRecord.DIR_IN
                FilterMode.OUT -> row.rec.dir == ChatRecord.DIR_OUT
                FilterMode.DONE -> row.rec.fileStatus == ChatRecord.FS_DONE || !row.rec.filePath.isNullOrBlank()
                FilterMode.PENDING -> row.rec.fileStatus == ChatRecord.FS_PENDING
            }
            val matchesQuery = query.isBlank() ||
                row.rec.fileName.contains(query, ignoreCase = true) ||
                row.peerName.contains(query, ignoreCase = true) ||
                row.peerIp.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        })
        selectedIds.retainAll(rows.map { it.rec.id }.toSet())
        adapter.notifyDataSetChanged()
        summary.text = "当前 ${rows.size} 个文件，已选择 ${selectedIds.size} 个"
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        fileList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toggleSelected(row: FileRow) {
        if (!selectedIds.add(row.rec.id)) selectedIds.remove(row.rec.id)
        applyFilter()
    }

    private fun selectCurrent() {
        selectedIds.addAll(rows.map { it.rec.id })
        applyFilter()
    }

    private fun clearSelected() {
        selectedIds.clear()
        applyFilter()
    }

    private fun confirmDeleteSelected() {
        val selected = allRows.filter { it.rec.id in selectedIds }
        if (selected.isEmpty()) {
            toast("还没有选择文件记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除所选记录")
            .setMessage("将从聊天记录中删除 ${selected.size} 条文件记录，不会删除本地文件。")
            .setPositiveButton("删除") { _, _ ->
                selected.forEach { App.repo().deleteRecord(it.peerIp, it.rec) }
                selectedIds.clear()
                loadFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmCleanMissing() {
        val missing = allRows.filter { row ->
            val path = row.rec.filePath
            !path.isNullOrBlank() && !path.startsWith("content://") && !File(path).exists()
        }
        if (missing.isEmpty()) {
            toast("没有失效记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清理失效记录")
            .setMessage("将删除 ${missing.size} 条本地文件已不存在的记录。")
            .setPositiveButton("清理") { _, _ ->
                missing.forEach { App.repo().deleteRecord(it.peerIp, it.rec) }
                selectedIds.clear()
                loadFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@FileManagerActivity).inflate(R.layout.item_file_row, parent, false)
            val row = rows[position]
            view.findViewById<CheckBox>(R.id.fileCheck).apply {
                isChecked = row.rec.id in selectedIds
                isFocusable = false
                isFocusableInTouchMode = false
                setOnClickListener { toggleSelected(row) }
            }
            view.isClickable = true
            view.isFocusable = false
            view.setOnClickListener { openRow(row) }
            view.findViewById<TextView>(R.id.fileTypeIcon).text = if (row.rec.isDir) "夹" else "文"
            view.findViewById<TextView>(R.id.fileTitle).text = row.rec.fileName.ifBlank { "file" }
            view.findViewById<TextView>(R.id.fileSubtitle).text = buildSubtitle(row)
            return view
        }
    }

    private fun openRow(row: FileRow) {
        val path = row.rec.filePath
        if (path.isNullOrBlank()) {
            toast("这条文件没有本地路径")
        } else {
            openFile(path)
        }
    }

    private fun buildSubtitle(row: FileRow): String {
        val dir = if (row.rec.dir == ChatRecord.DIR_OUT) "发送" else "接收"
        val status = when (row.rec.fileStatus) {
            ChatRecord.FS_PENDING -> "待接收"
            ChatRecord.FS_RECEIVING -> "接收中"
            ChatRecord.FS_DONE -> "已完成"
            ChatRecord.FS_FAILED -> "失败"
            ChatRecord.FS_REJECTED -> "已拒绝"
            else -> if (row.rec.filePath.isNullOrBlank()) "记录" else "已完成"
        }
        return "$dir · $status · ${row.peerName} · ${UiUtil.formatSize(row.rec.fileSize)} · ${UiUtil.chatTime(row.rec.time)}"
    }

    private fun openFile(path: String) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val mime = contentResolver.getType(uri)
                    ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment.orEmpty())
                    ?: "*/*"
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(i, "打开文件"))
                return
            }
            val f = File(path)
            if (f.isDirectory) {
                toast("这是文件夹记录：${f.absolutePath}")
                return
            }
            if (!f.exists()) {
                toast("文件不存在")
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "打开文件"))
        } catch (e: Exception) {
            toast("无法打开：${e.message}")
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

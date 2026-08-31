package com.feiq.droid.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.TextView
import com.feiq.droid.R
import com.feiq.droid.core.App
import com.feiq.droid.core.MessageRepository
import com.feiq.droid.databinding.ActivityGlobalSearchBinding

class GlobalSearchActivity : BaseActivity() {
    private lateinit var b: ActivityGlobalSearchBinding
    private val hits = mutableListOf<MessageRepository.SearchHit>()
    private lateinit var adapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGlobalSearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.toolbar.setNavigationOnClickListener { finish() }
        adapter = ResultAdapter()
        b.resultList.adapter = adapter
        b.resultList.setOnItemClickListener { _, _, pos, _ ->
            val hit = hits[pos]
            startActivity(Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_IP, hit.peerIp)
                .putExtra(ChatActivity.EXTRA_NAME, hit.peerName)
                .putExtra(ChatActivity.EXTRA_TARGET_ID, hit.record.id))
            finish()
        }
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = runSearch(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })
        showEmpty("输入关键词搜索联系人、消息和文件")
        b.searchInput.requestFocus()
        b.searchInput.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 220)
    }

    private fun runSearch(raw: String) {
        val q = raw.trim()
        hits.clear()
        if (q.isNotBlank() && App.isStarted()) hits.addAll(App.repo().searchAll(q))
        adapter.notifyDataSetChanged()
        if (q.isBlank()) showEmpty("输入关键词搜索联系人、消息和文件")
        else if (hits.isEmpty()) showEmpty("没有找到相关结果")
        else {
            b.emptyText.visibility = View.GONE
            b.resultList.visibility = View.VISIBLE
        }
    }

    private fun showEmpty(text: String) {
        b.emptyText.text = text
        b.emptyText.visibility = View.VISIBLE
        b.resultList.visibility = View.GONE
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(position: Int) = hits[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@GlobalSearchActivity).inflate(R.layout.item_search_result, parent, false)
            val hit = hits[position]
            v.findViewById<TextView>(R.id.resultName).text = hit.peerName
            v.findViewById<TextView>(R.id.resultTime).text = UiUtil.chatTime(hit.record.time)
            PreviewRenderer.bind(v.findViewById(R.id.resultPreview), hit.record)
            return v
        }
    }
}

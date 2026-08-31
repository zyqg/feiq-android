package com.feiq.droid.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.io.File

/** 图片画廊：左右滑切换、双击/捏合缩放、保存到相册。 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var paths: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: emptyList()
        val start = intent.getIntExtra(EXTRA_INDEX, 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pager = ViewPager(this)
        root.addView(pager, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 顶部计数 + 保存按钮
        val counter = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(40, 50, 40, 20)
        }
        root.addView(counter, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val saveBtn = TextView(this).apply {
            text = "保存"; setTextColor(Color.WHITE); textSize = 15f
            setPadding(40, 50, 40, 20)
            setOnClickListener { saveCurrent(pager.currentItem) }
        }
        root.addView(saveBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        setContentView(root)

        pager.adapter = object : PagerAdapter() {
            override fun getCount() = paths.size
            override fun isViewFromObject(v: View, o: Any) = v === o
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                val iv = ZoomableImageView(this@GalleryActivity)
                try { iv.setImageBitmap(BitmapFactory.decodeFile(paths[position])) } catch (_: Exception) {}
                container.addView(iv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return iv
            }
            override fun destroyItem(container: ViewGroup, position: Int, o: Any) {
                container.removeView(o as View)
            }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { counter.text = "${position + 1} / ${paths.size}" }
        })
        pager.currentItem = start.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
        counter.text = "${start + 1} / ${paths.size}"
    }

    private fun saveCurrent(index: Int) {
        val path = paths.getOrNull(index) ?: return
        try {
            val src = File(path)
            if (!src.exists()) { toast("文件不存在"); return }
            val name = "feiq_${System.currentTimeMillis()}.jpg"
            val resolver = contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FeiQ")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    toast("已保存到相册/Pictures/FeiQ")
                } else toast("保存失败")
            } else {
                // Android 9 及以下：写入公共图片目录
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "FeiQ")
                dir.mkdirs()
                val dst = File(dir, name)
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
                MediaStore.Images.Media.insertImage(resolver, dst.absolutePath, name, "")
                toast("已保存到相册")
            }
        } catch (e: Exception) { toast("保存失败: ${e.message}") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

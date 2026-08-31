package com.feiq.droid.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import com.feiq.droid.core.ChatRecord
import com.feiq.droid.net.FeiqRichText

object PreviewRenderer {
    fun bind(textView: TextView, record: ChatRecord?, animated: Boolean = false) {
        if (record == null) {
            textView.text = ""
            return
        }
        when (record.kind) {
            ChatRecord.KIND_IMAGE -> textView.text = "[图片]"
            ChatRecord.KIND_FILE -> textView.text = if (record.isDir) "[文件夹] ${record.fileName}" else "[文件] ${record.fileName}"
            else -> textView.text = render(textView.context, textView, record.text, 18, animated)
        }
    }

    fun render(context: Context, owner: TextView, raw: String, dp: Int, animated: Boolean = true): CharSequence {
        val text = SpannableString(raw)
        FeiqRichText.TOKEN.findAll(raw).forEach { m ->
            val span = when (m.groupValues[1]) {
                "emoji" -> emojiSpan(context, owner, m.groupValues[2], dp, animated)
                "image" -> imageSpan(context, m.groupValues[2], dp)
                else -> null
            }
            if (span != null) text.setSpan(span, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    fun plainCopy(raw: String): String =
        raw.replace(FeiqRichText.TOKEN) { m ->
            when (m.groupValues[1]) {
                "emoji" -> Emoji.codeFor(m.groupValues[2]) ?: "[表情]"
                "image", "inline" -> "[图片]"
                else -> ""
            }
        }

    fun emojiSpan(context: Context, owner: TextView, name: String, dp: Int, animated: Boolean = true): ImageSpan? {
        val bytes = Emoji.rawBytes(context, name) ?: return null
        val drawable = if (animated) AnimatedGifDrawable(bytes, owner) else Emoji.load(context, name) ?: return null
        val size = (dp * context.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, size, size)
        return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    }

    private fun imageSpan(context: Context, path: String, dp: Int): ImageSpan? {
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        val size = (dp * context.resources.displayMetrics.density).toInt()
        val drawable = BitmapDrawable(context.resources, bmp)
        drawable.setBounds(0, 0, size, size)
        return ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
    }
}

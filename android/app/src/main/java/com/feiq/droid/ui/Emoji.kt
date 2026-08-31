package com.feiq.droid.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import com.feiq.droid.net.FeiqEmoticons

/** 表情管理：从 assets/emoji 列出所有表情资源。 */
object Emoji {
    @Volatile private var cached: List<String>? = null

    /** 列出所有表情文件名（如 "1.gif"），按数字序排。 */
    fun list(ctx: Context): List<String> {
        cached?.let { return it }
        return try {
            val files = ctx.assets.list("emoji")?.toList().orEmpty()
            val sorted = files.sortedBy { it.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            cached = sorted; sorted
        } catch (e: Exception) { emptyList() }
    }

    /** 加载某个表情为 Drawable（GIF 在普通 ImageView 里只显示首帧，足够选择面板用）。 */
    fun load(ctx: Context, name: String): Drawable? = try {
        ctx.assets.open("emoji/$name").use { Drawable.createFromStream(it, name) }
    } catch (e: Exception) { null }

    fun loadAnimated(ctx: Context, name: String, owner: TextView? = null): Drawable? =
        rawBytes(ctx, name)?.let { AnimatedGifDrawable(it, owner) }

    /** 原样读取表情字节，保留 GIF/PNG 透明背景，避免转 JPEG 后接收端黑底。 */
    fun rawBytes(ctx: Context, name: String): ByteArray? = try {
        ctx.assets.open("emoji/$name").use { it.readBytes() }
    } catch (e: Exception) { null }

    fun codeFor(name: String): String? = FeiqEmoticons.codeForAsset(name)

    fun sortNames(names: List<String>): List<String> =
        names.sortedBy { it.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
}

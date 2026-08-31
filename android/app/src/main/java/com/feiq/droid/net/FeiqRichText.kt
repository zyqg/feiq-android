package com.feiq.droid.net

/**
 * FeiQ rich text helper for the private trailing font tag:
 *   {/font;-16 0 0 0 400 0 0 0 134 0 0 2 32 微软雅黑 8404992;}
 *
 * The payload is mostly a Windows LOGFONT dump followed by a COLORREF value.
 */
object FeiqRichText {
    private val FONT_TAG = Regex("\\{/font;([^{}]*);\\}")
    private val ANY_FEIQ_TAG = Regex("\\{/[^{}]*;\\}")
    private val IMAGE_MARK = Regex("/~#>[^<]*<B~")

    data class FontStyle(
        val height: Int = -16,
        val weight: Int = 400,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikeOut: Boolean = false,
        val charset: Int = 134,
        val faceName: String = "微软雅黑",
        val colorRef: Int = 0,
    ) {
        fun tagBody(): String = listOf(
            height, 0, 0, 0, weight,
            if (italic) 1 else 0,
            if (underline) 1 else 0,
            if (strikeOut) 1 else 0,
            charset, 0, 0, 2, 32,
            faceName,
            colorRef.coerceIn(0, 0xFFFFFF)
        ).joinToString(" ")

        fun tag(): String = "{/font;${tagBody()};}"
    }

    data class Parsed(
        val plainText: String,
        val tokenText: String,
        val fontTagBody: String,
        val inlineImageIds: List<String>,
    )

    fun parse(raw: String): Parsed {
        val fontTag = FONT_TAG.findAll(raw).lastOrNull()?.groupValues?.getOrNull(1).orEmpty()
        val noTags = FeiqEmoticons.replaceCodesWithTokens(ANY_FEIQ_TAG.replace(raw, ""))
        val ids = IMAGE_MARK.findAll(noTags)
            .mapNotNull { it.value.removePrefix("/~#>").removeSuffix("<B~").takeIf(String::isNotBlank) }
            .toList()
        val tokenText = IMAGE_MARK.replace(noTags) {
            val id = it.value.removePrefix("/~#>").removeSuffix("<B~")
            inlineToken(id)
        }.trim()
        val plain = IMAGE_MARK.replace(noTags, "").trim()
        return Parsed(
            plainText = plain,
            tokenText = tokenText,
            fontTagBody = fontTag.trim(),
            inlineImageIds = ids,
        )
    }

    fun buildTaggedText(text: String, style: FontStyle?): String {
        if (style == null) return text
        return if (text.isBlank()) style.tag() else "$text ${style.tag()}"
    }

    fun parseStyle(tagBody: String): FontStyle? {
        val parts = tagBody.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 15) return null
        val nums = parts.take(13).map { it.toIntOrNull() ?: return null }
        val color = parts.last().toIntOrNull() ?: return null
        val face = parts.subList(13, parts.lastIndex).joinToString(" ").ifBlank { "微软雅黑" }
        return FontStyle(
            height = nums[0],
            weight = nums[4],
            italic = nums[5] != 0,
            underline = nums[6] != 0,
            strikeOut = nums[7] != 0,
            charset = nums[8],
            faceName = face,
            colorRef = color,
        )
    }

    fun inlineToken(imageId: String): String = "[[inline:$imageId]]"
    fun imageToken(path: String): String = "[[image:$path]]"
    fun emojiToken(name: String): String = "[[emoji:$name]]"

    val TOKEN = Regex("\\[\\[(emoji|inline|image):([^\\]]+)\\]\\]")
}

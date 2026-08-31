package com.feiq.droid.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.feiq.droid.net.Protocol

object Prefs {
    private const val FILE = "feiq_prefs"
    private const val KEY_NICK = "nick"
    private const val KEY_GROUP = "group"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_NOTIFY = "notify_enable"
    private const val KEY_NOTIFY_VIBRATE = "notify_vibrate"
    private const val KEY_NOTIFY_SOUND = "notify_sound"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_AUTO_RECV_FILE = "auto_recv_file"
    private const val KEY_PORT = "port"
    private const val KEY_FAVORITE_EMOJIS = "favorite_emojis"
    private const val KEY_SELF_AVATAR = "self_avatar"
    private const val KEY_RICH_TEXT = "rich_text"
    private const val KEY_RICH_BOLD = "rich_bold"
    private const val KEY_RICH_ITALIC = "rich_italic"
    private const val KEY_RICH_UNDERLINE = "rich_underline"
    private const val KEY_RICH_COLOR = "rich_color"
    private const val KEY_RICH_HEIGHT = "rich_height"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun peerKey(prefix: String, peerIp: String): String {
        val safe = peerIp.replace(Regex("[^0-9A-Za-z._-]"), "_")
        return "$prefix:$safe"
    }

    fun nick(ctx: Context): String =
        sp(ctx).getString(KEY_NICK, "\u5b89\u5353-${android.os.Build.MODEL}") ?: "\u5b89\u5353-${android.os.Build.MODEL}"
    fun setNick(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_NICK, v).apply()

    fun group(ctx: Context): String =
        sp(ctx).getString(KEY_GROUP, "\u5b89\u5353\u7ec4") ?: "\u5b89\u5353\u7ec4"
    fun setGroup(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_GROUP, v).apply()

    fun nightMode(ctx: Context): Int = sp(ctx).getInt(KEY_NIGHT, 0)
    fun setNightMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_NIGHT, mode).apply()
        applyNightMode(mode)
    }
    fun applyNightMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    fun fontScale(ctx: Context): Int = sp(ctx).getInt(KEY_FONT_SCALE, 1)
    fun setFontScale(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_FONT_SCALE, v).apply()
    fun fontScaleFactor(ctx: Context): Float = when (fontScale(ctx)) {
        0 -> 0.85f
        2 -> 1.18f
        else -> 1f
    }

    fun notifyEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_NOTIFY, true)
    fun setNotifyEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_NOTIFY, v).apply()
    fun notifyVibrate(ctx: Context) = sp(ctx).getBoolean(KEY_NOTIFY_VIBRATE, true)
    fun setNotifyVibrate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_NOTIFY_VIBRATE, v).apply()
    fun notifySound(ctx: Context) = sp(ctx).getBoolean(KEY_NOTIFY_SOUND, true)
    fun setNotifySound(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_NOTIFY_SOUND, v).apply()

    fun autoRecvFile(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_RECV_FILE, false)
    fun setAutoRecvFile(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_AUTO_RECV_FILE, v).apply()

    fun port(ctx: Context): Int = sp(ctx).getInt(KEY_PORT, Protocol.DEFAULT_PORT).coerceIn(1, 65535)
    fun setPort(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_PORT, v.coerceIn(1, 65535)).apply()

    fun isPinned(ctx: Context, peerIp: String) = sp(ctx).getBoolean(peerKey("pin", peerIp), false)
    fun setPinned(ctx: Context, peerIp: String, v: Boolean) =
        sp(ctx).edit().putBoolean(peerKey("pin", peerIp), v).apply()
    fun isMuted(ctx: Context, peerIp: String) = sp(ctx).getBoolean(peerKey("mute", peerIp), false)
    fun setMuted(ctx: Context, peerIp: String, v: Boolean) =
        sp(ctx).edit().putBoolean(peerKey("mute", peerIp), v).apply()
    fun isBlocked(ctx: Context, peerIp: String) = sp(ctx).getBoolean(peerKey("block", peerIp), false)
    fun setBlocked(ctx: Context, peerIp: String, v: Boolean) =
        sp(ctx).edit().putBoolean(peerKey("block", peerIp), v).apply()

    // 0=follow global 1=ask 2=auto 3=never
    fun fileRule(ctx: Context, peerIp: String): Int = sp(ctx).getInt(peerKey("file_rule", peerIp), 0)
    fun setFileRule(ctx: Context, peerIp: String, v: Int) =
        sp(ctx).edit().putInt(peerKey("file_rule", peerIp), v.coerceIn(0, 3)).apply()

    fun peerAvatarPath(ctx: Context, peerIp: String): String =
        sp(ctx).getString(peerKey("avatar", peerIp), "") ?: ""
    fun setPeerAvatarPath(ctx: Context, peerIp: String, path: String) =
        sp(ctx).edit().putString(peerKey("avatar", peerIp), path).apply()
    fun clearPeerAvatar(ctx: Context, peerIp: String) =
        sp(ctx).edit().remove(peerKey("avatar", peerIp)).apply()

    fun selfAvatarPath(ctx: Context): String =
        sp(ctx).getString(KEY_SELF_AVATAR, "") ?: ""
    fun setSelfAvatarPath(ctx: Context, path: String) =
        sp(ctx).edit().putString(KEY_SELF_AVATAR, path).apply()
    fun clearSelfAvatar(ctx: Context) =
        sp(ctx).edit().remove(KEY_SELF_AVATAR).apply()

    fun richTextEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_RICH_TEXT, true)
    fun setRichTextEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_RICH_TEXT, v).apply()
    fun richBold(ctx: Context) = sp(ctx).getBoolean(KEY_RICH_BOLD, false)
    fun setRichBold(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_RICH_BOLD, v).apply()
    fun richItalic(ctx: Context) = sp(ctx).getBoolean(KEY_RICH_ITALIC, false)
    fun setRichItalic(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_RICH_ITALIC, v).apply()
    fun richUnderline(ctx: Context) = sp(ctx).getBoolean(KEY_RICH_UNDERLINE, false)
    fun setRichUnderline(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_RICH_UNDERLINE, v).apply()
    fun richColor(ctx: Context) = sp(ctx).getInt(KEY_RICH_COLOR, 8404992)
    fun setRichColor(ctx: Context, colorRef: Int) =
        sp(ctx).edit().putInt(KEY_RICH_COLOR, colorRef.coerceIn(0, 0xFFFFFF)).apply()
    fun richHeight(ctx: Context) = sp(ctx).getInt(KEY_RICH_HEIGHT, -16).coerceIn(-28, -10)
    fun setRichHeight(ctx: Context, height: Int) =
        sp(ctx).edit().putInt(KEY_RICH_HEIGHT, height.coerceIn(-28, -10)).apply()

    fun favoriteEmojiSet(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_FAVORITE_EMOJIS, emptySet())?.toSet().orEmpty()
    fun isFavoriteEmoji(ctx: Context, name: String) = favoriteEmojiSet(ctx).contains(name)
    fun toggleFavoriteEmoji(ctx: Context, name: String): Boolean {
        val set = favoriteEmojiSet(ctx).toMutableSet()
        val added = if (set.contains(name)) {
            set.remove(name)
            false
        } else {
            set.add(name)
            true
        }
        sp(ctx).edit().putStringSet(KEY_FAVORITE_EMOJIS, set).apply()
        return added
    }

    fun shouldAutoReceiveFile(ctx: Context, peerIp: String): Boolean = when (fileRule(ctx, peerIp)) {
        1 -> false
        2 -> true
        3 -> false
        else -> autoRecvFile(ctx)
    }

    fun clearSession(ctx: Context, peerIp: String) {
        sp(ctx).edit()
            .remove(peerKey("pin", peerIp))
            .remove(peerKey("mute", peerIp))
            .remove(peerKey("block", peerIp))
            .remove(peerKey("file_rule", peerIp))
            .remove(peerKey("avatar", peerIp))
            .apply()
    }

    fun clearAll(ctx: Context) {
        try {
            java.io.File(ctx.filesDir, "chat").deleteRecursively()
            java.io.File(ctx.filesDir, "images").deleteRecursively()
        } catch (_: Exception) {}
        sp(ctx).edit().clear().apply()
    }
}

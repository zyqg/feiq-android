package com.feiq.droid.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** UI 小工具：头像配色、时间、文件大小格式化。 */
object UiUtil {
    private val AVATAR_COLORS = intArrayOf(
        0xFF128C7E.toInt(), 0xFF2196F3.toInt(), 0xFFFF7043.toInt(),
        0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEC407A.toInt(),
        0xFF5C6BC0.toInt(), 0xFF66BB6A.toInt(), 0xFFFFA726.toInt(),
    )

    fun avatarColor(name: String): Int {
        val h = name.ifEmpty { "?" }.hashCode()
        return AVATAR_COLORS[(h and 0x7FFFFFFF) % AVATAR_COLORS.size]
    }

    fun initial(name: String): String {
        val s = name.trim()
        return if (s.isEmpty()) "?" else s.substring(0, 1).uppercase()
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /** 会话列表的相对时间（今天显示 HH:mm，昨天显示“昨天”，更早显示 M/d）。 */
    fun listTime(ms: Long): String {
        if (ms <= 0) return ""
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        return when {
            sameDay(now, t) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            isYesterday(now, t) -> "昨天"
            else -> SimpleDateFormat("M/d", Locale.getDefault()).format(Date(ms))
        }
    }

    /** 聊天里的时间分隔（今天 HH:mm，否则 M月d日 HH:mm）。 */
    fun chatTime(ms: Long): String {
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        val fmt = if (sameDay(now, t)) "HH:mm" else "M月d日 HH:mm"
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ms))
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(now: Calendar, t: Calendar): Boolean {
        val y = now.clone() as Calendar
        y.add(Calendar.DAY_OF_YEAR, -1)
        return sameDay(y, t)
    }
}

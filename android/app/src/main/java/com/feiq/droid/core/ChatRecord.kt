package com.feiq.droid.core

import org.json.JSONObject
import java.util.UUID
import com.feiq.droid.net.FeiqRichText

data class ChatRecord(
    val dir: Int,
    val kind: Int,
    var text: String = "",
    val imagePath: String? = null,
    val fileName: String = "",
    val fileSize: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val msgId: String = "",
    var id: String = UUID.randomUUID().toString(),
    var status: Int = STATUS_NONE,
    var filePath: String? = null,
    var fileStatus: Int = FS_NONE,
    val fileId: Int = 0,
    val packetId: String = "",
    val isDir: Boolean = false,
    val richStyle: String = "",
) {
    companion object {
        const val DIR_IN = 0
        const val DIR_OUT = 1
        const val DIR_SYS = 2

        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1
        const val KIND_FILE = 2

        const val STATUS_NONE = 0
        const val STATUS_SENDING = 1
        const val STATUS_SENT = 2
        const val STATUS_FAILED = 3

        const val FS_NONE = 0
        const val FS_PENDING = 1
        const val FS_RECEIVING = 2
        const val FS_DONE = 3
        const val FS_FAILED = 4
        const val FS_REJECTED = 5

        fun fromJson(o: JSONObject) = ChatRecord(
            dir = o.optInt("dir"),
            kind = o.optInt("kind"),
            text = o.optString("text"),
            imagePath = if (o.isNull("img")) null else o.optString("img"),
            fileName = o.optString("fn"),
            fileSize = o.optLong("fs"),
            time = o.optLong("time"),
            msgId = o.optString("mid"),
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            status = o.optInt("st"),
            filePath = if (o.isNull("fp")) null else o.optString("fp"),
            fileStatus = o.optInt("fst"),
            fileId = o.optInt("fid"),
            packetId = o.optString("pid"),
            isDir = o.optBoolean("isDir"),
            richStyle = o.optString("rich"),
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("dir", dir)
        put("kind", kind)
        put("text", text)
        put("img", imagePath ?: JSONObject.NULL)
        put("fn", fileName)
        put("fs", fileSize)
        put("time", time)
        put("mid", msgId)
        put("id", id)
        put("st", status)
        put("fp", filePath ?: JSONObject.NULL)
        put("fst", fileStatus)
        put("fid", fileId)
        put("pid", packetId)
        put("isDir", isDir)
        put("rich", richStyle)
    }

    fun preview(): String = when (kind) {
        KIND_IMAGE -> "[图片]"
        KIND_FILE -> if (isDir) "[文件夹] $fileName" else "[文件] $fileName"
        else -> text.replace(FeiqRichText.TOKEN, "[表情]")
    }
}

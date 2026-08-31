package com.feiq.droid.net

object FileListCodec {
    data class FileEntry(
        val fileId: Int,
        val name: String,
        val size: Long,
        val mtime: Long,
        val attr: Int,
    ) {
        val isRegular: Boolean get() = (attr and 0xFF) == Protocol.IPMSG_FILE_REGULAR
        val isDir: Boolean get() = (attr and 0xFF) == Protocol.IPMSG_FILE_DIR
    }

    data class OutFile(
        val fileId: Int,
        val name: String,
        val size: Long,
        val mtime: Long,
        val attr: Int = Protocol.IPMSG_FILE_REGULAR,
    )

    fun buildSingle(
        fileId: Int,
        name: String,
        size: Long,
        mtime: Long,
        attr: Int = Protocol.IPMSG_FILE_REGULAR,
    ): ByteArray {
        val safeName = escapeName(name)
        val s = "$fileId:$safeName:${size.toString(16)}:${mtime.toString(16)}:${attr.toString(16)}:"
        return s.toByteArray(PacketCodec.GBK) + byteArrayOf(0x07)
    }

    fun buildMulti(files: List<OutFile>): ByteArray {
        var out = ByteArray(0)
        for (f in files) out += buildSingle(f.fileId, f.name, f.size, f.mtime, f.attr)
        return out
    }

    fun parse(raw: ByteArray): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (chunk in splitBy(raw, 0x07)) {
            val trimmed = chunk.filter { it.toInt() != 0 }.toByteArray()
            if (trimmed.isEmpty()) continue
            val parts = splitEscaped(String(trimmed, PacketCodec.GBK))
            if (parts.size < 5) continue
            try {
                val fid = parts[0].trim().toIntOrNull() ?: continue
                val size = parts[2].toLongOrNull(16) ?: 0L
                val mtime = parts[3].toLongOrNull(16) ?: 0L
                val attr = parts[4].toIntOrNull(16) ?: Protocol.IPMSG_FILE_REGULAR
                out.add(FileEntry(fid, unescapeName(parts[1]), size, mtime, attr))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun escapeName(name: String): String = name.replace(":", "::")
    fun unescapeName(name: String): String = name.replace("::", ":")

    fun splitEscaped(text: String): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ':') {
                if (i + 1 < text.length && text[i + 1] == ':') {
                    cur.append("::")
                    i += 2
                } else {
                    parts.add(cur.toString())
                    cur.setLength(0)
                    i++
                }
            } else {
                cur.append(ch)
                i++
            }
        }
        parts.add(cur.toString())
        return parts
    }

    private fun splitBy(data: ByteArray, sep: Int): List<ByteArray> {
        val res = ArrayList<ByteArray>()
        var start = 0
        for (i in data.indices) {
            if (data[i].toInt() == sep) {
                res.add(data.copyOfRange(start, i))
                start = i + 1
            }
        }
        if (start < data.size) res.add(data.copyOfRange(start, data.size))
        return res
    }
}

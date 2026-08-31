package com.feiq.droid.net

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object DirFileCodec {
    data class Entry(
        val name: String,
        val size: Long,
        val attr: Int,
        val open: (() -> InputStream)? = null,
        val children: List<Entry> = emptyList(),
    ) {
        val isDir: Boolean get() = (attr and 0xFF) == Protocol.IPMSG_FILE_DIR
        val isFile: Boolean get() = (attr and 0xFF) == Protocol.IPMSG_FILE_REGULAR
    }

    fun totalFileBytes(entry: Entry): Long =
        if (entry.isFile) entry.size else entry.children.sumOf { totalFileBytes(it) }

    fun writeTree(root: Entry, out: OutputStream, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }) {
        var sent = 0L
        val total = totalFileBytes(root)
        writeEntry(root, out) { n ->
            sent += n
            onProgress(sent, total)
        }
        out.flush()
    }

    fun readTree(input: InputStream, destRoot: File, onProgress: (got: Long, total: Long) -> Unit = { _, _ -> }): Long {
        destRoot.mkdirs()
        val stack = ArrayDeque<File>()
        stack.add(destRoot)
        var got = 0L
        while (true) {
            val header = readHeader(input) ?: break
            val parts = FileListCodec.splitEscaped(header.removeSuffix(":"))
            if (parts.size < 4) break
            val name = safeName(FileListCodec.unescapeName(parts[1]))
            val size = parts[2].toLongOrNull(16) ?: 0L
            val attr = parts[3].toIntOrNull(16) ?: Protocol.IPMSG_FILE_REGULAR
            when (attr and 0xFF) {
                Protocol.IPMSG_FILE_DIR -> {
                    val dir = uniqueFile(stack.last(), name)
                    dir.mkdirs()
                    stack.add(dir)
                }
                Protocol.IPMSG_FILE_RETPARENT -> {
                    if (stack.size > 1) stack.removeLast()
                }
                Protocol.IPMSG_FILE_REGULAR -> {
                    val file = uniqueFile(stack.last(), name)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        got += copyExact(input, out, size) { done ->
                            onProgress(got + done, 0)
                        }
                    }
                    onProgress(got, 0)
                }
                else -> skipExact(input, size)
            }
        }
        return got
    }

    private fun writeEntry(entry: Entry, out: OutputStream, onFileBytes: (Long) -> Unit) {
        val name = safeName(entry.name)
        when {
            entry.isDir -> {
                writeHeader(out, name, 0, Protocol.IPMSG_FILE_DIR)
                entry.children.forEach { writeEntry(it, out, onFileBytes) }
                writeHeader(out, ".", 0, Protocol.IPMSG_FILE_RETPARENT)
            }
            entry.isFile -> {
                writeHeader(out, name, entry.size, Protocol.IPMSG_FILE_REGULAR)
                val buf = ByteArray(64 * 1024)
                entry.open?.invoke()?.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        onFileBytes(r.toLong())
                    }
                }
            }
        }
    }

    private fun writeHeader(out: OutputStream, name: String, size: Long, attr: Int) {
        val body = "${FileListCodec.escapeName(name)}:${size.toString(16)}:${attr.toString(16)}:"
        val bodyBytes = body.toByteArray(PacketCodec.GBK)
        var hex = ""
        while (true) {
            val total = hex.toByteArray(Charsets.US_ASCII).size + 1 + bodyBytes.size
            val next = total.toString(16)
            if (next == hex) break
            hex = next
        }
        out.write(hex.toByteArray(Charsets.US_ASCII))
        out.write(':'.code)
        out.write(bodyBytes)
    }

    private fun readHeader(input: InputStream): String? {
        val hex = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == ':'.code) break
            hex.append(b.toChar())
            if (hex.length > 16) return null
        }
        val headerSize = hex.toString().toIntOrNull(16) ?: return null
        val prefixSize = hex.length + 1
        val remain = headerSize - prefixSize
        if (remain < 0 || remain > 64 * 1024) return null
        val rest = ByteArray(remain)
        readFully(input, rest)
        return hex.toString() + ":" + String(rest, PacketCodec.GBK)
    }

    private fun copyExact(input: InputStream, out: OutputStream, size: Long, progress: (Long) -> Unit): Long {
        val buf = ByteArray(64 * 1024)
        var left = size
        var copied = 0L
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException()
            out.write(buf, 0, r)
            left -= r
            copied += r
            progress(copied)
        }
        return copied
    }

    private fun skipExact(input: InputStream, size: Long) {
        var left = size
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else {
                left -= s
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }

    private fun safeName(name: String): String =
        name.replace('\\', '_').replace('/', '_').ifBlank { "folder" }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base($i)$ext")
            i++
        }
        return f
    }
}

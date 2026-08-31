package com.feiq.droid.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class TcpFileServer(
    private val portProvider: () -> Int = { Protocol.DEFAULT_PORT },
    private val provider: (packetId: String, fileId: Int) -> OfferedFile?,
    private val onProgress: (fileId: Int, sent: Long, total: Long) -> Unit = { _, _, _ -> },
) {
    companion object { private const val TAG = "TcpFileServer" }

    data class OfferedFile(
        val stream: InputStream,
        val size: Long,
        val isDir: Boolean = false,
    )

    private var server: ServerSocket? = null
    private var scope = newScope()
    @Volatile private var running = false

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (running) return
        val port = portProvider()
        try {
            server = ServerSocket(port)
            running = true
            scope.launch { acceptLoop() }
            Log.i(TAG, "TCP file server listening on $port")
        } catch (e: Exception) {
            Log.e(TAG, "TCP listen failed: ${e.message}")
        }
    }

    private fun acceptLoop() {
        val srv = server ?: return
        while (running) {
            try {
                val conn = srv.accept()
                scope.launch { handle(conn) }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept error: ${e.message}")
            }
        }
    }

    private fun handle(conn: Socket) {
        conn.use {
            try {
                val req = ByteArray(4096)
                val n = conn.getInputStream().read(req)
                if (n <= 0) return
                val pkt = PacketCodec.parse(req, n) ?: return
                if (pkt.commandBase != Protocol.IPMSG_GETFILEDATA &&
                    pkt.commandBase != Protocol.IPMSG_GETDIRFILES) {
                    Log.w(TAG, "unexpected file command: ${Protocol.describe(pkt.command)}")
                    return
                }

                val fields = pkt.extraMain.split(":")
                if (fields.size < 2) return
                val packetIdRaw = fields[0].trim()
                val packetId = packetIdRaw.toLongOrNull(16)?.toString() ?: packetIdRaw
                val fileId = fields[1].trim().toIntOrNull(16)
                    ?: fields[1].trim().toIntOrNull() ?: 0
                val offset = if (fields.size >= 3) fields[2].trim().toLongOrNull(16) ?: 0L else 0L

                var offered = provider(packetId, fileId)
                if (offered == null && packetId != packetIdRaw) offered = provider(packetIdRaw, fileId)
                if (offered == null) {
                    Log.w(TAG, "offered file not found packet=$packetId raw=$packetIdRaw file=$fileId")
                    return
                }

                if (pkt.commandBase == Protocol.IPMSG_GETDIRFILES || offered.isDir) {
                    streamOut(conn.getOutputStream(), offered, 0L, fileId)
                } else {
                    streamOut(conn.getOutputStream(), offered, offset, fileId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "handle error: ${e.message}")
            }
        }
    }

    private fun streamOut(out: OutputStream, file: OfferedFile, offset: Long, fileId: Int) {
        file.stream.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val s = input.skip(offset - skipped)
                if (s <= 0) break
                skipped += s
            }
            val buf = ByteArray(64 * 1024)
            var sent = offset
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                sent += r
                onProgress(fileId, sent, file.size)
            }
            out.flush()
            Log.i(TAG, "file item $fileId sent ($sent/${file.size})")
        }
    }

    fun stop() {
        running = false
        server?.close()
        server = null
        scope.cancel()
        scope = newScope()
    }
}

object TcpFileClient {
    private const val TAG = "TcpFileClient"

    fun download(
        ip: String,
        port: Int = Protocol.DEFAULT_PORT,
        packetId: String,
        fileId: Int,
        size: Long,
        versionField: String,
        user: String,
        host: String,
        sink: OutputStream,
        onProgress: (got: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        val req = PacketCodec.build(
            versionField,
            user,
            host,
            Protocol.IPMSG_GETFILEDATA,
            requestExtra(packetId, fileId, 0L),
        )
        Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(InetAddress.getByName(ip), port), 10000)
            sock.soTimeout = 15000
            sock.getOutputStream().write(req)
            sock.getOutputStream().flush()
            val input = sock.getInputStream()
            val buf = ByteArray(64 * 1024)
            var got = 0L
            while (size <= 0 || got < size) {
                val want = if (size > 0) minOf(buf.size.toLong(), size - got).toInt() else buf.size
                val r = input.read(buf, 0, want)
                if (r < 0) break
                sink.write(buf, 0, r)
                got += r
                onProgress(got, size)
            }
            sink.flush()
            Log.i(TAG, "download complete $got/$size from $ip")
            return got
        }
    }

    fun downloadDir(
        ip: String,
        port: Int = Protocol.DEFAULT_PORT,
        packetId: String,
        fileId: Int,
        versionField: String,
        user: String,
        host: String,
        destRoot: File,
        onProgress: (got: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        val req = PacketCodec.build(
            versionField,
            user,
            host,
            Protocol.IPMSG_GETDIRFILES,
            requestExtra(packetId, fileId, null),
        )
        Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(InetAddress.getByName(ip), port), 10000)
            sock.soTimeout = 30000
            sock.getOutputStream().write(req)
            sock.getOutputStream().flush()
            val got = DirFileCodec.readTree(sock.getInputStream(), destRoot, onProgress)
            Log.i(TAG, "directory download complete $got bytes from $ip")
            return got
        }
    }

    private fun requestExtra(packetId: String, fileId: Int, offset: Long?): ByteArray {
        val pidHex = packetId.toLongOrNull()?.toString(16) ?: packetId
        val base = "$pidHex:${fileId.toString(16)}"
        val s = if (offset == null) base else "$base:${offset.toString(16)}"
        return s.toByteArray(Charsets.US_ASCII)
    }
}

package com.feiq.droid.net

import android.util.Log

/**
 * Reassembles FeiQ inline image chunks.
 *
 * FeiQ sends a SENDMSG marker like /~#>imageId<B~, then UDP chunks with
 * command 0x2000C0. Each chunk extra field is:
 * imageId|total|offset|totalChunks|index|chunkSize|0|2|0|00000000#\0<payload>
 */
class InlineImageReceiver(
    private val onComplete: (peerIp: String, imageId: String, data: ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "InlineImage"

        fun parseHeader(header: String, payloadSize: Int): ChunkInfo? {
            val f = header.trim().removeSuffix("#").split("|")
            if (f.size < 6) return null
            val imageId = f[0]
            val total = f[1].toLongOrNull() ?: return null
            val offset = f[2].toLongOrNull() ?: return null
            val totalChunks = f[3].toIntOrNull() ?: return null
            val index = f[4].toIntOrNull() ?: return null
            val chunkSize = f[5].toIntOrNull() ?: payloadSize
            return ChunkInfo(imageId, total, offset, totalChunks, index, chunkSize)
        }
    }

    private data class Buffer(
        val total: Long,
        val totalChunks: Int,
        val data: ByteArray,
        val received: BooleanArray,
        var receivedBytes: Long = 0,
        var maxIndex: Int = 0,
        var packets: Int = 0,
        val startMs: Long = System.currentTimeMillis(),
        var lastMs: Long = System.currentTimeMillis(),
        var dupPackets: Int = 0,
        var maxGapMs: Long = 0,
        var maxGapIdx: Int = 0,
    )

    private val buffers = HashMap<String, Buffer>()

    fun onChunk(peerIp: String, header: String, payload: ByteArray): ChunkInfo? {
        val info = parseHeader(header, payload.size) ?: return null
        return onChunk(peerIp, info, payload)
    }

    fun onChunk(peerIp: String, info: ChunkInfo, payload: ByteArray): ChunkInfo? {
        if (info.total <= 0 || info.total > Int.MAX_VALUE || info.totalChunks <= 0) return null
        val buf = buffers.getOrPut(info.imageId) {
            Log.i(TAG, "new image ${info.imageId} total=${info.total} chunks=${info.totalChunks}")
            Buffer(
                total = info.total,
                totalChunks = info.totalChunks,
                data = ByteArray(info.total.toInt()),
                received = BooleanArray(info.totalChunks + 2),
            )
        }
        buf.packets++

        val now = System.currentTimeMillis()
        val gap = now - buf.lastMs
        if (gap > buf.maxGapMs) {
            buf.maxGapMs = gap
            buf.maxGapIdx = info.index
        }
        buf.lastMs = now

        val n = minOf(payload.size, (info.total - info.offset).toInt().coerceAtLeast(0))
        if (
            info.offset >= 0 &&
            info.offset + n <= info.total &&
            info.index in 0..buf.received.lastIndex &&
            !buf.received[info.index]
        ) {
            System.arraycopy(payload, 0, buf.data, info.offset.toInt(), n)
            buf.received[info.index] = true
            buf.receivedBytes += n
            if (info.index > buf.maxIndex) buf.maxIndex = info.index
        } else if (info.index in 0..buf.received.lastIndex && buf.received[info.index]) {
            buf.dupPackets++
        }

        if (buf.receivedBytes >= info.total) {
            val elapsed = now - buf.startMs
            Log.i(
                TAG,
                "image ${info.imageId} complete bytes=${buf.receivedBytes} elapsed=${elapsed}ms " +
                    "packets=${buf.packets} dup=${buf.dupPackets} maxGap=${buf.maxGapMs}ms@${buf.maxGapIdx}",
            )
            buffers.remove(info.imageId)
            onComplete(peerIp, info.imageId, buf.data)
        }
        return info
    }

    data class ChunkInfo(
        val imageId: String,
        val total: Long,
        val offset: Long,
        val totalChunks: Int,
        val index: Int,
        val chunkSize: Int,
    )

    fun clear() = buffers.clear()
}

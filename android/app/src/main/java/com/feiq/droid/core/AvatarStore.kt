package com.feiq.droid.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

object AvatarStore {
    private const val AVATAR_SIZE = 320

    fun savePeerAvatar(context: Context, peerIp: String, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return savePeerAvatarBytes(context, peerIp, bytes, AVATAR_SIZE, 88)
    }

    fun saveSelfAvatar(context: Context, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return saveSelfAvatarBytes(context, bytes, AVATAR_SIZE, 88)
    }

    fun savePeerAvatarBytes(context: Context, peerIp: String, bytes: ByteArray, size: Int = AVATAR_SIZE, quality: Int = 88): String? {
        val out = cropAndCompress(bytes, size, quality) ?: return null
        val file = File(avatarDir(context), sanitize(peerIp) + ".jpg")
        file.writeBytes(out)
        return file.absolutePath
    }

    fun saveSelfAvatarBytes(context: Context, bytes: ByteArray, size: Int = AVATAR_SIZE, quality: Int = 88): String? {
        val out = cropAndCompress(bytes, size, quality) ?: return null
        val file = File(avatarDir(context), "self.jpg")
        file.writeBytes(out)
        return file.absolutePath
    }

    private fun cropAndCompress(bytes: ByteArray, size: Int, quality: Int): ByteArray? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val edge = minOf(src.width, src.height)
        if (edge <= 0) return null
        val x = (src.width - edge) / 2
        val y = (src.height - edge) / 2
        val square = Bitmap.createBitmap(src, x, y, edge, edge)
        val scaled = if (edge == size) square else Bitmap.createScaledBitmap(square, size, size, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        if (scaled !== square) scaled.recycle()
        if (square !== src) square.recycle()
        src.recycle()
        return out.toByteArray()
    }

    private fun avatarDir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^0-9A-Za-z._-]"), "_")
}

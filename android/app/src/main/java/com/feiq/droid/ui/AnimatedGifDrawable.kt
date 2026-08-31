package com.feiq.droid.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import java.io.ByteArrayInputStream

class AnimatedGifDrawable(
    bytes: ByteArray,
    private val owner: View? = null,
) : Drawable() {
    private val movie: Movie? = Movie.decodeStream(ByteArrayInputStream(bytes))
    private val start = SystemClock.uptimeMillis()
    private val duration = movie?.duration()?.takeIf { it > 0 } ?: 1000

    override fun draw(canvas: Canvas) {
        val m = movie ?: return
        val now = SystemClock.uptimeMillis()
        m.setTime(((now - start) % duration).toInt())
        val w = m.width().takeIf { it > 0 } ?: bounds.width()
        val h = m.height().takeIf { it > 0 } ?: bounds.height()
        val sx = bounds.width().toFloat() / w.toFloat()
        val sy = bounds.height().toFloat() / h.toFloat()
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(sx, sy)
        m.draw(canvas, 0f, 0f)
        canvas.restoreToCount(save)
        invalidateOwner()
    }

    private fun invalidateOwner() {
        invalidateSelf()
        owner?.removeCallbacks(tick)
        owner?.postDelayed(tick, 80L)
    }

    private val tick = Runnable {
        invalidateSelf()
        owner?.postInvalidateOnAnimation()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

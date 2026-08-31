package com.feiq.droid.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可缩放图片视图：双击放大/还原(带平滑动画)、双指捏合缩放、单指拖动。
 * 缩放到边界时把触摸事件交还父级(供 ViewPager 翻页)。
 */
class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    private val matrix0 = Matrix()
    private val m = FloatArray(9)
    private var minScale = 1f
    private val maxScale = 5f
    private var viewW = 0f
    private var viewH = 0f
    private var drawableW = 0f
    private var drawableH = 0f
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            var factor = d.scaleFactor
            val cur = curScale()
            if (cur * factor < minScale) factor = minScale / cur
            if (cur * factor > maxScale) factor = maxScale / cur
            matrix0.postScale(factor, factor, d.focusX, d.focusY)
            fixTranslation()
            imageMatrix = matrix0
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (curScale() > minScale * 1.1f) minScale else minScale * 3f
            animateZoomTo(target, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (curScale() > minScale * 1.01f) {
                matrix0.postTranslate(-dx, -dy)
                fixTranslation()
                imageMatrix = matrix0
                // 放大状态下，自己消费拖动，阻止 ViewPager 翻页
                parent?.requestDisallowInterceptTouchEvent(true)
            } else {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && curScale() <= minScale * 1.01f) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            true
        }
    }

    private fun curScale(): Float { matrix0.getValues(m); return m[Matrix.MSCALE_X] }

    /** 平滑动画把缩放推到 target，焦点在 (fx, fy)。 */
    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        // 先把当前矩阵存住，再算出"终态矩阵"
        val startVals = FloatArray(9); matrix0.getValues(startVals)
        val endMatrix = Matrix(matrix0)
        val factor = target / curScale()
        endMatrix.postScale(factor, factor, fx, fy)
        // 计算终态后的位移修正(等价 fixTranslation 但作用于 endMatrix)
        endMatrix.getValues(m)
        val w = drawableW * m[Matrix.MSCALE_X]; val h = drawableH * m[Matrix.MSCALE_Y]
        val tx = m[Matrix.MTRANS_X]; val ty = m[Matrix.MTRANS_Y]
        val fixX = when {
            w <= viewW -> (viewW - w) / 2f - tx
            tx > 0 -> -tx
            tx < viewW - w -> viewW - w - tx
            else -> 0f
        }
        val fixY = when {
            h <= viewH -> (viewH - h) / 2f - ty
            ty > 0 -> -ty
            ty < viewH - h -> viewH - h - ty
            else -> 0f
        }
        endMatrix.postTranslate(fixX, fixY)
        val endVals = FloatArray(9); endMatrix.getValues(endVals)

        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val cur = FloatArray(9)
                for (i in 0 until 9) cur[i] = startVals[i] + (endVals[i] - startVals[i]) * t
                matrix0.setValues(cur)
                imageMatrix = matrix0
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    matrix0.setValues(endVals)
                    imageMatrix = matrix0
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        viewW = w.toFloat(); viewH = h.toFloat()
        resetToFit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        drawableW = (bm?.width ?: 0).toFloat()
        drawableH = (bm?.height ?: 0).toFloat()
        resetToFit()
    }

    private fun resetToFit() {
        if (drawableW <= 0 || viewW <= 0) return
        matrix0.reset()
        val scale = minOf(viewW / drawableW, viewH / drawableH)
        minScale = scale
        matrix0.postScale(scale, scale)
        // 居中
        val dx = (viewW - drawableW * scale) / 2f
        val dy = (viewH - drawableH * scale) / 2f
        matrix0.postTranslate(dx, dy)
        imageMatrix = matrix0
    }

    private fun fixTranslation() {
        matrix0.getValues(m)
        val transX = m[Matrix.MTRANS_X]; val transY = m[Matrix.MTRANS_Y]
        val w = drawableW * curScale(); val h = drawableH * curScale()
        var fixX = 0f; var fixY = 0f
        fixX = when {
            w <= viewW -> (viewW - w) / 2f - transX
            transX > 0 -> -transX
            transX < viewW - w -> viewW - w - transX
            else -> 0f
        }
        fixY = when {
            h <= viewH -> (viewH - h) / 2f - transY
            transY > 0 -> -transY
            transY < viewH - h -> viewH - h - transY
            else -> 0f
        }
        matrix0.postTranslate(fixX, fixY)
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The same gesture as [SwipeToQueueElement] for the screens that still use RecyclerView rows.
 *
 * The patch calls [attach] from the adapter delegate that binds every legacy row.
 */
object LegacyRowSwipe {

    private const val LOG_TAG = "morphe-swipe-to-queue"
    private const val TAG_KEY = 0x4d535751 // "MSWQ"

    @JvmStatic
    fun attach(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?) {
        val itemView = viewHolder?.itemView ?: return
        try {
            if (itemView.getTag(TAG_KEY) != null) return
            itemView.setTag(TAG_KEY, true)
            itemView.setOnTouchListener(RowTouchListener())
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Could not attach legacy swipe", ex)
        }
    }

    private class RowTouchListener : View.OnTouchListener {

        private val density = android.content.res.Resources.getSystem().displayMetrics.density
        private val touchSlop = 12f * density

        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var active = false
        private var indicator: QueueIndicator? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    active = false
                    tracking = view.isLongClickable
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!active) {
                        if (abs(dy) > touchSlop || dx < -touchSlop) {
                            tracking = false
                            return false
                        }
                        if (dx <= touchSlop || dx < abs(dy) * 1.5f) return false
                        active = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        showIndicator(view)
                    }
                    val offset = min(max(dx - touchSlop, 0f), view.width * 0.5f)
                    view.translationX = offset
                    indicator?.update(offset, threshold(view))
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!active) {
                        tracking = false
                        return false
                    }
                    val committed = event.actionMasked == MotionEvent.ACTION_UP &&
                        view.translationX >= threshold(view)
                    active = false
                    tracking = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (committed) {
                        SwipeToQueue.arm()
                        view.performLongClick()
                    }
                    settle(view)
                    return true
                }
            }
            return false
        }

        private fun threshold(view: View) = min(view.width * 0.28f, 96f * density)

        private fun showIndicator(view: View) {
            val parent = view.parent as? ViewGroup ?: return
            val drawable = QueueIndicator(view, density)
            indicator = drawable
            parent.overlay.add(drawable)
        }

        private fun hideIndicator(view: View) {
            val drawable = indicator ?: return
            indicator = null
            (view.parent as? ViewGroup)?.overlay?.remove(drawable)
        }

        private fun settle(view: View) {
            val from = view.translationX
            if (from <= 0f) {
                hideIndicator(view)
                return
            }
            ValueAnimator.ofFloat(from, 0f).apply {
                duration = 220L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val value = it.animatedValue as Float
                    view.translationX = value
                    indicator?.update(value, threshold(view))
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        view.translationX = 0f
                        hideIndicator(view)
                    }
                })
                start()
            }
        }
    }

    /** Green strip with the queue glyph, drawn in the area the row uncovers. */
    private class QueueIndicator(
        private val row: View,
        private val density: Float,
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var offset = 0f
        private var progress = 0f

        fun update(offset: Float, threshold: Float) {
            this.offset = offset
            this.progress = if (threshold <= 0f) 0f else min(offset / threshold, 1f)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (offset <= 0.5f) return
            val top = row.top.toFloat()
            val bottom = row.bottom.toFloat()
            val left = row.left.toFloat()

            paint.color = ACCENT
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + offset, bottom, paint)

            val size = 22f * density * (0.6f + 0.4f * progress)
            val centerX = left + min(offset / 2f, offset - size)
            if (centerX - left < size / 2f) return
            val centerY = (top + bottom) / 2f
            paint.color = Color.argb((255 * progress).toInt(), 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1.5f * density, 2f * density)
            paint.strokeCap = Paint.Cap.ROUND

            val lineLeft = centerX - size / 2f
            val lineRight = centerX + size / 2f
            val gap = size / 3.4f
            for (line in 0..2) {
                val y = centerY - gap + line * gap
                val end = if (line == 2) lineRight - size * 0.42f else lineRight
                canvas.drawLine(lineLeft, y, end, y, paint)
            }
            val plusX = lineRight - size * 0.16f
            val plusY = centerY + gap
            val arm = size * 0.2f
            canvas.drawLine(plusX - arm, plusY, plusX + arm, plusY, paint)
            canvas.drawLine(plusX, plusY - arm, plusX, plusY + arm, paint)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private companion object {
            const val ACCENT = 0xFF1DB954.toInt()
        }
    }
}

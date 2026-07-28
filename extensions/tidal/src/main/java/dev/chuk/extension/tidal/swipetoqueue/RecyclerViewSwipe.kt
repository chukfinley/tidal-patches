/*
 * Copyright 2026 chukfinley.
 * https://github.com/chukfinley/tidal-patches
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/**
 * The same gesture as [SwipeToQueueElement] for the screens that still use RecyclerView rows,
 * for example the favourite tracks list.
 *
 * The patch calls [attach] from `RecyclerView.setAdapter`, so every list in the app is covered
 * without knowing anything about its adapter.
 */
object RecyclerViewSwipe {

    private const val LOG_TAG = "morphe-swipe-to-queue"
    private const val TAG_KEY = 0x4d535751 // "MSWQ"

    /** How long the row stays pushed out before it snaps back. */
    private const val HOLD_MS = 160L

    private val handler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun attach(recyclerView: RecyclerView?) {
        if (recyclerView == null) return
        try {
            if (recyclerView.getTag(TAG_KEY) != null) return
            recyclerView.setTag(TAG_KEY, true)
            recyclerView.addOnItemTouchListener(SwipeInterceptor())
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Could not attach list swipe", ex)
        }
    }

    private class SwipeInterceptor : RecyclerView.OnItemTouchListener {

        private val density = Resources.getSystem().displayMetrics.density
        private val armDistance = 12f * density
        private val triggerDistance = 40f * density
        private val maxRowHeight = 132f * density

        private var downX = 0f
        private var downY = 0f
        private var tracking = false
        private var fired = false

        override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    fired = false
                    tracking = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || fired) return fired
                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (abs(dy) > triggerDistance || dx < -triggerDistance) {
                        tracking = false
                        return false
                    }
                    if (dx < abs(dy) * 1.5f) return false

                    // Arm early: from here on the app's own long press could fire, and its menu
                    // has to be turned into a queue action just like the one below.
                    if (dx > armDistance) SwipeToQueue.arm()
                    if (dx < triggerDistance) return false

                    val row = recyclerView.findChildViewUnder(downX, downY) ?: return false
                    if (row.height > maxRowHeight || !row.isLongClickable) return false

                    fired = true
                    if (SwipeToQueue.isHandled()) return true
                    SwipeToQueue.arm()
                    try {
                        row.performLongClick()
                    } catch (ex: Throwable) {
                        Log.e(LOG_TAG, "Could not fire long click", ex)
                    }
                    confirm(recyclerView, row)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking) SwipeToQueue.endGesture()
                    tracking = false
                    fired = false
                }
            }
            return false
        }

        /** Swallows the rest of the gesture so it never turns into a click or a scroll. */
        override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (tracking) SwipeToQueue.endGesture()
                tracking = false
                fired = false
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit

        /**
         * The same confirmation the Compose rows show: the row snaps to the right, uncovering a
         * strip with the queue glyph, and snaps back. Both steps are instant.
         */
        private fun confirm(recyclerView: RecyclerView, row: View) {
            val offset = 64f * density
            val indicator = QueueIndicator(row, density)
            indicator.update(offset)
            recyclerView.overlay.add(indicator)
            row.translationX = offset

            handler.postDelayed({
                row.translationX = 0f
                recyclerView.overlay.remove(indicator)
            }, HOLD_MS)
        }
    }

    /** Green strip with the queue glyph, drawn in the area the row uncovers. */
    private class QueueIndicator(
        private val row: View,
        private val density: Float,
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var offset = 0f

        fun update(offset: Float) {
            this.offset = offset
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (offset <= 0.5f) return
            val top = row.top.toFloat()
            val bottom = row.bottom.toFloat()
            val left = row.left.toFloat()

            paint.style = Paint.Style.FILL
            paint.color = ACCENT
            canvas.drawRect(left, top, left + offset, bottom, paint)

            val glyph = 22f * density
            if (offset < glyph * 1.5f) return
            val centerX = left + min(offset / 2f, offset - glyph)
            val centerY = (top + bottom) / 2f

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE

            val glyphLeft = centerX - glyph / 2f
            val glyphRight = centerX + glyph / 2f
            val gap = glyph / 3.4f
            for (line in 0..2) {
                val y = centerY - gap + line * gap
                val end = if (line == 2) glyphRight - glyph * 0.42f else glyphRight
                canvas.drawLine(glyphLeft, y, end, y, paint)
            }
            val plusX = glyphRight - glyph * 0.16f
            val plusY = centerY + gap
            val arm = glyph * 0.2f
            canvas.drawLine(plusX - arm, plusY, plusX + arm, plusY, paint)
            canvas.drawLine(plusX, plusY - arm, plusX, plusY + arm, paint)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private companion object {
            /** Spotify's "add to queue" green. */
            val ACCENT = Color.rgb(29, 185, 84)
        }
    }
}

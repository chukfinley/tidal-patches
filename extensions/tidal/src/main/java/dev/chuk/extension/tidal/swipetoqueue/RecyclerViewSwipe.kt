/*
 * Copyright 2026 chukfinley.
 * https://github.com/chukfinley/tidal-patches
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.content.res.Resources
import android.util.Log
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

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
    }
}

/*
 * Copyright 2026 chukfinley.
 * https://github.com/chukfinley/tidal-patches
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.content.res.Resources
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Modifier element the patch appends to every long clickable Compose row.
 *
 * The row itself is never moved or redrawn: as soon as the drag passes the threshold the item is
 * queued and the rest of the gesture is swallowed. Nothing to animate means nothing to stutter.
 *
 * Components that are not list rows (buttons, grid cells, chips) are filtered out at runtime by
 * [SwipeToQueueNode.isRowLike], which only accepts near full width, row height layouts.
 */
internal class SwipeToQueueElement(
    private val onLongClick: Any,
) : ModifierNodeElement<SwipeToQueueNode>() {

    override fun create() = SwipeToQueueNode(onLongClick)

    override fun update(node: SwipeToQueueNode) {
        node.onLongClick = onLongClick
    }

    override fun hashCode() = onLongClick.hashCode()

    override fun equals(other: Any?) =
        other is SwipeToQueueElement && other.onLongClick == onLongClick
}

internal class SwipeToQueueNode(
    var onLongClick: Any,
) : Modifier.Node(), PointerInputModifierNode, LayoutAwareModifierNode {

    private val density = Resources.getSystem().displayMetrics.density
    private val armDistance = 12f * density
    private val triggerDistance = 40f * density
    private val minRowHeight = 32f * density
    private val maxRowHeight = 132f * density

    private var rootWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var fired = false

    override fun onPlaced(coordinates: LayoutCoordinates) {
        rootWidth = coordinates.findRootCoordinates().size.width
    }

    override fun onCancelPointerInput() {
        if (tracking) SwipeToQueue.endGesture()
        tracking = false
        fired = false
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Main) return
        val change = pointerEvent.changes.firstOrNull() ?: return

        when (pointerEvent.type) {
            PointerEventType.Press -> {
                fired = false
                tracking = pointerEvent.changes.size == 1 && isRowLike(bounds)
                if (tracking) {
                    downX = change.position.x
                    downY = change.position.y
                }
            }

            PointerEventType.Move -> {
                if (!tracking) return
                if (fired) {
                    // Keep the rest of the drag away from the click and the list scroll.
                    change.consume()
                    return
                }

                val dx = change.position.x - downX
                val dy = change.position.y - downY

                // A vertical drag belongs to the list, a left drag to whatever is behind it.
                if (abs(dy) > triggerDistance || dx < -triggerDistance) {
                    tracking = false
                    return
                }
                if (dx < abs(dy) * 1.5f) return

                // Arm early: from here on the app's own long press detector could fire, and its
                // menu has to be turned into a queue action just like the one below.
                if (dx > armDistance) SwipeToQueue.arm()
                if (dx < triggerDistance) return

                fired = true
                change.consume()
                SwipeToQueue.triggerFromCompose(onLongClick)
            }

            PointerEventType.Release -> {
                if (fired) change.consume()
                if (tracking) SwipeToQueue.endGesture()
                tracking = false
                fired = false
            }

            else -> Unit
        }
    }

    private fun isRowLike(bounds: IntSize): Boolean {
        if (rootWidth <= 0) return false
        if (bounds.width < rootWidth * 0.6f) return false
        return bounds.height in minRowHeight.toInt()..maxRowHeight.toInt()
    }
}

/*
 * Copyright 2026 chukfinley.
 * https://github.com/chukfinley/tidal-patches
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.animation.ValueAnimator
import android.content.res.Resources
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
) : Modifier.Node(), PointerInputModifierNode, DrawModifierNode, LayoutAwareModifierNode {

    private val density = Resources.getSystem().displayMetrics.density
    private val armDistance = 12f * density
    private val triggerDistance = 40f * density
    private val minRowHeight = 32f * density
    private val maxRowHeight = 132f * density
    private val pushDistance = 56f * density
    private val iconSize = 22f * density

    private var rootWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var fired = false

    /** 0 while idle, 1 at the peak of the confirmation. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        rootWidth = coordinates.findRootCoordinates().size.width
    }

    override fun onDetach() {
        animator?.cancel()
        animator = null
        progress = 0f
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
                confirm()
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

    /**
     * The confirmation: one short push of the row to the right and back, over a strip carrying
     * the queue glyph. It is a fixed 200ms curve rather than something that follows the finger,
     * so it always looks the same and cannot stutter along with the drag.
     */
    private fun confirm() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener {
                progress = it.animatedValue as Float
                if (isAttached) invalidateDraw()
            }
            start()
        }
    }

    override fun ContentDrawScope.draw() {
        val current = progress
        if (current <= 0f) {
            drawContent()
            return
        }

        // Out and back within the single curve.
        val travel = sin(current * Math.PI).toFloat()
        val offset = travel * pushDistance

        drawRect(
            color = ACCENT,
            topLeft = Offset.Zero,
            size = Size(max(offset, 0f), size.height),
        )
        drawQueueIcon(offset, size.height, travel)

        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(offset, 0f)
        drawContent()
        canvas.restore()
    }

    /** Three list lines with a plus, drawn from primitives so the patch adds no drawable. */
    private fun ContentDrawScope.drawQueueIcon(revealed: Float, height: Float, travel: Float) {
        val glyph = iconSize * (0.7f + 0.3f * travel)
        if (revealed < glyph * 1.5f) return

        val centerX = min(revealed / 2f, revealed - glyph)
        val centerY = height / 2f
        val stroke = 2f * density
        val color = Color.White.copy(alpha = min(travel * 1.6f, 1f))

        val left = centerX - glyph / 2f
        val right = centerX + glyph / 2f
        val gap = glyph / 3.4f

        for (line in 0..2) {
            val y = centerY - gap + line * gap
            val end = if (line == 2) right - glyph * 0.42f else right
            drawLine(color, Offset(left, y), Offset(end, y), stroke, StrokeCap.Round)
        }

        val plusX = right - glyph * 0.16f
        val plusY = centerY + gap
        val arm = glyph * 0.2f
        drawLine(color, Offset(plusX - arm, plusY), Offset(plusX + arm, plusY), stroke, StrokeCap.Round)
        drawLine(color, Offset(plusX, plusY - arm), Offset(plusX, plusY + arm), stroke, StrokeCap.Round)
    }

    private fun isRowLike(bounds: IntSize): Boolean {
        if (rootWidth <= 0) return false
        if (bounds.width < rootWidth * 0.6f) return false
        return bounds.height in minRowHeight.toInt()..maxRowHeight.toInt()
    }

    private companion object {
        /** Spotify's "add to queue" green. */
        val ACCENT = Color(0xFF1DB954)
    }
}

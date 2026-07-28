/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.animation.ValueAnimator
import android.content.res.Resources
import android.view.animation.DecelerateInterpolator
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

/**
 * Modifier element the patch appends to every long clickable Compose row.
 *
 * Rows that are not list rows (buttons, grid cells, chips) are filtered out at runtime by
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
    private val touchSlop = 12f * density
    private val minRowHeight = 36f * density
    private val maxRowHeight = 132f * density
    private val iconSize = 22f * density

    private var rootWidth = 0
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var active = false
    private var offset = 0f
    private var settleAnimator: ValueAnimator? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        rootWidth = coordinates.findRootCoordinates().size.width
    }

    override fun onDetach() {
        settleAnimator?.cancel()
        settleAnimator = null
        offset = 0f
        active = false
        tracking = false
    }

    override fun onCancelPointerInput() {
        if (active) settle()
        tracking = false
        active = false
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
                if (pointerEvent.changes.size != 1 || !isRowLike(bounds)) {
                    tracking = false
                    return
                }
                settleAnimator?.cancel()
                downX = change.position.x
                downY = change.position.y
                offset = 0f
                active = false
                tracking = true
            }

            PointerEventType.Move -> {
                if (!tracking) return
                val dx = change.position.x - downX
                val dy = change.position.y - downY

                if (!active) {
                    // A vertical drag belongs to the list, a left drag to whatever is behind it.
                    if (abs(dy) > touchSlop || dx < -touchSlop) {
                        tracking = false
                        return
                    }
                    if (dx <= touchSlop || dx < abs(dy) * 1.5f) return
                    active = true
                }

                offset = min(max(dx - touchSlop, 0f), bounds.width * 0.5f)
                change.consume()
                invalidate()
            }

            PointerEventType.Release -> {
                if (active) {
                    change.consume()
                    val committed = offset >= commitThreshold(bounds)
                    active = false
                    tracking = false
                    if (committed) SwipeToQueue.triggerAddToQueue(onLongClick)
                    settle()
                } else {
                    tracking = false
                }
            }

            else -> Unit
        }
    }

    private fun isRowLike(bounds: IntSize): Boolean {
        if (rootWidth <= 0) return false
        if (bounds.width < rootWidth * 0.6f) return false
        return bounds.height in minRowHeight.toInt()..maxRowHeight.toInt()
    }

    private fun commitThreshold(bounds: IntSize) =
        min(bounds.width * 0.28f, 96f * density)

    private fun settle() {
        settleAnimator?.cancel()
        val from = offset
        if (from <= 0f) {
            offset = 0f
            invalidate()
            return
        }
        settleAnimator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun invalidate() {
        if (isAttached) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val current = offset
        if (current <= 0.5f) {
            drawContent()
            return
        }

        val height = size.height
        val progress = min(current / commitThreshold(IntSize(size.width.toInt(), height.toInt())), 1f)

        drawRect(
            color = ACCENT,
            topLeft = Offset.Zero,
            size = Size(current, height),
        )
        drawQueueIcon(current, height, progress)

        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(current, 0f)
        drawContent()
        canvas.restore()
    }

    /**
     * The queue glyph: three stacked lines with a plus sign, drawn with primitives so the patch
     * does not have to add a drawable to the app.
     */
    private fun ContentDrawScope.drawQueueIcon(revealed: Float, height: Float, progress: Float) {
        val scale = 0.6f + 0.4f * progress
        val size = iconSize * scale
        val centerX = min(revealed / 2f, revealed - size)
        if (centerX < size / 2f) return
        val centerY = height / 2f
        val stroke = max(1.5f * density, 2f * density * scale)
        val color = Color.White.copy(alpha = progress)

        val left = centerX - size / 2f
        val right = centerX + size / 2f
        val lineGap = size / 3.4f

        // Three list lines, the bottom one shortened to leave room for the plus.
        for (line in 0..2) {
            val y = centerY - lineGap + line * lineGap
            val end = if (line == 2) right - size * 0.42f else right
            drawLine(
                color = color,
                start = Offset(left, y),
                end = Offset(end, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        // Plus sign next to the bottom line.
        val plusX = right - size * 0.16f
        val plusY = centerY + lineGap
        val plusArm = size * 0.2f
        drawLine(
            color = color,
            start = Offset(plusX - plusArm, plusY),
            end = Offset(plusX + plusArm, plusY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(plusX, plusY - plusArm),
            end = Offset(plusX, plusY + plusArm),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }

    private companion object {
        /** Spotify's "add to queue" green. */
        val ACCENT = Color(0xFF1DB954)
    }
}

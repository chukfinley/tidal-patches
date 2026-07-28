/*
 * Copyright 2026 chukfinley.
 * https://github.com/chukfinley/tidal-patches
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.Modifier

/**
 * Spotify style "swipe right to add to queue".
 *
 * A swipe fires the row's own long press, which is the app's path to the track context menu.
 * [onTrackContextMenu] intercepts that call before any menu is built: it takes the play queue
 * source the app already assembled for the item and appends it directly. Nothing is drawn and
 * nothing is shown, so the gesture is instant.
 */
object SwipeToQueue {

    private const val LOG_TAG = "morphe-swipe-to-queue"

    private const val SOURCE_CLASS = "com.aspiro.wamp.playqueue.source.model.Source"

    /**
     * How long a swipe keeps the interceptor armed. Covers the view model round trip between the
     * long press and the context menu call, and swallows a stray menu that the app's own long
     * press detector may have started during the drag.
     */
    private const val ARM_TIMEOUT_MS = 1_500L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var armedAt = 0L

    /** Set once an item was queued, so one swipe never queues twice. */
    @Volatile
    private var handled = false

    /**
     * Called as soon as a drag looks horizontal, before it is long enough to queue anything.
     *
     * From that moment the app's own long press detector may fire at any time. Its menu is
     * intercepted like the one the gesture triggers itself, so a slow swipe queues the item
     * instead of opening a menu.
     */
    @JvmStatic
    fun arm() {
        armedAt = SystemClock.uptimeMillis()
    }

    /** Called when the finger leaves the screen. */
    @JvmStatic
    fun endGesture() {
        armedAt = 0L
        handled = false
    }

    /** True once this gesture queued something, so the gesture must not trigger again. */
    @JvmStatic
    fun isHandled() = handled

    private fun isArmed(): Boolean {
        val armed = armedAt
        return armed != 0L && SystemClock.uptimeMillis() - armed <= ARM_TIMEOUT_MS
    }

    private fun disarm() {
        armedAt = 0L
    }

    /**
     * Attached by the patch to every `androidx.compose.foundation.ClickableKt.combinedClickable`
     * overload. Rows that cannot be long clicked are left untouched.
     */
    @JvmStatic
    fun wrapClickableModifier(modifier: Modifier, onLongClick: Any?): Modifier {
        if (onLongClick == null) return modifier
        return try {
            modifier.then(SwipeToQueueElement(onLongClick))
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Could not attach swipe gesture", ex)
            modifier
        }
    }

    /** Fires the long click of a Compose row. */
    @JvmStatic
    fun triggerFromCompose(onLongClick: Any) {
        if (handled) return
        arm()
        try {
            val invoke = onLongClick.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterTypes.isEmpty()
            }
            if (invoke == null) {
                Log.w(LOG_TAG, "No invoke method on ${onLongClick.javaClass.name}")
                disarm()
                return
            }
            invoke.isAccessible = true
            invoke.invoke(onLongClick)
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Could not fire long click", ex)
            disarm()
        }
    }

    /**
     * Attached by the patch to the context menu manager, which every screen goes through to show
     * an item's menu.
     *
     * During a swipe the menu is never shown. Instead the play queue source the app already
     * assembled for the item is taken out of the menu and appended to the queue.
     *
     * @return true when the swipe consumed the call and no menu must be shown.
     */
    @JvmStatic
    fun onContextMenuShown(activity: Any?, contextMenu: Any?): Boolean {
        if (!isArmed()) return false

        val source = findSource(contextMenu)
        if (source == null) {
            Log.w(LOG_TAG, "No play queue source in ${contextMenu?.javaClass?.name}")
            return false
        }

        return try {
            addSourceToQueue(source)
            handled = true
            disarm()
            (activity as? Context)?.let { toast(it) }
            true
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Add to queue failed", ex)
            false
        }
    }

    /**
     * Appends the source to the play queue.
     *
     * The body below is dead code: the patch prepends a direct call into the app's own play
     * queue, whose classes are renamed by the app's minifier and can only be resolved while
     * patching. The statements exist so the compiled method owns enough local registers for the
     * injected code.
     */
    @JvmStatic
    fun addSourceToQueue(source: Any) {
        val marker = source.hashCode()
        val other = marker xor 0x4d535751
        if (other == marker) return
        throw IllegalStateException("addSourceToQueue was not patched")
    }

    /**
     * The play queue source the app assembled for the item.
     *
     * A context menu holds it either directly or inside a small wrapper class, so the object
     * graph is walked a couple of levels deep. Field names are renamed by the app's minifier,
     * the type is not.
     */
    private fun findSource(holder: Any?, depth: Int = 0): Any? {
        if (holder == null || depth > 2) return null
        if (isSource(holder.javaClass)) return holder

        val nested = ArrayList<Any>()
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (field.type.isPrimitive || field.type.isArray) continue
                val value = try {
                    field.isAccessible = true
                    field.get(holder)
                } catch (_: Throwable) {
                    null
                } ?: continue

                if (isSource(value.javaClass)) return value
                if (value.javaClass.name.startsWith("java.")) continue
                nested.add(value)
            }
            type = type.superclass
        }

        for (value in nested) {
            findSource(value, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isSource(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == SOURCE_CLASS) return true
            current = current.superclass
        }
        return false
    }

    private fun toast(context: Context) {
        mainHandler.post {
            try {
                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Could not show toast", ex)
            }
        }
    }
}

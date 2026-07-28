/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package dev.chuk.extension.tidal.swipetoqueue

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.Modifier
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Spotify style "swipe right to add to queue".
 *
 * The gesture itself is implemented by [SwipeToQueueElement], which the patch attaches to every
 * `combinedClickable` modifier the app creates (Compose screens) and to legacy RecyclerView rows
 * (see [LegacyRowSwipe]).
 *
 * Instead of reimplementing "add to queue" - which would require reaching into the obfuscated
 * play queue internals - the gesture re-uses the app's own context menu:
 *
 *  1. the gesture fires the row's long click, which is what normally opens the context menu,
 *  2. [onContextMenuShown] intercepts the menu before it is displayed,
 *  3. the "Add to queue" entry of that menu is invoked directly and the menu is discarded.
 *
 * That way the correct item, source metadata, analytics and toast are all handled by the app.
 */
object SwipeToQueue {

    private const val LOG_TAG = "morphe-swipe-to-queue"

    /**
     * How long the interceptor stays armed after a swipe. Long enough to survive a view model
     * round trip, short enough that a genuine long press is never swallowed.
     */
    private const val ARM_TIMEOUT_MS = 2_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "morphe-swipe-to-queue").apply { isDaemon = true }
    }

    @Volatile
    private var armedAt = 0L

    /** Called by the gesture right before it fires the row's long click. */
    @JvmStatic
    fun arm() {
        armedAt = SystemClock.uptimeMillis()
    }

    private fun consumeArmed(): Boolean {
        val armed = armedAt
        if (armed == 0L || SystemClock.uptimeMillis() - armed > ARM_TIMEOUT_MS) return false
        armedAt = 0L
        return true
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

    /**
     * Attached by the patch to the app's context menu manager.
     *
     * @return true when the menu was consumed by a swipe and must not be shown.
     */
    @JvmStatic
    fun onContextMenuShown(activity: Any?, contextMenu: Any?): Boolean {
        if (activity !is Activity || contextMenu == null) return false
        if (!consumeArmed()) return false

        background.execute {
            try {
                val item = findAddToQueueItem(activity, contextMenu)
                if (item == null) {
                    Log.w(LOG_TAG, "Context menu has no add to queue entry")
                    return@execute
                }
                val click = findClickMethod(item.javaClass)
                if (click == null) {
                    Log.w(LOG_TAG, "No click method on ${item.javaClass.name}")
                    return@execute
                }
                mainHandler.post {
                    try {
                        click.invoke(item, activity)
                    } catch (ex: Throwable) {
                        Log.e(LOG_TAG, "Add to queue failed", ex)
                    }
                }
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "Could not resolve add to queue entry", ex)
            }
        }

        return true
    }

    /**
     * The menu items of a context menu. Obfuscation renames the method, but there is exactly one
     * parameterless method returning a [List] (the parameterless [ArrayList] variant is the
     * pre-filtered one and is only used as a fallback).
     */
    private fun findAddToQueueItem(activity: Activity, contextMenu: Any): Any? {
        val resources = activity.resources
        val packageName = activity.packageName
        val addToQueueLabel = resources.getIdentifier("add_to_queue", "string", packageName)
        val addToQueueIcon = resources.getIdentifier("ic_add_to_queue_last", "drawable", packageName)
        if (addToQueueLabel == 0 && addToQueueIcon == 0) return null

        val items = invokeItems(contextMenu) ?: return null
        return items.firstOrNull { item ->
            item != null && matchesAddToQueue(item, addToQueueLabel, addToQueueIcon)
        }
    }

    private fun invokeItems(contextMenu: Any): List<*>? {
        var fallback: Method? = null
        for (method in contextMenu.javaClass.methods) {
            if (method.parameterTypes.isNotEmpty()) continue
            if (!List::class.java.isAssignableFrom(method.returnType)) continue
            if (method.returnType == List::class.java) {
                method.isAccessible = true
                return method.invoke(contextMenu) as? List<*>
            }
            if (fallback == null) fallback = method
        }
        fallback?.isAccessible = true
        return fallback?.invoke(contextMenu) as? List<*>
    }

    /**
     * The tracking id ("add_to_queue") that identifies the entry in the source is dropped by the
     * app's minifier, so the entry is matched by the resource ids it was built with instead: the
     * title string and, as a fallback, the icon.
     */
    private fun matchesAddToQueue(item: Any, label: Int, icon: Int): Boolean {
        val values = HashSet<Int>()
        collectIntFields(item, values, 0)
        return (label != 0 && values.contains(label)) || (icon != 0 && values.contains(icon))
    }

    private fun collectIntFields(instance: Any, into: MutableSet<Int>, depth: Int) {
        if (depth > 2) return
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    when (field.type) {
                        Int::class.javaPrimitiveType -> into.add(field.getInt(instance))
                        // The title of an entry is a sealed class wrapping the string resource id.
                        else -> if (!field.type.isPrimitive && !field.type.isArray) {
                            val nested = field.get(instance)
                            if (nested != null && nested.javaClass.name.startsWith(
                                    type.name.substringBefore('$')
                                )
                            ) {
                                collectIntFields(nested, into, depth + 1)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore inaccessible fields.
                }
            }
            type = type.superclass
        }
    }

    /** The context menu entry click handler: the only method taking a single activity parameter. */
    private fun findClickMethod(type: Class<*>): Method? {
        for (method in type.methods) {
            val parameters = method.parameterTypes
            if (parameters.size != 1) continue
            if (!Activity::class.java.isAssignableFrom(parameters[0])) continue
            method.isAccessible = true
            return method
        }
        return null
    }

    /** Fires the long click of a row, arming the interceptor first. */
    @JvmStatic
    fun triggerAddToQueue(onLongClick: Any) {
        arm()
        try {
            val invoke = onLongClick.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterTypes.isEmpty()
            }
            if (invoke == null) {
                Log.w(LOG_TAG, "No invoke method on ${onLongClick.javaClass.name}")
                return
            }
            invoke.isAccessible = true
            invoke.invoke(onLongClick)
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Could not fire long click", ex)
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package dev.chuk.patches.tidal.interaction.swipetoqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import dev.chuk.patches.tidal.shared.Constants.COMPATIBILITY_TIDAL
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS =
    "Ldev/chuk/extension/tidal/swipetoqueue/SwipeToQueue;"

private const val LEGACY_EXTENSION_CLASS =
    "Ldev/chuk/extension/tidal/swipetoqueue/LegacyRowSwipe;"

private const val MODIFIER_TYPE = "Landroidx/compose/ui/Modifier;"
private const val CLICKABLE_CLASS = "Landroidx/compose/foundation/ClickableKt;"
private const val BOTTOM_SHEET_DIALOG_CLASS =
    "Lcom/google/android/material/bottomsheet/BottomSheetDialog;"
private const val VIEW_HOLDER_TYPE = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"

/**
 * Register of the parameter at [index]. Wide parameters occupy two registers.
 * Only valid for static methods, where the first parameter is `p0`.
 */
private fun parameterRegisterOfStatic(method: Method, index: Int): Int {
    var register = 0
    method.parameterTypes.forEachIndexed { current, type ->
        if (current == index) return register
        register += if (type == "J" || type == "D") 2 else 1
    }
    throw PatchException("Parameter $index does not exist in ${method.name}")
}

@Suppress("unused")
val swipeToQueuePatch = bytecodePatch(
    name = "Swipe to add to queue",
    description = "Adds a Spotify style swipe right gesture that adds the swiped item to the " +
        "play queue, on every screen that lists tracks, albums, playlists or mixes.",
) {
    compatibleWith(COMPATIBILITY_TIDAL)

    extendWith("extensions/tidal.mpe")

    execute {

        // region Compose rows.
        //
        // Every long clickable Compose component goes through one of the combinedClickable
        // overloads. The swipe modifier is appended there and filters non row layouts itself,
        // which covers search, home, album, playlist, artist and mix screens with a single hook.

        val clickableClass = classDefByOrNull(CLICKABLE_CLASS)
            ?: throw PatchException("Could not find $CLICKABLE_CLASS")

        var composeHooks = 0
        mutableClassDefBy(clickableClass).methods.filter { method ->
            method.name.startsWith("combinedClickable") &&
                !method.name.contains("\$default") &&
                method.parameterTypes.firstOrNull() == MODIFIER_TYPE &&
                method.returnType == MODIFIER_TYPE
        }.forEach { method ->
            // In every overload the parameter order is
            // (..., onClickLabel: String, role: Role, onLongClickLabel: String, onLongClick, ...),
            // so the long click callback always follows the last string parameter.
            val lastStringParameter = method.parameterTypes.indexOfLast { it == "Ljava/lang/String;" }
            if (lastStringParameter == -1) return@forEach
            val onLongClickParameter = lastStringParameter + 1
            if (onLongClickParameter >= method.parameterTypes.size) return@forEach

            val implementation = method.implementation
                ?: throw PatchException("${method.name} has no implementation")
            val parameterRegisters = method.parameterTypes.sumOf { type ->
                if (type == "J" || type == "D") 2 else 1
            }
            val firstParameterRegister = implementation.registerCount - parameterRegisters
            val modifierRegister = firstParameterRegister + parameterRegisterOfStatic(method, 0)
            val onLongClickRegister =
                firstParameterRegister + parameterRegisterOfStatic(method, onLongClickParameter)

            // The overloads with long signatures keep their parameters above v15, which
            // invoke-static cannot address, so those are routed through two local registers.
            val instructions = if (modifierRegister < 16 && onLongClickRegister < 16) {
                """
                    invoke-static { v$modifierRegister, v$onLongClickRegister }, $EXTENSION_CLASS->wrapClickableModifier(${MODIFIER_TYPE}Ljava/lang/Object;)$MODIFIER_TYPE
                    move-result-object v$modifierRegister
                """
            } else {
                if (firstParameterRegister < 2) {
                    throw PatchException("Not enough local registers in ${method.name}")
                }
                """
                    move-object/from16 v0, v$modifierRegister
                    move-object/from16 v1, v$onLongClickRegister
                    invoke-static { v0, v1 }, $EXTENSION_CLASS->wrapClickableModifier(${MODIFIER_TYPE}Ljava/lang/Object;)$MODIFIER_TYPE
                    move-result-object v0
                    move-object/16 v$modifierRegister, v0
                """
            }

            method.addInstructions(0, instructions)
            composeHooks++
        }

        if (composeHooks == 0) {
            throw PatchException("Could not hook any combinedClickable overload")
        }

        // endregion

        // region Context menu interception.
        //
        // The gesture fires the row's own long click and the context menu it opens is consumed
        // here, so the app resolves the item and performs the actual "add to queue".

        val bottomSheetSubclasses = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.superclass == BOTTOM_SHEET_DIALOG_CLASS) {
                bottomSheetSubclasses.add(classDef.type)
            }
        }

        var contextMenuMethod: Pair<ClassDef, Method>? = null
        classDefForEach { classDef ->
            if (contextMenuMethod != null) return@classDefForEach
            classDef.methods.forEach forEachMethod@{ method ->
                if (method.returnType != "V") return@forEachMethod
                if (method.parameterTypes.size != 2) return@forEachMethod
                if (method.parameterTypes[0] != "Landroid/app/Activity;") return@forEachMethod

                val constructsDialog = method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.NEW_INSTANCE &&
                        bottomSheetSubclasses.contains(
                            ((instruction as ReferenceInstruction).reference as TypeReference).type
                        )
                } ?: false

                if (constructsDialog) contextMenuMethod = classDef to method
            }
        }

        val (contextMenuClass, showMethod) = contextMenuMethod
            ?: throw PatchException("Could not find the context menu manager")

        mutableClassDefBy(contextMenuClass).methods.first {
            it.name == showMethod.name && it.parameterTypes == showMethod.parameterTypes
        }.apply {
            val registerCount = implementation?.registerCount ?: 0
            val activityRegister = registerCount - 2
            val menuRegister = registerCount - 1
            if (registerCount - 3 < 2) {
                throw PatchException("No free register in ${contextMenuClass.type}->$name")
            }

            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, v$activityRegister
                    move-object/from16 v1, v$menuRegister
                    invoke-static { v0, v1 }, $EXTENSION_CLASS->onContextMenuShown(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :morphe_show_menu
                    return-void
                """,
                ExternalLabel("morphe_show_menu", getInstruction(0))
            )
        }

        // endregion

        // region Legacy RecyclerView rows.
        //
        // Screens that were not migrated to Compose bind their rows through a single adapter
        // delegate manager, which is where the touch handling is attached.

        val legacyBindMethod = run {
            var found: Pair<ClassDef, Method>? = null
            classDefForEach { classDef ->
                if (found != null) return@classDefForEach
                val bind = classDef.methods.firstOrNull { method ->
                    method.returnType == "V" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == "Ljava/lang/Object;" &&
                        method.parameterTypes[1] == VIEW_HOLDER_TYPE
                }
                val createsViewHolder = classDef.methods.any { method ->
                    method.returnType == VIEW_HOLDER_TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == "Landroid/view/ViewGroup;" &&
                        method.parameterTypes[1] == "I"
                }
                if (bind != null && createsViewHolder) found = classDef to bind
            }
            found
        }

        // Legacy rows only exist on a few remaining screens, so a missing delegate manager is
        // not an error.
        legacyBindMethod?.let { (classDef, method) ->
            mutableClassDefBy(classDef).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes
            }.apply {
                val registerCount = implementation?.registerCount ?: 0
                val viewHolderRegister = registerCount - 1
                addInstructions(
                    0,
                    if (viewHolderRegister < 16) {
                        "invoke-static { v$viewHolderRegister }, $LEGACY_EXTENSION_CLASS->attach($VIEW_HOLDER_TYPE)V"
                    } else {
                        """
                            move-object/from16 v0, v$viewHolderRegister
                            invoke-static { v0 }, $LEGACY_EXTENSION_CLASS->attach($VIEW_HOLDER_TYPE)V
                        """
                    }
                )
            }
        }

        // endregion
    }
}

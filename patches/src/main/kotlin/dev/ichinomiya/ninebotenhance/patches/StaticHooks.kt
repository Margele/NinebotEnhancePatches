package dev.ichinomiya.ninebotenhance.patches

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod.Companion.toMutable
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.util.MethodUtil
import dev.ichinomiya.ninebotenhance.hook.HookPolicy

internal const val EXTENSION = "Ldev/ichinomiya/ninebotenhance/"
private const val HOOKS = "${EXTENSION}embedded/StaticHooks;"
private const val TABLE = "${EXTENSION}embedded/HookTable;"
private const val TWIN = "${EXTENSION}embedded/Twin;"
private const val OBJECT = "Ljava/lang/Object;"

/** Must equal StaticHooks.CAPACITY in the extension: a trampoline indexes the slot array without a bounds check of its own. */
private const val CAPACITY = 1 shl 14

/** Locals of a method trampoline: slot, argument array, boxed value or null receiver, array index. */
private const val LOCALS = 4

private val BOXES = mapOf(
    'Z' to ("Ljava/lang/Boolean;" to "booleanValue"), 'B' to ("Ljava/lang/Byte;" to "byteValue"), 'S' to ("Ljava/lang/Short;" to "shortValue"),
    'C' to ("Ljava/lang/Character;" to "charValue"), 'I' to ("Ljava/lang/Integer;" to "intValue"), 'J' to ("Ljava/lang/Long;" to "longValue"),
    'F' to ("Ljava/lang/Float;" to "floatValue"), 'D' to ("Ljava/lang/Double;" to "doubleValue"),
)

private fun wide(type: String) = type == "J" || type == "D"
private fun reference(type: String) = type[0] == 'L' || type[0] == '['
private fun dotted(type: String) = type.substring(1, type.length - 1).replace('/', '.')
private fun descriptor(method: Method) = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

/**
 * What the module may hook inside Ninebot, decided the way the module itself decides at run time. Classes HookPolicy names are
 * wrapped whole, because the module picks their methods by name and signature patterns. Two more groups are reached through an
 * object rather than a class name (EncodingHooks): the MediaCodec callbacks Ninebot registers, and the bitrate and frame rate
 * setters NbFFmpegFrameRecorder inherits from javacv.
 */
private class Selection(val classDef: ClassDef, val constructors: Boolean, val accepts: (Method) -> Boolean)

private const val RECORDER = "Lcn/ninebot/capture/mpeg2/NbFFmpegFrameRecorder;"
private val CALLBACKS = setOf(
    "onOutputFormatChanged(Landroid/media/MediaCodec;Landroid/media/MediaFormat;)V",
    "onOutputBufferAvailable(Landroid/media/MediaCodec;ILandroid/media/MediaCodec\$BufferInfo;)V",
)
private val RECORDER_SETTERS = setOf("setVideoBitrate(I)V", "setFrameRate(D)V")
private fun signature(method: Method) = "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private fun BytecodePatchContext.selections(): List<Selection> {
    val selections = LinkedHashMap<String, Selection>()
    for (classDef in classDefs.toList()) {
        val type = classDef.type
        if (type.startsWith(EXTENSION) || AccessFlags.INTERFACE.isSet(classDef.accessFlags)) continue
        val name = dotted(type)
        if (HookPolicy.interestingClass(name)) selections[type] = Selection(classDef, HookPolicy.captureConstructors(name)) { true }
        else if (classDef.methods.any { signature(it) in CALLBACKS && it.implementation != null })
            selections[type] = Selection(classDef, false) { signature(it) in CALLBACKS }
    }
    var parent = classDefs[RECORDER]?.superclass
    while (parent != null) {
        val classDef = classDefs[parent] ?: break
        if (classDef.type !in selections) selections[classDef.type] = Selection(classDef, false) { signature(it) in RECORDER_SETTERS && !AccessFlags.STATIC.isSet(it.accessFlags) }
        parent = classDef.superclass
    }
    return selections.values.toList()
}

/**
 * Wraps every selected method and constructor and replaces the extension's empty HookTable with the list of what was wrapped.
 *
 * @return how many methods and constructors were wrapped.
 */
internal fun BytecodePatchContext.wrapHookTargets(): Int {
    val table = ArrayList<String>()
    for (selection in selections()) {
        val owner = classDefs.getOrReplaceMutable(selection.classDef)
        for (method in owner.methods.toList()) {
            if (method.implementation == null || method.name == "<clinit>" || !selection.accepts(method)) continue
            if (method.name == "<init>" && !selection.constructors) continue
            if (table.size >= CAPACITY) throw PatchException("more than $CAPACITY hook targets; raise StaticHooks.CAPACITY")
            // The line number is the slot, so the descriptor is recorded before the method changes.
            table += descriptor(method)
            val twin = if (method.name == "<init>") wrapConstructor(method, table.size - 1) else wrapMethod(method, table.size - 1)
            owner.add(twin)
        }
    }
    val tableClass = ImmutableClassDef(
        TABLE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null,
        listOf(ImmutableField(TABLE, "TABLE", "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            ImmutableStringEncodedValue(table.joinToString("\n")), null, null)),
        null,
    )
    classDefs[TABLE]?.let { classDefs.remove(it) } ?: throw PatchException("the extension carries no HookTable")
    classDefs.add(tableClass)
    return table.size
}

/** The class keeps three views of its methods; a new method has to reach the one the dex writer reads for its kind. */
private fun MutableClassDef.add(method: MutableMethod) {
    methods.add(method)
    if (MethodUtil.isDirect(method)) directMethods.add(method) else virtualMethods.add(method)
}

/** Register a parameter lands in, counted from p0, for each parameter; an instance method's receiver is p0. */
private fun parameterRegisters(method: Method): List<Int> {
    var next = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    return method.parameterTypes.map { type -> next.also { next += if (wide(type.toString())) 2 else 1 } }
}
private fun inputs(method: Method) = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) + method.parameterTypes.sumOf { if (wide(it.toString())) 2 else 1 as Int }

/** Smali that stores every argument, boxed where it is a primitive, into a new Object[] in v1; v2 and v3 are scratch. */
private fun boxArguments(method: Method): String {
    val types = method.parameterTypes.map { it.toString() }
    val registers = parameterRegisters(method)
    val lines = StringBuilder("const/16 v1, ${types.size}\nnew-array v1, v1, [$OBJECT\n")
    types.forEachIndexed { index, type ->
        val register = registers[index]
        lines.append("const/16 v3, $index\n")
        if (reference(type)) lines.append("aput-object p$register, v1, v3\n")
        else {
            val box = BOXES.getValue(type[0]).first
            lines.append("invoke-static/range {p$register .. p${register + if (wide(type)) 1 else 0}}, $box->valueOf($type)$box\n")
            lines.append("move-result-object v2\naput-object v2, v1, v3\n")
        }
    }
    return lines.toString()
}

/**
 * The original body moves to a private twin named name$$ne; the method itself becomes:
 * <pre>
 *   slot = StaticHooks.hooks[id]
 *   if (slot == null) return name$$ne(args)
 *   return (unboxed) StaticHooks.dispatch(slot, this, new Object[]{args})
 * </pre>
 * The twin is private so a subclass override can never stand in for it when the dispatcher calls it.
 */
private fun wrapMethod(method: MutableMethod, id: Int): MutableMethod {
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val twinFlags = (method.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.PROTECTED.value).inv()) or
        AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value
    val twinName = method.name + HookPolicy.TWIN_SUFFIX
    val parameters = method.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) }
    val twin = ImmutableMethod(method.definingClass, twinName, parameters, method.returnType, twinFlags, emptySet<Annotation>(),
        method.hiddenApiRestrictions, method.implementation).toMutable()

    val inputs = inputs(method)
    val proto = "(${method.parameterTypes.joinToString("")})${method.returnType}"
    val returns = method.returnType
    val call = when {
        inputs == 0 -> "invoke-static {}, ${method.definingClass}->$twinName$proto"
        else -> "invoke-${if (static) "static" else "direct"}/range {p0 .. p${inputs - 1}}, ${method.definingClass}->$twinName$proto"
    }
    val direct = when {
        returns == "V" -> "return-void"
        wide(returns) -> "move-result-wide v0\nreturn-wide v0"
        reference(returns) -> "move-result-object v0\nreturn-object v0"
        else -> "move-result v0\nreturn v0"
    }
    val hooked = when {
        returns == "V" -> "return-void"
        reference(returns) -> (if (returns == OBJECT) "" else "check-cast v0, $returns\n") + "return-object v0"
        else -> {
            val (box, unbox) = BOXES.getValue(returns[0])
            "check-cast v0, $box\ninvoke-virtual {v0}, $box->$unbox()$returns\n" +
                if (wide(returns)) "move-result-wide v0\nreturn-wide v0" else "move-result v0\nreturn v0"
        }
    }
    val self = if (static) "const/4 v2, 0x0\n" to "v2" else "" to "p0"
    method.setImplementation(MutableMethodImplementation(LOCALS + inputs))
    method.addInstructionsWithLabels(
        0,
        """
        sget-object v0, $HOOKS->hooks:[$OBJECT
        const v1, $id
        aget-object v0, v0, v1
        if-nez v0, :hooked
        $call
        $direct
        :hooked
        ${boxArguments(method)}
        ${self.first}invoke-static {v0, ${self.second}, v1}, $HOOKS->dispatch($OBJECT$OBJECT[$OBJECT)$OBJECT
        move-result-object v0
        $hooked
        """.trimIndent().lines().joinToString("\n") { it.trim() },
    )
    return twin
}

/**
 * A constructor's body cannot move to a method, so it moves to a second constructor with a trailing Twin parameter, and the
 * constructor itself becomes:
 * <pre>
 *   this(args, (Twin) null)
 *   slot = StaticHooks.hooks[id]
 *   if (slot != null) StaticHooks.constructed(slot, this, new Object[]{args})
 * </pre>
 * Hookers therefore run after construction: they see the arguments and the finished object, and cannot change either.
 */
private fun wrapConstructor(method: MutableMethod, id: Int): MutableMethod {
    val original = method.implementation!!
    val twinFlags = (method.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.PROTECTED.value).inv()) or
        AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value
    val parameters = method.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) } + ImmutableMethodParameter(TWIN, null, null)
    // One more input register on top: every register the body already uses keeps its number.
    val twinBody = ImmutableMethodImplementation(original.registerCount + 1, original.instructions, original.tryBlocks, original.debugItems)
    val twin = ImmutableMethod(method.definingClass, "<init>", parameters, "V", twinFlags, emptySet(), method.hiddenApiRestrictions, twinBody).toMutable()

    val inputs = inputs(method)
    val types = method.parameterTypes.map { it.toString() }
    val registers = parameterRegisters(method)
    // The delegating call needs this, the arguments and the null marker in consecutive registers, and inputs sit at the top.
    val locals = maxOf(inputs + 1, LOCALS)
    val moves = StringBuilder("move-object/from16 v0, p0\n")
    types.forEachIndexed { index, type ->
        val move = if (reference(type)) "move-object/from16" else if (wide(type)) "move-wide/from16" else "move/from16"
        moves.append("$move v${registers[index]}, p${registers[index]}\n")
    }
    method.setImplementation(MutableMethodImplementation(locals + inputs))
    method.addInstructionsWithLabels(
        0,
        """
        ${moves}const/16 v$inputs, 0x0
        invoke-direct/range {v0 .. v$inputs}, ${method.definingClass}-><init>(${types.joinToString("")}$TWIN)V
        sget-object v0, $HOOKS->hooks:[$OBJECT
        const v1, $id
        aget-object v0, v0, v1
        if-eqz v0, :done
        ${boxArguments(method)}
        move-object/from16 v2, p0
        invoke-static {v0, v2, v1}, $HOOKS->constructed($OBJECT$OBJECT[$OBJECT)V
        :done
        return-void
        """.trimIndent().lines().joinToString("\n") { it.trim() },
    )
    return twin
}

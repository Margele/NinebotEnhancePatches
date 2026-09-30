package dev.ichinomiya.ninebotenhance.patches

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val REDIRECTS = "${EXTENSION}embedded/Redirects;"
private const val REDIRECT = "${EXTENSION}embedded/Redirect;"

/** One method of the extension's Redirects class, read from its Redirect annotation. */
private class Stub(val static: Boolean, val scope: List<String>, val reference: ImmutableMethodReference)

private class Site(val classDef: ClassDef, val method: Method, val index: Int, val stub: Stub)

private fun key(reference: MethodReference) =
    "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

/** The platform method each stub replaces, keyed by its descriptor. Annotation defaults are not stored with the use; they are repeated here. */
private fun BytecodePatchContext.stubs(): Map<String, Stub> {
    val redirects = classDefs[REDIRECTS] ?: throw PatchException("the extension carries no Redirects")
    val stubs = HashMap<String, Stub>()
    for (method in redirects.methods) {
        val annotation = method.annotations.firstOrNull { it.type == REDIRECT } ?: continue
        val elements = annotation.elements.associate { it.name to it.value }
        val owner = (elements["owner"] as StringEncodedValue).value
        val static = (elements["isStatic"] as? BooleanEncodedValue)?.value ?: false
        val scope = (elements["scope"] as? ArrayEncodedValue)?.value?.map { (it as StringEncodedValue).value } ?: listOf("Lcn/ninebot/")
        val parameters = method.parameterTypes.map { it.toString() }
        if (!static && parameters.firstOrNull() != owner) throw PatchException("Redirects.${method.name} does not take its receiver first")
        val target = "$owner->${method.name}(${(if (static) parameters else parameters.drop(1)).joinToString("")})${method.returnType}"
        stubs[target] = Stub(static, scope, ImmutableMethodReference(REDIRECTS, method.name, parameters, method.returnType))
    }
    if (stubs.isEmpty()) throw PatchException("Redirects declares no redirect")
    return stubs
}

/**
 * Rewrites Ninebot's calls of the platform methods the module hooks into calls of the extension's Redirects. An instance call
 * becomes a static call with the same registers: the receiver is the stub's first parameter.
 *
 * @return how many call sites were rewritten, per stub.
 */
internal fun BytecodePatchContext.redirectPlatformCalls(): Map<String, Int> {
    val stubs = stubs()
    val scopes = stubs.values.flatMap { it.scope }.toSet()
    val sites = ArrayList<Site>()
    for (classDef in classDefs.toList()) {
        val type = classDef.type
        if (type.startsWith(EXTENSION) || scopes.none { type.startsWith(it) }) continue
        for (method in classDef.methods) {
            val instructions = method.implementation?.instructions ?: continue
            instructions.forEachIndexed { index, instruction ->
                val static = when (instruction.opcode) {
                    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> false
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> true
                    else -> return@forEachIndexed
                }
                val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@forEachIndexed
                val stub = stubs[key(reference)] ?: return@forEachIndexed
                if (stub.static == static && stub.scope.any { type.startsWith(it) }) sites += Site(classDef, method, index, stub)
            }
        }
    }
    val counts = sortedMapOf<String, Int>()
    for ((classDef, inClass) in sites.groupBy { it.classDef }) {
        val owner = classDefs.getOrReplaceMutable(classDef)
        for ((method, inMethod) in inClass.groupBy { it.method }) {
            val implementation = owner.methods.first { MethodUtil.methodSignaturesMatch(it, method) }.implementation!!
            for (site in inMethod) {
                val replacement = when (val old = implementation.instructions[site.index]) {
                    is Instruction35c -> BuilderInstruction35c(Opcode.INVOKE_STATIC, old.registerCount, old.registerC, old.registerD, old.registerE,
                        old.registerF, old.registerG, site.stub.reference)
                    is Instruction3rc -> BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, old.startRegister, old.registerCount, site.stub.reference)
                    else -> throw PatchException("unexpected invoke format in ${classDef.type}->${method.name}")
                }
                // Same size as the instruction it replaces, so no offset in the method moves.
                implementation.replaceInstruction(site.index, replacement)
                counts.merge(site.stub.reference.name, 1, Int::plus)
            }
        }
    }
    return counts
}

package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.indexer.*

fun tryRenderStructOrUnion(def: StructDef): String? = when (def.kind) {
    StructDef.Kind.STRUCT -> tryRenderStruct(def)
    StructDef.Kind.UNION -> tryRenderUnion(def)
}

private fun tryRenderStruct(def: StructDef): String? {
    // The only case when offset starts from non-zero is a inner anonymous struct or union
    val baseOffset = def.members.filterIsInstance<Field>().firstOrNull()?.offsetBytes ?: 0L
    var offset = 0L

    // Members of anonymous struct/union are the fields of enclosing named aggregate and has the corresponding offset.
    // However for the purpose of alignment heuristic we use "immediate" offset, i.e. relative to the immediate parent.
    // Consider for ex. a packed struct containing not packed anonymous inner: their fields are not aligned relative to the root.
    val isPackedStruct = def.fields.any { it.offsetBytes - baseOffset % it.typeAlign != 0L}


    val maxAlign = def.members.filterIsInstance<Field>().maxOfOrNull { it.typeAlign }
    val forceAlign = maxAlign?.let { def.align > maxAlign } ?: false

    return buildString {
        append("struct")
        if (isPackedStruct) append(" __attribute__((packed))")
        if (forceAlign) append(" __attribute__((aligned(${def.align})))")
        append(" { ")

        def.members.forEach { it ->
            val name = it.name
            val decl = when (it) {
                is Field -> {
                    val defaultAlignment = if (isPackedStruct) 1L else it.typeAlign

                    val alignment = guessAlignment(offset, it.offsetBytes - baseOffset, defaultAlignment) ?: return null
                    offset = it.offsetBytes - baseOffset + it.typeSize

                    tryRenderVar(it.type, name)
                            ?.plus(if (alignment == defaultAlignment) "" else " __attribute__((aligned($alignment)))")
                }

                is BitField, // TODO: tryRenderVar(it.type, name)?.plus(" : ${it.size}")
                is IncompleteField -> null // e.g. flexible array member.
                is AnonymousInnerRecord -> {
                    offset = it.offsetBytes - baseOffset + it.typeSize
                    tryRenderStructOrUnion(it.def)
                }
            } ?: return null
            append("$decl; ")
        }
        append("}")
    }
}

private fun guessAlignment(offset: Long, paddedOffset: Long, defaultAlignment: Long): Long? =
        longArrayOf(defaultAlignment, 1L, 2L, 4L, 8L, 16L, 32L).firstOrNull {
            alignUp(offset, it) == paddedOffset
        }

private fun alignUp(x: Long, alignment: Long): Long = (x + alignment - 1) and ((alignment - 1).inv())

private fun tryRenderUnion(def: StructDef): String? =
        buildString {
            append("union { ")
            def.members.forEach { it ->
                val name = it.name
                val decl = when (it) {
                    is Field -> tryRenderVar(it.type, name)
                    is BitField, is IncompleteField -> null
                    is AnonymousInnerRecord -> tryRenderStructOrUnion(it.def)
                } ?: return null
                append("$decl; ")
            }
            append("}")
        }

private fun tryRenderVar(type: Type, name: String): String? = when (type) {
    CharType, is BoolType -> "char $name"
    is IntegerType -> "${type.spelling} $name"
    is FloatingType -> "${type.spelling} $name"
    is VectorType -> "${type.spelling} $name"
    is RecordType -> "${tryRenderStructOrUnion(type.decl.def!!)} $name"
    is EnumType -> tryRenderVar(type.def.baseType, name)
    is PointerType -> "void* $name"
    is ConstArrayType -> tryRenderVar(type.elemType, "$name[${type.length}]")
    is IncompleteArrayType -> tryRenderVar(type.elemType, "$name[]")
    is Typedef -> tryRenderVar(type.def.aliased, name)
    is ObjCPointer -> "void* $name"
    else -> null
}

private val Field.offsetBytes: Long get() {
    require(this.offset % 8 == 0L)
    return this.offset / 8
}

private val AnonymousInnerRecord.offsetBytes: Long get() {
    require(this.offset % 8 == 0L)
    return this.offset / 8
}

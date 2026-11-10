package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type

/**
 * What Wire's naming needs to know beyond the message itself.
 *
 * @property protoPackage the proto `package` of the file declaring the message.
 * @property knownTypes fully-qualified names (no leading dot) of every message
 *   and enum in the request, for Wire's eponymous-type rule.
 * @property boxOneOfsMinSize Wire's `boxOneOfsMinSize`; see [WireOptions].
 */
data class WireSchema(
    val protoPackage: String,
    val knownTypes: Set<String>,
    val boxOneOfsMinSize: Int = WireOptions.DEFAULT_BOX_ONEOFS_MIN_SIZE,
)

/**
 * A oneof Wire generates as one `OneOf<Key, Value>?` property.
 *
 * @property property the message property holding the box.
 * @property keys oneof member field name to the companion `OneOf.Key` constant
 *   for that member.
 */
data class WireBox(val oneofName: String, val property: String, val keys: Map<String, String>)

/**
 * How Wire lays a message out as Kotlin properties.
 *
 * @property properties field name to property name, for every field that is
 *   its own property (all but members of a [WireBox]).
 * @property boxes oneof index to its box, for the oneofs Wire boxes.
 */
data class WireLayout(val properties: Map<String, String>, val boxes: Map<Int, WireBox>)

/**
 * Property names Wire 7's Kotlin generator gives a message's fields, with its
 * default options (`escapeKotlinKeywords = false`).
 *
 * Mirrors `KotlinGenerator.nameAllocator`: a kotlinpoet `NameAllocator` with
 * keywords preallocated, then Wire's own member names, then each field in
 * declaration order. A taken name gets `_` appended until it is free. A field
 * named like its type, or like a type in its own package, is suggested as
 * `<package>.<name>`, which the allocator turns into `<package>_<name>`.
 */
internal object WireNames {

    /** kotlinpoet's `KEYWORDS`: hard, soft and modifier keywords. */
    private val KOTLINPOET_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally", "get",
        "import", "init", "param", "property", "receiver", "set", "setparam", "where",
        "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum",
        "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
        "noinline", "open", "operator", "out", "override", "private", "protected", "public",
        "reified", "sealed", "suspend", "tailrec", "value", "vararg",
        "header", "impl", "yield",
    )

    /** Allocated by Wire before any field, in this order. */
    private val WIRE_MEMBERS = listOf(
        "unknownFields", "ADAPTER", "adapter", "reader", "Builder", "builder", "MESSAGE_OPTIONS",
    )

    fun layout(message: DescriptorProto, schema: WireSchema): WireLayout {
        val allocated = KOTLINPOET_KEYWORDS.toHashSet()
        fun newName(suggestion: String): String {
            var name = toJavaIdentifier(suggestion)
            while (!allocated.add(name)) name += "_"
            return name
        }
        WIRE_MEMBERS.forEach { newName(it) }

        val boxed = boxedOneofIndices(message, schema.boxOneOfsMinSize)
        val properties = mutableMapOf<String, String>()
        val boxes = mutableMapOf<Int, WireBox>()
        // Wire allocates fields and boxes in source order, a box at its first member.
        for (field in message.fieldList) {
            val oneofIndex = if (field.hasOneofIndex() && !field.proto3Optional) field.oneofIndex else null
            if (oneofIndex != null && oneofIndex in boxed) {
                if (oneofIndex in boxes) continue
                val oneofName = message.getOneofDecl(oneofIndex).name
                val property = newName(oneofName)
                newName(oneofName.replaceFirstChar(Char::titlecase)) // the Key class
                newName("${property}_keys".uppercase())
                val keys = message.fieldList
                    .filter { it.hasOneofIndex() && it.oneofIndex == oneofIndex }
                    .associate { it.name to newName("${oneofName}_${it.name}".uppercase()) }
                boxes[oneofIndex] = WireBox(oneofName, property, keys)
                continue
            }
            val eponymous = field.name == wireTypeSimpleName(field) ||
                qualified(schema.protoPackage, field.name) in schema.knownTypes
            val suggestion = if (eponymous) qualified(schema.protoPackage, field.name) else field.name
            properties[field.name] = newName(suggestion)
        }
        return WireLayout(properties, boxes)
    }

    /**
     * Indices of the real oneofs with at least [minSize] members, which Wire
     * boxes. A proto3 `optional` field's synthetic oneof is not a oneof to Wire.
     */
    private fun boxedOneofIndices(message: DescriptorProto, minSize: Int): Set<Int> = message.fieldList
        .filter { it.hasOneofIndex() && !it.proto3Optional }
        .groupingBy { it.oneofIndex }
        .eachCount()
        .filterValues { it >= minSize }
        .keys

    /** Whether [name] is a Kotlin keyword kotlinpoet would escape. */
    fun isKeyword(name: String): Boolean = name in KOTLINPOET_KEYWORDS

    private fun qualified(pkg: String, name: String) = if (pkg.isEmpty()) name else "$pkg.$name"

    /** `ProtoType.simpleName` for the field's type. */
    private fun wireTypeSimpleName(field: FieldDescriptorProto): String = when (field.type) {
        Type.TYPE_MESSAGE, Type.TYPE_ENUM, Type.TYPE_GROUP -> field.typeName.substringAfterLast('.')
        else -> field.type.name.removePrefix("TYPE_").lowercase()
    }

    /** kotlinpoet's `toJavaIdentifier`. */
    private fun toJavaIdentifier(suggestion: String): String = buildString {
        var i = 0
        while (i < suggestion.length) {
            val codePoint = suggestion.codePointAt(i)
            if (i == 0 && !Character.isJavaIdentifierStart(codePoint) && Character.isJavaIdentifierPart(codePoint)) {
                append("_")
            }
            appendCodePoint(if (Character.isJavaIdentifierPart(codePoint)) codePoint else '_'.code)
            i += Character.charCount(codePoint)
        }
    }
}

/**
 * Wire 7 Kotlin message classes.
 *
 * Message fields, oneof members and fields with explicit presence are nullable
 * properties. Smart casts on a property declared in another module don't
 * compile, so each nullable field the body reads is bound to a local first
 * ([bindings]); every other read is `this.<property>`, which keeps a field named
 * like an emitter local (`violations`, `item`, …) from resolving to that local.
 *
 * A boxed oneof ([WireBox]) is one `OneOf<Key, Value>?` property. Its box is
 * bound to a local, and each member read is bound to
 * `box?.getOrNull(<Receiver>.<KEY>)`, the member's value when the box holds
 * that member's key. Presence, the oneof case and `required` are read off the
 * box.
 *
 * One instance serves one message, whose Kotlin type is [receiverType].
 */
class WireAccessors(
    private val message: DescriptorProto,
    private val fileSyntax: FileSyntax,
    schema: WireSchema,
    private val receiverType: String,
) : AccessorStyle {

    private val layout = WireNames.layout(message, schema)

    /** Oneof index of each member of a boxed oneof, by field name. */
    private val boxedMembers: Map<String, Int> = message.fieldList
        .filter { it.hasOneofIndex() && !it.proto3Optional && it.oneofIndex in layout.boxes }
        .associate { it.name to it.oneofIndex }

    private val boxLocals = mutableMapOf<Int, String>()

    private val localNames: Map<String, String> = run {
        val taken = mutableSetOf<String>()
        fun allocate(suggestion: String): String {
            var local = if (suggestion in EMITTER_IDENTIFIERS) "field_$suggestion" else suggestion
            while (local in EMITTER_IDENTIFIERS || local in taken || WireNames.isKeyword(local)) local += "_"
            taken += local
            return local
        }
        message.fieldList.filter { isNullable(it) }.associate { field ->
            val oneofIndex = boxedMembers[field.name]
            val local = if (oneofIndex != null) {
                boxLocals.getOrPut(oneofIndex) { allocate(layout.boxes.getValue(oneofIndex).property) }
                allocate(field.name.replace(Regex("""\W"""), "_"))
            } else {
                allocate(layout.properties.getValue(field.name))
            }
            field.name to local
        }
    }

    /** Field names whose local [field] handed out, in first-use order. */
    private val requested = linkedSetOf<String>()

    /** Boxed oneofs whose box local was handed out directly. */
    private val requestedBoxes = mutableSetOf<Int>()

    private fun isNullable(field: FieldDescriptorProto): Boolean {
        if (field.label == Label.LABEL_REPEATED) return false
        if (field.label == Label.LABEL_REQUIRED) return false
        if (field.hasOneofIndex()) return true // real and synthetic (proto3 `optional`) oneofs
        return when (fileSyntax) {
            FileSyntax.PROTO2 -> true
            FileSyntax.PROTO3 -> field.type == Type.TYPE_MESSAGE || field.type == Type.TYPE_GROUP
            FileSyntax.EDITIONS -> throw PluginOptionException("target=wire does not support editions files")
        }
    }

    private fun local(field: FieldDescriptorProto): String {
        val local = localNames[field.name]
            ?: error("${message.name}.${field.name} is not nullable in Wire; it has no presence to bind")
        requested += field.name
        return local
    }

    private fun boxLocal(oneofIndex: Int): String {
        requestedBoxes += oneofIndex
        return boxLocals.getValue(oneofIndex)
    }

    /**
     * `val x = this.x` lines for the nullable fields [body] reads, in field
     * declaration order. A local is counted as read when it appears as a bare
     * identifier, not inside a field-path string or after a `.`. A boxed
     * member's binding reads its box, so the box is bound before its first
     * member.
     */
    fun bindings(body: String): List<String> {
        fun reads(local: String) =
            Regex("""(?<![\w"$.])${Regex.escape(local)}(?!\w)""").containsMatchIn(body)

        val readMembers = message.fieldList
            .filter { it.name in requested && reads(localNames.getValue(it.name)) }
            .map { it.name }
            .toSet()
        val readBoxes = layout.boxes.keys.filter { index ->
            (index in requestedBoxes && reads(boxLocals.getValue(index))) ||
                readMembers.any { boxedMembers[it] == index }
        }.toSet()

        val lines = mutableListOf<String>()
        val boundBoxes = mutableSetOf<Int>()
        for (field in message.fieldList) {
            val oneofIndex = boxedMembers[field.name]
            if (oneofIndex != null && oneofIndex in readBoxes && boundBoxes.add(oneofIndex)) {
                lines += "val ${boxLocals.getValue(oneofIndex)} = this.${layout.boxes.getValue(oneofIndex).property}"
            }
            if (field.name !in readMembers) continue
            val local = localNames.getValue(field.name)
            lines += if (oneofIndex != null) {
                "val $local = ${boxLocals.getValue(oneofIndex)}?.getOrNull(${keyConstant(oneofIndex, field)})"
            } else {
                "val $local = this.${layout.properties.getValue(field.name)}"
            }
        }
        return lines
    }

    private fun keyConstant(oneofIndex: Int, member: FieldDescriptorProto): String =
        "$receiverType.${layout.boxes.getValue(oneofIndex).keys.getValue(member.name)}"

    override fun field(field: FieldDescriptorProto): String =
        if (field.name in localNames) local(field) else "this.${layout.properties.getValue(field.name)}"

    override fun has(field: FieldDescriptorProto): String =
        if (field.name in localNames) "${local(field)} != null" else "true"

    override fun hasFromAccessor(accessor: String): String =
        if (accessor.startsWith("this.")) "true" else "$accessor != null"

    override fun list(accessor: String): String = accessor

    override fun map(accessor: String): String = accessor

    override fun enumNumber(accessor: String): String = "$accessor.value"

    override fun fieldEnumNumber(accessor: String): String = "$accessor.value"

    override fun celEnumNumber(accessor: String, fileSyntax: FileSyntax): String =
        throw PluginOptionException("target=wire does not support CEL rules")

    // Wire drops values outside the enum into unknownFields on decode.
    override fun enumUndefined(accessor: String): String? = null

    // Wrappers map to nullable Kotlin scalars: the accessor is the value.
    override fun wrapperValue(accessor: String): String = accessor

    override fun bytesNotEmpty(accessor: String): String = "$accessor.size > 0"

    // com.squareup.wire.Duration / Instant: only these getters are common API.
    override fun durationSeconds(accessor: String): String = "$accessor.getSeconds()"
    override fun durationNanos(accessor: String): String = "$accessor.getNano()"
    override fun timestampSeconds(accessor: String): String = "$accessor.getEpochSecond()"
    override fun timestampNanos(accessor: String): String = "$accessor.getNano()"

    override fun fieldMaskPaths(accessor: String): String =
        throw PluginOptionException("target=wire does not support field_mask rules")

    override fun oneofCaseIs(receiverType: String, oneofName: String, member: FieldDescriptorProto): String {
        val oneofIndex = boxedMembers[member.name]
            ?: return "${local(member)} != null"
        return "${boxLocal(oneofIndex)}?.key == ${keyConstant(oneofIndex, member)}"
    }

    override fun oneofNotSet(receiverType: String, oneofName: String, members: List<FieldDescriptorProto>): String {
        val oneofIndex = members.firstNotNullOfOrNull { boxedMembers[it.name] }
            ?: return members.joinToString(" && ") { "${local(it)} == null" }
        return "${boxLocal(oneofIndex)} == null"
    }

    private companion object {
        /** Names the emitted function body declares or resolves unqualified. */
        val EMITTER_IDENTIFIERS = setOf(
            "violations", "result", "index", "item", "mapKey", "mapValue", "it", "_celNow",
            "Validators", "FieldViolation", "ValidationResult", "dev",
        )
    }
}

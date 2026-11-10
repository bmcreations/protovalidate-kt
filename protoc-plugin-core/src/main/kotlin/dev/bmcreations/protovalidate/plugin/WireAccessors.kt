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
 */
data class WireSchema(val protoPackage: String, val knownTypes: Set<String>)

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

    fun propertyNames(message: DescriptorProto, schema: WireSchema): Map<String, String> {
        val allocated = KOTLINPOET_KEYWORDS.toHashSet()
        fun newName(suggestion: String): String {
            var name = toJavaIdentifier(suggestion)
            while (!allocated.add(name)) name += "_"
            return name
        }
        WIRE_MEMBERS.forEach { newName(it) }
        return message.fieldList.associate { field ->
            val eponymous = field.name == wireTypeSimpleName(field) ||
                qualified(schema.protoPackage, field.name) in schema.knownTypes
            val suggestion = if (eponymous) qualified(schema.protoPackage, field.name) else field.name
            field.name to newName(suggestion)
        }
    }

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
 * Wire 7 Kotlin message classes, with flat (unboxed) oneofs.
 *
 * Message fields, oneof members and fields with explicit presence are nullable
 * properties. Smart casts on a property declared in another module don't
 * compile, so each nullable field the body reads is bound to a local first
 * ([bindings]); every other read is `this.<property>`, which keeps a field named
 * like an emitter local (`violations`, `item`, …) from resolving to that local.
 *
 * One instance serves one message.
 */
class WireAccessors(
    private val message: DescriptorProto,
    private val fileSyntax: FileSyntax,
    schema: WireSchema,
) : AccessorStyle {

    private val propertyNames = WireNames.propertyNames(message, schema)

    private val localNames: Map<String, String> = run {
        val taken = mutableSetOf<String>()
        message.fieldList.filter { isNullable(it) }.associate { field ->
            val property = propertyNames.getValue(field.name)
            var local = if (property in EMITTER_IDENTIFIERS) "field_$property" else property
            while (local in EMITTER_IDENTIFIERS || local in taken) local += "_"
            taken += local
            field.name to local
        }
    }

    /** Field names whose local [field] handed out, in first-use order. */
    private val requested = linkedSetOf<String>()

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

    /**
     * `val x = this.x` lines for the nullable fields [body] reads, in field
     * declaration order. A local is counted as read when it appears as a bare
     * identifier, not inside a field-path string or after a `.`.
     */
    fun bindings(body: String): List<String> = message.fieldList
        .filter { it.name in requested }
        .mapNotNull { field ->
            val local = localNames.getValue(field.name)
            val read = Regex("""(?<![\w"$.])${Regex.escape(local)}(?!\w)""").containsMatchIn(body)
            if (read) "val $local = this.${propertyNames.getValue(field.name)}" else null
        }

    override fun field(field: FieldDescriptorProto): String =
        if (field.name in localNames) local(field) else "this.${propertyNames.getValue(field.name)}"

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

    override fun oneofCaseIs(receiverType: String, oneofName: String, member: FieldDescriptorProto): String =
        "${local(member)} != null"

    override fun oneofNotSet(receiverType: String, oneofName: String, members: List<FieldDescriptorProto>): String =
        members.joinToString(" && ") { "${local(it)} == null" }

    private companion object {
        /** Names the emitted function body declares or resolves unqualified. */
        val EMITTER_IDENTIFIERS = setOf(
            "violations", "result", "index", "item", "mapKey", "mapValue", "it", "_celNow",
            "Validators", "FieldViolation", "ValidationResult", "dev",
        )
    }
}

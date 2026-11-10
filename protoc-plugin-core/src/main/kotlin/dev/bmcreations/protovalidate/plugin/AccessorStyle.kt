package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto

/**
 * How emitted code reads a message's fields.
 *
 * The emitters decide *what* to check; this decides how a field, its presence,
 * its list/map view or an enum's number is spelled for the message classes the
 * validators are compiled against. Methods taking an `accessor` receive the
 * expression [field] returned (or a loop variable such as `item`, `mapKey`).
 */
interface AccessorStyle {
    /** Expression for a singular field's value, or the base for [list]/[map]. */
    fun field(field: FieldDescriptorProto): String

    /** Boolean expression: the field is set. */
    fun has(field: FieldDescriptorProto): String

    /** Like [has], for emitters that only hold the [field] expression. */
    fun hasFromAccessor(accessor: String): String

    fun list(accessor: String): String

    fun map(accessor: String): String

    /** Numeric value of an enum, where [accessor] may be a field or a repeated item. */
    fun enumNumber(accessor: String): String

    /** Numeric value of an enum field in a zero-value check. */
    fun fieldEnumNumber(accessor: String): String

    /** Numeric value of an enum for a CEL comparison. */
    fun celEnumNumber(accessor: String, fileSyntax: FileSyntax): String

    /** Kotlin condition that is true for an enum value the schema doesn't define, or null if unobservable. */
    fun enumUndefined(accessor: String): String?

    /** The scalar inside a `google.protobuf.*Value` wrapper. */
    fun wrapperValue(accessor: String): String

    fun bytesNotEmpty(accessor: String): String

    fun durationSeconds(accessor: String): String
    fun durationNanos(accessor: String): String
    fun timestampSeconds(accessor: String): String
    fun timestampNanos(accessor: String): String

    fun fieldMaskPaths(accessor: String): String

    /** Boolean expression: [member] is the active case of oneof [oneofName] on [receiverType]. */
    fun oneofCaseIs(receiverType: String, oneofName: String, member: FieldDescriptorProto): String

    /** Boolean expression: no member of oneof [oneofName] is set. */
    fun oneofNotSet(receiverType: String, oneofName: String, members: List<FieldDescriptorProto>): String
}

/** protobuf-java (and protobuf-kotlin) generated message classes. */
object JavaAccessors : AccessorStyle {
    private fun raw(accessor: String): String = accessor.removeSurrounding("`")

    override fun field(field: FieldDescriptorProto): String = escapeIfKeyword(snakeToCamelLower(field.name))

    override fun has(field: FieldDescriptorProto): String = "has${snakeToCamel(field.name)}()"

    override fun hasFromAccessor(accessor: String): String =
        "has${raw(accessor).replaceFirstChar { it.uppercaseChar() }}()"

    override fun list(accessor: String): String = "${raw(accessor)}List"

    override fun map(accessor: String): String = "${raw(accessor)}Map"

    // A repeated item is the enum itself; a field has a generated `<name>Value` getter.
    override fun enumNumber(accessor: String): String =
        if (accessor == "item") "item.number" else "${raw(accessor)}Value"

    override fun fieldEnumNumber(accessor: String): String = "${raw(accessor)}Value"

    override fun celEnumNumber(accessor: String, fileSyntax: FileSyntax): String =
        if (accessor == "item" || accessor == "item.value" || accessor == "mapKey" || accessor == "mapValue") {
            // Repeated/map enum items: use .number for CEL integer comparison
            "$accessor.number"
        } else if (fileSyntax == FileSyntax.PROTO2) {
            // Proto2: no *Value getter, use .number on the enum accessor
            "$accessor.number"
        } else {
            "${raw(accessor)}Value"
        }

    override fun enumUndefined(accessor: String): String = "$accessor.toString() == \"UNRECOGNIZED\""

    override fun wrapperValue(accessor: String): String = "$accessor.value"

    override fun bytesNotEmpty(accessor: String): String = "!$accessor.isEmpty"

    override fun durationSeconds(accessor: String): String = "$accessor.seconds"
    override fun durationNanos(accessor: String): String = "$accessor.nanos"
    override fun timestampSeconds(accessor: String): String = "$accessor.seconds"
    override fun timestampNanos(accessor: String): String = "$accessor.nanos"

    override fun fieldMaskPaths(accessor: String): String = "$accessor.pathsList"

    override fun oneofCaseIs(receiverType: String, oneofName: String, member: FieldDescriptorProto): String =
        "${snakeToCamelLower(oneofName)}Case == $receiverType.${snakeToCamel(oneofName)}Case.${member.name.uppercase()}"

    override fun oneofNotSet(receiverType: String, oneofName: String, members: List<FieldDescriptorProto>): String {
        val notSetValue = oneofName.replace("_", "").uppercase() + "_NOT_SET"
        return "${snakeToCamelLower(oneofName)}Case == $receiverType.${snakeToCamel(oneofName)}Case.$notSetValue"
    }
}

package dev.bmcreations.protovalidate.plugin

/**
 * Maps to buf.validate.Ignore enum.
 * Controls when validation rules should be skipped.
 */
enum class IgnoreMode {
    /** Default: for presence-tracking fields, skip if unset; for non-presence, always validate */
    UNSPECIFIED,
    /** Skip if the field value is the zero/empty value (regardless of presence tracking) */
    IF_UNPOPULATED,
    /** Skip if the field value equals its default (handles proto2 custom defaults) */
    IF_DEFAULT_VALUE,
    /** Always skip validation */
    ALWAYS,
}

enum class FileSyntax { PROTO2, PROTO3, EDITIONS }

enum class RuleType {
    STRING, BYTES,
    INT32, INT64, UINT32, UINT64, SINT32, SINT64,
    FIXED32, FIXED64, SFIXED32, SFIXED64,
    FLOAT, DOUBLE,
    BOOL, ENUM,
    REPEATED, MAP,
    DURATION, TIMESTAMP, ANY, FIELD_MASK,
    NONE
}

val RuleType.rulePrefix: String get() = name.lowercase()

object WellKnownTypes {
    const val DURATION   = ".google.protobuf.Duration"
    const val TIMESTAMP  = ".google.protobuf.Timestamp"
    const val ANY        = ".google.protobuf.Any"
    const val FIELD_MASK = ".google.protobuf.FieldMask"

    val WRAPPER_TO_RULE_TYPE: Map<String, RuleType> = mapOf(
        ".google.protobuf.DoubleValue" to RuleType.DOUBLE,
        ".google.protobuf.FloatValue" to RuleType.FLOAT,
        ".google.protobuf.Int64Value" to RuleType.INT64,
        ".google.protobuf.UInt64Value" to RuleType.UINT64,
        ".google.protobuf.Int32Value" to RuleType.INT32,
        ".google.protobuf.UInt32Value" to RuleType.UINT32,
        ".google.protobuf.BoolValue" to RuleType.BOOL,
        ".google.protobuf.StringValue" to RuleType.STRING,
        ".google.protobuf.BytesValue" to RuleType.BYTES,
    )

    val WRAPPER_SUFFIXES: Set<String> = WRAPPER_TO_RULE_TYPE.keys
}

data class FieldRuleSet(
    val type: RuleType = RuleType.NONE,
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val message: MessageRuleSet? = null,
    val string: StringRuleSet? = null,
    val bytes: BytesRuleSet? = null,
    val numeric: NumericRuleSet? = null,
    val bool: BoolRuleSet? = null,
    val enum: EnumRuleSet? = null,
    val repeated: RepeatedRuleSet? = null,
    val map: MapRuleSet? = null,
    val duration: DurationRuleSet? = null,
    val timestamp: TimestampRuleSet? = null,
    val any: AnyRuleSet? = null,
    val fieldMask: FieldMaskRuleSet? = null,
    /** CEL rules applied directly to this field via (field).cel / (field).cel_expression */
    val celRules: List<CelRule> = emptyList(),
    /** Predefined CEL rules from extensions on the type-specific rule message */
    val predefinedCelRules: List<PredefinedCelRule> = emptyList(),
)

data class MessageRuleSet(
    val required: Boolean = false,
    val skip: Boolean = false,
    /** When true, required only checks presence on explicitly-optional fields (PGV semantics) */
    val requiredOnlyExplicit: Boolean = false,
)

data class StringRuleSet(
    val const: String? = null,
    val len: Long? = null,
    val minLen: Long? = null,
    val maxLen: Long? = null,
    val lenBytes: Long? = null,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val pattern: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val contains: String? = null,
    val notContains: String? = null,
    val inList: List<String> = emptyList(),
    val notInList: List<String> = emptyList(),
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val wellKnown: StringWellKnown? = null,
    val strict: Boolean = true,
)

enum class StringWellKnown {
    EMAIL, EMAIL_PGV, URI, URI_REF, UUID, HOSTNAME,
    IP, IPV4, IPV6, ADDRESS,
    HTTP_HEADER_NAME, HTTP_HEADER_VALUE,
    HOST_AND_PORT, ULID, TUUID,
    IP_WITH_PREFIXLEN, IPV4_WITH_PREFIXLEN, IPV6_WITH_PREFIXLEN,
    IP_PREFIX, IPV4_PREFIX, IPV6_PREFIX,
    PROTOBUF_FQN, PROTOBUF_DOT_FQN,
}

data class BytesRuleSet(
    val const: ByteArray? = null,
    val len: Long? = null,
    val minLen: Long? = null,
    val maxLen: Long? = null,
    val pattern: String? = null,
    val prefix: ByteArray? = null,
    val suffix: ByteArray? = null,
    val contains: ByteArray? = null,
    val inList: List<ByteArray> = emptyList(),
    val notInList: List<ByteArray> = emptyList(),
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val wellKnown: BytesWellKnown? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BytesRuleSet

        if (len != other.len) return false
        if (minLen != other.minLen) return false
        if (maxLen != other.maxLen) return false
        if (ignore != other.ignore) return false
        if (!const.contentEquals(other.const)) return false
        if (pattern != other.pattern) return false
        if (!prefix.contentEquals(other.prefix)) return false
        if (!suffix.contentEquals(other.suffix)) return false
        if (!contains.contentEquals(other.contains)) return false
        if (inList != other.inList) return false
        if (notInList != other.notInList) return false
        if (wellKnown != other.wellKnown) return false

        return true
    }

    override fun hashCode(): Int {
        var result = len?.hashCode() ?: 0
        result = 31 * result + (minLen?.hashCode() ?: 0)
        result = 31 * result + (maxLen?.hashCode() ?: 0)
        result = 31 * result + ignore.hashCode()
        result = 31 * result + (const?.contentHashCode() ?: 0)
        result = 31 * result + (pattern?.hashCode() ?: 0)
        result = 31 * result + (prefix?.contentHashCode() ?: 0)
        result = 31 * result + (suffix?.contentHashCode() ?: 0)
        result = 31 * result + (contains?.contentHashCode() ?: 0)
        result = 31 * result + inList.hashCode()
        result = 31 * result + notInList.hashCode()
        result = 31 * result + (wellKnown?.hashCode() ?: 0)
        return result
    }
}

enum class BytesWellKnown {
    IP, IPV4, IPV6, UUID,
}

// Covers all numeric types (int32, int64, uint32, uint64, sint32, sint64,
// fixed32, fixed64, sfixed32, sfixed64, float, double).
// Values are stored as pre-formatted Kotlin literal strings (e.g. "42L", "3.14f").
// Raw Double values are stored alongside for exclusive-range detection at codegen time.
data class NumericRuleSet(
    val constVal: String? = null,
    val ltVal: String? = null,
    val lteVal: String? = null,
    val gtVal: String? = null,
    val gteVal: String? = null,
    val inList: List<String> = emptyList(),
    val notInList: List<String> = emptyList(),
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val zeroLiteral: String = "0",  // "0", "0L", "0.0f", "0.0"
    val ltRaw: Double? = null,
    val lteRaw: Double? = null,
    val gtRaw: Double? = null,
    val gteRaw: Double? = null,
    val finite: Boolean = false,
)

data class BoolRuleSet(
    val const: Boolean? = null,
)

data class EnumRuleSet(
    val const: Int? = null,
    val definedOnly: Boolean = false,
    val inList: List<Int> = emptyList(),
    val notInList: List<Int> = emptyList(),
)

data class RepeatedRuleSet(
    val minItems: Long? = null,
    val maxItems: Long? = null,
    val unique: Boolean = false,
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val items: FieldRuleSet? = null,
)

data class MapRuleSet(
    val minPairs: Long? = null,
    val maxPairs: Long? = null,
    val ignore: IgnoreMode = IgnoreMode.UNSPECIFIED,
    val keys: FieldRuleSet? = null,
    val values: FieldRuleSet? = null,
)

data class DurationValue(val seconds: Long, val nanos: Int) : Comparable<DurationValue> {
    override fun compareTo(other: DurationValue): Int {
        val secCmp = seconds.compareTo(other.seconds)
        return if (secCmp != 0) secCmp else nanos.compareTo(other.nanos)
    }
}

data class DurationRuleSet(
    val required: Boolean = false,
    val const: DurationValue? = null,
    val lt: DurationValue? = null,
    val lte: DurationValue? = null,
    val gt: DurationValue? = null,
    val gte: DurationValue? = null,
    val inList: List<DurationValue> = emptyList(),
    val notInList: List<DurationValue> = emptyList(),
)

data class TimestampRuleSet(
    val required: Boolean = false,
    val const: DurationValue? = null,
    val lt: DurationValue? = null,
    val lte: DurationValue? = null,
    val gt: DurationValue? = null,
    val gte: DurationValue? = null,
    val ltNow: Boolean = false,
    val gtNow: Boolean = false,
    val within: DurationValue? = null,
    val inList: List<DurationValue> = emptyList(),
    val notInList: List<DurationValue> = emptyList(),
)

data class AnyRuleSet(
    val required: Boolean = false,
    val inList: List<String> = emptyList(),
    val notInList: List<String> = emptyList(),
)

data class FieldMaskRuleSet(
    // const: sorted paths list that the field must equal (paths sorted for canonical comparison)
    val constPaths: List<String>? = null,
    // in: each path in the field's FieldMask must be one of these strings or a sub-path of one
    val inList: List<String> = emptyList(),
    // not_in: no path in the field's FieldMask may be one of these strings or a sub-path of one
    val notInList: List<String> = emptyList(),
)

/**
 * Represents a single `(buf.validate.message).oneof` constraint.
 * @param fields the list of field names in this oneof group
 * @param required if true, exactly one field must be set; if false, at most one can be set
 */
data class MessageOneofRuleSet(
    val fields: List<String>,
    val required: Boolean = false,
)

/**
 * A CEL rule applied via `(field).cel` or `(field).cel_expression`.
 * @param id machine-readable identifier (or the expression itself for cel_expression)
 * @param expression the CEL expression string
 * @param message human-readable error message (empty if the expression returns a string)
 */
data class CelRule(
    val id: String,
    val expression: String,
    val message: String = "",
    val isCelExpression: Boolean = false,
)

/**
 * A predefined CEL rule from an extension on a type-specific rule message.
 * For example: `(buf.validate.field).uint32.(uint32_even_proto2) = true` references an
 * extension whose `(predefined).cel` contains the expression.
 *
 * @param id machine-readable identifier from the Rule.id
 * @param expression the CEL expression string
 * @param message human-readable error message
 * @param ruleValue Kotlin literal expression for the value of the extension field (the `rule` binding)
 */
data class PredefinedCelRule(
    val id: String,
    val expression: String,
    val message: String = "",
    val ruleValue: String,
)

/**
 * Message-level CEL rules from `(buf.validate.message).cel` / `.cel_expression`.
 */
data class MessageCelRule(
    val id: String,
    val expression: String,
    val message: String = "",
    val isCelExpression: Boolean = false,
)

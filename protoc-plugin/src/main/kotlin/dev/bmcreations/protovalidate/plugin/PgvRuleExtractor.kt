package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DescriptorProtos.OneofOptions
import com.google.protobuf.ExtensionRegistry
import io.envoyproxy.pgv.validate.Validate
import io.envoyproxy.pgv.validate.Validate.*

class PgvRuleExtractor : RuleExtractor {

    override val oneofIgnoreEmptySkipsZeroValue: Boolean get() = true

    private val registry = ExtensionRegistry.newInstance().also {
        Validate.registerAllExtensions(it)
    }

    override fun createRegistry(): ExtensionRegistry = registry

    override fun isMessageDisabled(options: MessageOptions): Boolean =
        options.hasExtension(Validate.disabled) && options.getExtension(Validate.disabled)

    override fun isMessageIgnored(options: MessageOptions): Boolean =
        options.hasExtension(Validate.ignored) && options.getExtension(Validate.ignored)

    override fun isOneofRequired(options: OneofOptions): Boolean =
        options.hasExtension(Validate.required) && options.getExtension(Validate.required)

    override fun getFieldRules(options: FieldOptions): FieldRuleSet? {
        if (!options.hasExtension(Validate.rules)) return null
        val rules = options.getExtension(Validate.rules) ?: return null
        return convertFieldRules(rules)
    }

    private fun convertFieldRules(rules: FieldRules): FieldRuleSet {
        val message = if (rules.hasMessage()) {
            MessageRuleSet(
                required = rules.message.hasRequired() && rules.message.required,
                skip = rules.message.hasSkip() && rules.message.skip,
                requiredOnlyExplicit = true,
            )
        } else null

        // Extract ignore_empty from type-specific rules and propagate to FieldRuleSet level
        val typeIgnore = when (rules.typeCase) {
            FieldRules.TypeCase.STRING -> if (rules.string.hasIgnoreEmpty() && rules.string.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.BYTES -> if (rules.bytes.hasIgnoreEmpty() && rules.bytes.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.INT32 -> if (rules.int32.hasIgnoreEmpty() && rules.int32.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.INT64 -> if (rules.int64.hasIgnoreEmpty() && rules.int64.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.UINT32 -> if (rules.uint32.hasIgnoreEmpty() && rules.uint32.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.UINT64 -> if (rules.uint64.hasIgnoreEmpty() && rules.uint64.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.SINT32 -> if (rules.sint32.hasIgnoreEmpty() && rules.sint32.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.SINT64 -> if (rules.sint64.hasIgnoreEmpty() && rules.sint64.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.FIXED32 -> if (rules.fixed32.hasIgnoreEmpty() && rules.fixed32.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.FIXED64 -> if (rules.fixed64.hasIgnoreEmpty() && rules.fixed64.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.SFIXED32 -> if (rules.sfixed32.hasIgnoreEmpty() && rules.sfixed32.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.SFIXED64 -> if (rules.sfixed64.hasIgnoreEmpty() && rules.sfixed64.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.FLOAT -> if (rules.float.hasIgnoreEmpty() && rules.float.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.DOUBLE -> if (rules.double.hasIgnoreEmpty() && rules.double.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.BOOL -> IgnoreMode.UNSPECIFIED
            FieldRules.TypeCase.ENUM -> IgnoreMode.UNSPECIFIED
            else -> IgnoreMode.UNSPECIFIED
        }

        return when (rules.typeCase) {
            FieldRules.TypeCase.STRING -> FieldRuleSet(
                type = RuleType.STRING,
                message = message,
                string = convertStringRules(rules.string),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.BYTES -> FieldRuleSet(
                type = RuleType.BYTES,
                message = message,
                bytes = convertBytesRules(rules.bytes),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.INT32 -> FieldRuleSet(
                type = RuleType.INT32,
                message = message,
                numeric = convertInt32Rules(rules.int32),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.INT64 -> FieldRuleSet(
                type = RuleType.INT64,
                message = message,
                numeric = convertInt64Rules(rules.int64),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.UINT32 -> FieldRuleSet(
                type = RuleType.UINT32,
                message = message,
                numeric = convertUInt32Rules(rules.uint32),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.UINT64 -> FieldRuleSet(
                type = RuleType.UINT64,
                message = message,
                numeric = convertUInt64Rules(rules.uint64),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.SINT32 -> FieldRuleSet(
                type = RuleType.SINT32,
                message = message,
                numeric = convertSInt32Rules(rules.sint32),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.SINT64 -> FieldRuleSet(
                type = RuleType.SINT64,
                message = message,
                numeric = convertSInt64Rules(rules.sint64),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.FIXED32 -> FieldRuleSet(
                type = RuleType.FIXED32,
                message = message,
                numeric = convertFixed32Rules(rules.fixed32),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.FIXED64 -> FieldRuleSet(
                type = RuleType.FIXED64,
                message = message,
                numeric = convertFixed64Rules(rules.fixed64),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.SFIXED32 -> FieldRuleSet(
                type = RuleType.SFIXED32,
                message = message,
                numeric = convertSFixed32Rules(rules.sfixed32),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.SFIXED64 -> FieldRuleSet(
                type = RuleType.SFIXED64,
                message = message,
                numeric = convertSFixed64Rules(rules.sfixed64),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.FLOAT -> FieldRuleSet(
                type = RuleType.FLOAT,
                message = message,
                numeric = convertFloatRules(rules.float),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.DOUBLE -> FieldRuleSet(
                type = RuleType.DOUBLE,
                message = message,
                numeric = convertDoubleRules(rules.double),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.BOOL -> FieldRuleSet(
                type = RuleType.BOOL,
                message = message,
                bool = BoolRuleSet(
                    const = if (rules.bool.hasConst()) rules.bool.const else null,
                ),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.ENUM -> FieldRuleSet(
                type = RuleType.ENUM,
                message = message,
                enum = EnumRuleSet(
                    const = if (rules.enum.hasConst()) rules.enum.const else null,
                    definedOnly = rules.enum.hasDefinedOnly() && rules.enum.definedOnly,
                    inList = rules.enum.inList.toList(),
                    notInList = rules.enum.notInList.toList(),
                ),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.REPEATED -> FieldRuleSet(
                type = RuleType.REPEATED,
                message = message,
                repeated = convertRepeatedRules(rules.repeated),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.MAP -> FieldRuleSet(
                type = RuleType.MAP,
                message = message,
                map = convertMapRules(rules.map),
                ignore = typeIgnore,
            )
            FieldRules.TypeCase.DURATION -> FieldRuleSet(
                type = RuleType.DURATION,
                message = message,
                duration = convertDurationRules(rules.duration),
            )
            FieldRules.TypeCase.TIMESTAMP -> FieldRuleSet(
                type = RuleType.TIMESTAMP,
                message = message,
                timestamp = convertTimestampRules(rules.timestamp),
            )
            FieldRules.TypeCase.ANY -> FieldRuleSet(
                type = RuleType.ANY,
                message = message,
                any = AnyRuleSet(
                    required = rules.any.hasRequired() && rules.any.required,
                    inList = rules.any.inList.toList(),
                    notInList = rules.any.notInList.toList(),
                ),
            )
            else -> FieldRuleSet(type = RuleType.NONE, message = message)
        }
    }

    // ── String ──

    private fun convertStringRules(r: StringRules): StringRuleSet {
        val strict = if (r.hasStrict()) r.strict else true
        val wellKnown = when (r.wellKnownCase) {
            StringRules.WellKnownCase.EMAIL -> if (r.email) StringWellKnown.EMAIL_PGV else null
            StringRules.WellKnownCase.URI -> if (r.uri) StringWellKnown.URI else null
            StringRules.WellKnownCase.URI_REF -> if (r.uriRef) StringWellKnown.URI_REF else null
            StringRules.WellKnownCase.UUID -> if (r.uuid) StringWellKnown.UUID else null
            StringRules.WellKnownCase.HOSTNAME -> if (r.hostname) StringWellKnown.HOSTNAME else null
            StringRules.WellKnownCase.IP -> if (r.ip) StringWellKnown.IP else null
            StringRules.WellKnownCase.IPV4 -> if (r.ipv4) StringWellKnown.IPV4 else null
            StringRules.WellKnownCase.IPV6 -> if (r.ipv6) StringWellKnown.IPV6 else null
            StringRules.WellKnownCase.ADDRESS -> if (r.address) StringWellKnown.ADDRESS else null
            StringRules.WellKnownCase.WELL_KNOWN_REGEX -> when (r.wellKnownRegex) {
                KnownRegex.HTTP_HEADER_NAME -> StringWellKnown.HTTP_HEADER_NAME
                KnownRegex.HTTP_HEADER_VALUE -> StringWellKnown.HTTP_HEADER_VALUE
                else -> null
            }
            else -> null
        }
        return StringRuleSet(
            const = if (r.hasConst()) r.const else null,
            len = if (r.hasLen()) r.len else null,
            minLen = if (r.hasMinLen()) r.minLen else null,
            maxLen = if (r.hasMaxLen()) r.maxLen else null,
            lenBytes = if (r.hasLenBytes()) r.lenBytes else null,
            minBytes = if (r.hasMinBytes()) r.minBytes else null,
            maxBytes = if (r.hasMaxBytes()) r.maxBytes else null,
            pattern = if (r.hasPattern()) r.pattern else null,
            prefix = if (r.hasPrefix()) r.prefix else null,
            suffix = if (r.hasSuffix()) r.suffix else null,
            contains = if (r.hasContains()) r.contains else null,
            notContains = if (r.hasNotContains()) r.notContains else null,
            inList = r.inList.toList(),
            notInList = r.notInList.toList(),
            ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
            wellKnown = wellKnown,
            strict = strict,
        )
    }

    // ── Bytes ──

    private fun convertBytesRules(r: BytesRules): BytesRuleSet {
        val wellKnown = when (r.wellKnownCase) {
            BytesRules.WellKnownCase.IP -> if (r.ip) BytesWellKnown.IP else null
            BytesRules.WellKnownCase.IPV4 -> if (r.ipv4) BytesWellKnown.IPV4 else null
            BytesRules.WellKnownCase.IPV6 -> if (r.ipv6) BytesWellKnown.IPV6 else null
            else -> null
        }
        return BytesRuleSet(
            const = if (r.hasConst()) r.const.toByteArray() else null,
            len = if (r.hasLen()) r.len else null,
            minLen = if (r.hasMinLen()) r.minLen else null,
            maxLen = if (r.hasMaxLen()) r.maxLen else null,
            pattern = if (r.hasPattern()) r.pattern else null,
            prefix = if (r.hasPrefix()) r.prefix.toByteArray() else null,
            suffix = if (r.hasSuffix()) r.suffix.toByteArray() else null,
            contains = if (r.hasContains()) r.contains.toByteArray() else null,
            inList = r.inList.map { it.toByteArray() },
            notInList = r.notInList.map { it.toByteArray() },
            ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
            wellKnown = wellKnown,
        )
    }

    // ── Numeric converters ──

    private fun convertInt32Rules(r: Int32Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertInt64Rules(r: Int64Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertUInt32Rules(r: UInt32Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertUInt64Rules(r: UInt64Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSInt32Rules(r: SInt32Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSInt64Rules(r: SInt64Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFixed32Rules(r: Fixed32Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFixed64Rules(r: Fixed64Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSFixed32Rules(r: SFixed32Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSFixed64Rules(r: SFixed64Rules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFloatRules(r: FloatRules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}f" else null,
        ltVal = if (r.hasLt()) "${r.lt}f" else null,
        lteVal = if (r.hasLte()) "${r.lte}f" else null,
        gtVal = if (r.hasGt()) "${r.gt}f" else null,
        gteVal = if (r.hasGte()) "${r.gte}f" else null,
        inList = r.inList.map { "${it}f" },
        notInList = r.notInList.map { "${it}f" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0.0f",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertDoubleRules(r: DoubleRules) = NumericRuleSet(

        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        zeroLiteral = "0.0",
        ltRaw = if (r.hasLt()) r.lt else null,
        lteRaw = if (r.hasLte()) r.lte else null,
        gtRaw = if (r.hasGt()) r.gt else null,
        gteRaw = if (r.hasGte()) r.gte else null,
    )

    // ── Repeated ──

    private fun convertRepeatedRules(r: RepeatedRules): RepeatedRuleSet = RepeatedRuleSet(
        minItems = if (r.hasMinItems()) r.minItems else null,
        maxItems = if (r.hasMaxItems()) r.maxItems else null,
        unique = r.hasUnique() && r.unique,
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        items = if (r.hasItems()) convertFieldRules(r.items) else null,
    )

    // ── Map ──

    private fun convertMapRules(r: MapRules): MapRuleSet = MapRuleSet(
        minPairs = if (r.hasMinPairs()) r.minPairs else null,
        maxPairs = if (r.hasMaxPairs()) r.maxPairs else null,
        ignore = if (r.hasIgnoreEmpty() && r.ignoreEmpty) IgnoreMode.IF_DEFAULT_VALUE else IgnoreMode.UNSPECIFIED,
        keys = if (r.hasKeys()) convertFieldRules(r.keys) else null,
        values = if (r.hasValues()) convertFieldRules(r.values) else null,
    )

    // ── Duration ──

    private fun convertDurationRules(r: DurationRules): DurationRuleSet = DurationRuleSet(
        required = r.hasRequired() && r.required,
        const = if (r.hasConst()) DurationValue(r.const.seconds, r.const.nanos) else null,
        lt = if (r.hasLt()) DurationValue(r.lt.seconds, r.lt.nanos) else null,
        lte = if (r.hasLte()) DurationValue(r.lte.seconds, r.lte.nanos) else null,
        gt = if (r.hasGt()) DurationValue(r.gt.seconds, r.gt.nanos) else null,
        gte = if (r.hasGte()) DurationValue(r.gte.seconds, r.gte.nanos) else null,
        inList = r.inList.map { DurationValue(it.seconds, it.nanos) },
        notInList = r.notInList.map { DurationValue(it.seconds, it.nanos) },
    )

    // ── Timestamp ──

    private fun convertTimestampRules(r: TimestampRules): TimestampRuleSet = TimestampRuleSet(
        required = r.hasRequired() && r.required,
        const = if (r.hasConst()) DurationValue(r.const.seconds, r.const.nanos) else null,
        lt = if (r.hasLt()) DurationValue(r.lt.seconds, r.lt.nanos) else null,
        lte = if (r.hasLte()) DurationValue(r.lte.seconds, r.lte.nanos) else null,
        gt = if (r.hasGt()) DurationValue(r.gt.seconds, r.gt.nanos) else null,
        gte = if (r.hasGte()) DurationValue(r.gte.seconds, r.gte.nanos) else null,
        ltNow = r.hasLtNow() && r.ltNow,
        gtNow = r.hasGtNow() && r.gtNow,
        within = if (r.hasWithin()) DurationValue(r.within.seconds, r.within.nanos) else null,
    )
}

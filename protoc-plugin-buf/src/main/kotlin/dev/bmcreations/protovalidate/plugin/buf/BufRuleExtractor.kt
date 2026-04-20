package dev.bmcreations.protovalidate.plugin.buf

import build.buf.validate.FieldRules
import build.buf.validate.PredefinedRules
import build.buf.validate.ValidateProto
import dev.bmcreations.protovalidate.plugin.*
import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DescriptorProtos.OneofOptions
import com.google.protobuf.DynamicMessage
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.GeneratedMessage
import com.google.protobuf.Message

class BufRuleExtractor : RuleExtractor {

    private val registry = ExtensionRegistry.newInstance().also {
        ValidateProto.registerAllExtensions(it)
    }

    /**
     * Map from rule message full name to its dynamic Descriptor.
     * Used to re-parse type-specific rule messages with custom extensions resolved.
     */
    var dynamicRuleDescriptors: Map<String, Descriptors.Descriptor> = emptyMap()

    /**
     * Extension registry that includes user-defined predefined rule extensions.
     * Set from Main.kt after building dynamic descriptors.
     */
    var dynamicExtensionRegistry: ExtensionRegistry = ExtensionRegistry.getEmptyRegistry()

    override fun createRegistry(): ExtensionRegistry = registry

    override fun isMessageDisabled(options: MessageOptions): Boolean {
        // 'disabled' was removed (reserved) in newer buf validate proto
        return false
    }

    override fun isMessageIgnored(options: MessageOptions): Boolean = false

    override fun getMessageOneofRules(options: MessageOptions): List<MessageOneofRuleSet> {
        if (!options.hasExtension(ValidateProto.message)) return emptyList()
        val constraints = options.getExtension(ValidateProto.message) ?: return emptyList()
        if (constraints.oneofCount == 0) return emptyList()
        return constraints.oneofList.map { rule ->
            MessageOneofRuleSet(
                fields = rule.fieldsList.toList(),
                required = rule.hasRequired() && rule.required,
            )
        }
    }

    override fun isOneofRequired(options: OneofOptions): Boolean {
        if (!options.hasExtension(ValidateProto.oneof)) return false
        val constraints = options.getExtension(ValidateProto.oneof)
        return constraints.hasRequired() && constraints.required
    }

    override fun getMessageCelRules(options: MessageOptions): List<MessageCelRule> {
        if (!options.hasExtension(ValidateProto.message)) return emptyList()
        val constraints = options.getExtension(ValidateProto.message) ?: return emptyList()
        val result = mutableListOf<MessageCelRule>()
        // Explicit cel rules
        for (rule in constraints.celList) {
            result.add(MessageCelRule(
                id = rule.id ?: rule.expression ?: "",
                expression = rule.expression ?: "",
                message = rule.message ?: "",
            ))
        }
        // cel_expression shorthand — auto-generate message as "<expr> returned false"
        for (expr in constraints.celExpressionList) {
            result.add(MessageCelRule(
                id = expr,
                expression = expr,
                message = "\"$expr\" returned false",
                isCelExpression = true,
            ))
        }
        return result
    }

    override fun getFieldRules(options: FieldOptions): FieldRuleSet? {
        if (!options.hasExtension(ValidateProto.field)) return null
        val constraints = options.getExtension(ValidateProto.field) ?: return null
        return convertFieldRules(constraints)
    }

    private fun convertFieldRules(c: FieldRules): FieldRuleSet? {
        val isSkipped = c.hasIgnore() && c.ignore == build.buf.validate.Ignore.IGNORE_ALWAYS
        val isRequired = c.hasRequired() && c.required

        val ignoreMode = when {
            isSkipped -> IgnoreMode.ALWAYS
            c.hasIgnore() && c.ignore == build.buf.validate.Ignore.IGNORE_IF_ZERO_VALUE -> IgnoreMode.IF_DEFAULT_VALUE
            else -> IgnoreMode.UNSPECIFIED
        }

        val message = if (isRequired || isSkipped) {
            MessageRuleSet(required = isRequired, skip = isSkipped)
        } else null

        // Extract field-level CEL rules
        val celRules = mutableListOf<CelRule>()
        for (rule in c.celList) {
            celRules.add(CelRule(
                id = rule.id ?: rule.expression ?: "",
                expression = rule.expression ?: "",
                message = rule.message ?: "",
            ))
        }
        for (expr in c.celExpressionList) {
            celRules.add(CelRule(
                id = expr,
                expression = expr,
                message = "\"$expr\" returned false",
                isCelExpression = true,
            ))
        }

        // Extract predefined CEL rules from extensions on the type-specific rule message
        val predefinedCelRules = extractPredefinedCelRules(c)

        return when (c.typeCase) {
            FieldRules.TypeCase.STRING -> FieldRuleSet(
                type = RuleType.STRING,
                ignore = ignoreMode,
                message = message,
                string = convertStringRules(c.string, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.BYTES -> FieldRuleSet(
                type = RuleType.BYTES,
                ignore = ignoreMode,
                message = message,
                bytes = convertBytesRules(c.bytes, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.INT32 -> FieldRuleSet(
                type = RuleType.INT32,
                ignore = ignoreMode,
                message = message,
                numeric = convertInt32Rules(c.int32, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.INT64 -> FieldRuleSet(
                type = RuleType.INT64,
                ignore = ignoreMode,
                message = message,
                numeric = convertInt64Rules(c.int64, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.UINT32 -> FieldRuleSet(
                type = RuleType.UINT32,
                ignore = ignoreMode,
                message = message,
                numeric = convertUInt32Rules(c.uint32, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.UINT64 -> FieldRuleSet(
                type = RuleType.UINT64,
                ignore = ignoreMode,
                message = message,
                numeric = convertUInt64Rules(c.uint64, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.SINT32 -> FieldRuleSet(
                type = RuleType.SINT32,
                ignore = ignoreMode,
                message = message,
                numeric = convertSInt32Rules(c.sint32, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.SINT64 -> FieldRuleSet(
                type = RuleType.SINT64,
                ignore = ignoreMode,
                message = message,
                numeric = convertSInt64Rules(c.sint64, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.FIXED32 -> FieldRuleSet(
                type = RuleType.FIXED32,
                ignore = ignoreMode,
                message = message,
                numeric = convertFixed32Rules(c.fixed32, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.FIXED64 -> FieldRuleSet(
                type = RuleType.FIXED64,
                ignore = ignoreMode,
                message = message,
                numeric = convertFixed64Rules(c.fixed64, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.SFIXED32 -> FieldRuleSet(
                type = RuleType.SFIXED32,
                ignore = ignoreMode,
                message = message,
                numeric = convertSFixed32Rules(c.sfixed32, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.SFIXED64 -> FieldRuleSet(
                type = RuleType.SFIXED64,
                ignore = ignoreMode,
                message = message,
                numeric = convertSFixed64Rules(c.sfixed64, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.FLOAT -> FieldRuleSet(
                type = RuleType.FLOAT,
                ignore = ignoreMode,
                message = message,
                numeric = convertFloatRules(c.float, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.DOUBLE -> FieldRuleSet(
                type = RuleType.DOUBLE,
                ignore = ignoreMode,
                message = message,
                numeric = convertDoubleRules(c.double, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.BOOL -> FieldRuleSet(
                type = RuleType.BOOL,
                ignore = ignoreMode,
                message = message,
                bool = BoolRuleSet(
                    const = if (c.bool.hasConst()) c.bool.const else null,
                ),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.ENUM -> FieldRuleSet(
                type = RuleType.ENUM,
                ignore = ignoreMode,
                message = message,
                enum = EnumRuleSet(
                    const = if (c.enum.hasConst()) c.enum.const else null,
                    definedOnly = c.enum.hasDefinedOnly() && c.enum.definedOnly,
                    inList = c.enum.inList.toList(),
                    notInList = c.enum.notInList.toList(),
                ),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.REPEATED -> FieldRuleSet(
                type = RuleType.REPEATED,
                ignore = ignoreMode,
                message = message,
                repeated = convertRepeatedRules(c.repeated, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.MAP -> FieldRuleSet(
                type = RuleType.MAP,
                ignore = ignoreMode,
                message = message,
                map = convertMapRules(c.map, ignoreMode),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.DURATION -> FieldRuleSet(
                type = RuleType.DURATION,
                ignore = ignoreMode,
                message = message,
                duration = convertDurationRules(c.duration),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.TIMESTAMP -> FieldRuleSet(
                type = RuleType.TIMESTAMP,
                ignore = ignoreMode,
                message = message,
                timestamp = convertTimestampRules(c.timestamp),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.ANY -> FieldRuleSet(
                type = RuleType.ANY,
                ignore = ignoreMode,
                message = message,
                any = AnyRuleSet(
                    required = isRequired,
                    inList = c.any.inList.toList(),
                    notInList = c.any.notInList.toList(),
                ),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            FieldRules.TypeCase.FIELD_MASK -> FieldRuleSet(
                type = RuleType.FIELD_MASK,
                ignore = ignoreMode,
                message = message,
                fieldMask = FieldMaskRuleSet(
                    constPaths = if (c.fieldMask.hasConst()) c.fieldMask.const.pathsList.sorted() else null,
                    inList = c.fieldMask.inList.toList(),
                    notInList = c.fieldMask.notInList.toList(),
                ),
                celRules = celRules,
                predefinedCelRules = predefinedCelRules,
            )
            else -> {
                if (message != null || celRules.isNotEmpty() || predefinedCelRules.isNotEmpty()) {
                    FieldRuleSet(
                        type = RuleType.NONE,
                        ignore = ignoreMode,
                        message = message,
                        celRules = celRules,
                        predefinedCelRules = predefinedCelRules,
                    )
                } else null
            }
        }
    }

    // ── String ──

    private fun convertStringRules(r: build.buf.validate.StringRules, ignoreMode: IgnoreMode): StringRuleSet {
        val strict = if (r.hasStrict()) r.strict else true
        val wellKnown = when (r.wellKnownCase) {
            build.buf.validate.StringRules.WellKnownCase.EMAIL -> if (r.email) StringWellKnown.EMAIL else null
            build.buf.validate.StringRules.WellKnownCase.URI -> if (r.uri) StringWellKnown.URI else null
            build.buf.validate.StringRules.WellKnownCase.URI_REF -> if (r.uriRef) StringWellKnown.URI_REF else null
            build.buf.validate.StringRules.WellKnownCase.UUID -> if (r.uuid) StringWellKnown.UUID else null
            build.buf.validate.StringRules.WellKnownCase.HOSTNAME -> if (r.hostname) StringWellKnown.HOSTNAME else null
            build.buf.validate.StringRules.WellKnownCase.IP -> if (r.ip) StringWellKnown.IP else null
            build.buf.validate.StringRules.WellKnownCase.IPV4 -> if (r.ipv4) StringWellKnown.IPV4 else null
            build.buf.validate.StringRules.WellKnownCase.IPV6 -> if (r.ipv6) StringWellKnown.IPV6 else null
            build.buf.validate.StringRules.WellKnownCase.ADDRESS -> if (r.address) StringWellKnown.ADDRESS else null
            build.buf.validate.StringRules.WellKnownCase.WELL_KNOWN_REGEX -> when (r.wellKnownRegex) {
                build.buf.validate.KnownRegex.KNOWN_REGEX_HTTP_HEADER_NAME -> StringWellKnown.HTTP_HEADER_NAME
                build.buf.validate.KnownRegex.KNOWN_REGEX_HTTP_HEADER_VALUE -> StringWellKnown.HTTP_HEADER_VALUE
                else -> null
            }
            build.buf.validate.StringRules.WellKnownCase.HOST_AND_PORT -> if (r.hostAndPort) StringWellKnown.HOST_AND_PORT else null
build.buf.validate.StringRules.WellKnownCase.TUUID -> if (r.tuuid) StringWellKnown.TUUID else null
            build.buf.validate.StringRules.WellKnownCase.ULID -> if (r.ulid) StringWellKnown.ULID else null
            build.buf.validate.StringRules.WellKnownCase.IP_WITH_PREFIXLEN -> if (r.ipWithPrefixlen) StringWellKnown.IP_WITH_PREFIXLEN else null
            build.buf.validate.StringRules.WellKnownCase.IPV4_WITH_PREFIXLEN -> if (r.ipv4WithPrefixlen) StringWellKnown.IPV4_WITH_PREFIXLEN else null
            build.buf.validate.StringRules.WellKnownCase.IPV6_WITH_PREFIXLEN -> if (r.ipv6WithPrefixlen) StringWellKnown.IPV6_WITH_PREFIXLEN else null
            build.buf.validate.StringRules.WellKnownCase.IP_PREFIX -> if (r.ipPrefix) StringWellKnown.IP_PREFIX else null
            build.buf.validate.StringRules.WellKnownCase.IPV4_PREFIX -> if (r.ipv4Prefix) StringWellKnown.IPV4_PREFIX else null
            build.buf.validate.StringRules.WellKnownCase.IPV6_PREFIX -> if (r.ipv6Prefix) StringWellKnown.IPV6_PREFIX else null
            build.buf.validate.StringRules.WellKnownCase.PROTOBUF_FQN -> if (r.protobufFqn) StringWellKnown.PROTOBUF_FQN else null
            build.buf.validate.StringRules.WellKnownCase.PROTOBUF_DOT_FQN -> if (r.protobufDotFqn) StringWellKnown.PROTOBUF_DOT_FQN else null
            else -> null
        }
        return StringRuleSet(
            const = if (r.hasConst()) r.const else null,
            len = if (r.hasLen()) r.len.toLong() else null,
            minLen = if (r.hasMinLen()) r.minLen.toLong() else null,
            maxLen = if (r.hasMaxLen()) r.maxLen.toLong() else null,
            lenBytes = if (r.hasLenBytes()) r.lenBytes.toLong() else null,
            minBytes = if (r.hasMinBytes()) r.minBytes.toLong() else null,
            maxBytes = if (r.hasMaxBytes()) r.maxBytes.toLong() else null,
            pattern = if (r.hasPattern()) r.pattern else null,
            prefix = if (r.hasPrefix()) r.prefix else null,
            suffix = if (r.hasSuffix()) r.suffix else null,
            contains = if (r.hasContains()) r.contains else null,
            notContains = if (r.hasNotContains()) r.notContains else null,
            inList = r.inList.toList(),
            notInList = r.notInList.toList(),
            ignore = ignoreMode,
            wellKnown = wellKnown,
            strict = strict,
        )
    }

    // ── Bytes ──

    private fun convertBytesRules(r: build.buf.validate.BytesRules, ignoreMode: IgnoreMode): BytesRuleSet {
        val wellKnown = when (r.wellKnownCase) {
            build.buf.validate.BytesRules.WellKnownCase.IP -> if (r.ip) BytesWellKnown.IP else null
            build.buf.validate.BytesRules.WellKnownCase.IPV4 -> if (r.ipv4) BytesWellKnown.IPV4 else null
            build.buf.validate.BytesRules.WellKnownCase.IPV6 -> if (r.ipv6) BytesWellKnown.IPV6 else null
            build.buf.validate.BytesRules.WellKnownCase.UUID -> if (r.uuid) BytesWellKnown.UUID else null
            else -> null
        }
        return BytesRuleSet(
            const = if (r.hasConst()) r.const.toByteArray() else null,
            len = if (r.hasLen()) r.len.toLong() else null,
            minLen = if (r.hasMinLen()) r.minLen.toLong() else null,
            maxLen = if (r.hasMaxLen()) r.maxLen.toLong() else null,
            pattern = if (r.hasPattern()) r.pattern else null,
            prefix = if (r.hasPrefix()) r.prefix.toByteArray() else null,
            suffix = if (r.hasSuffix()) r.suffix.toByteArray() else null,
            contains = if (r.hasContains()) r.contains.toByteArray() else null,
            inList = r.inList.map { it.toByteArray() },
            notInList = r.notInList.map { it.toByteArray() },
            ignore = ignoreMode,
            wellKnown = wellKnown,
        )
    }

    // ── Numeric converters ──

    private fun convertInt32Rules(r: build.buf.validate.Int32Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "int32",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertInt64Rules(r: build.buf.validate.Int64Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "int64",
        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = ignoreMode,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertUInt32Rules(r: build.buf.validate.UInt32Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "uint32",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertUInt64Rules(r: build.buf.validate.UInt64Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "uint64",
        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = ignoreMode,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSInt32Rules(r: build.buf.validate.SInt32Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "sint32",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSInt64Rules(r: build.buf.validate.SInt64Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "sint64",
        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = ignoreMode,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFixed32Rules(r: build.buf.validate.Fixed32Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "fixed32",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFixed64Rules(r: build.buf.validate.Fixed64Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "fixed64",
        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = ignoreMode,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSFixed32Rules(r: build.buf.validate.SFixed32Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "sfixed32",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertSFixed64Rules(r: build.buf.validate.SFixed64Rules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "sfixed64",
        constVal = if (r.hasConst()) "${r.const}L" else null,
        ltVal = if (r.hasLt()) "${r.lt}L" else null,
        lteVal = if (r.hasLte()) "${r.lte}L" else null,
        gtVal = if (r.hasGt()) "${r.gt}L" else null,
        gteVal = if (r.hasGte()) "${r.gte}L" else null,
        inList = r.inList.map { "${it}L" },
        notInList = r.notInList.map { "${it}L" },
        ignore = ignoreMode,
        zeroLiteral = "0L",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
    )

    private fun convertFloatRules(r: build.buf.validate.FloatRules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "float",
        constVal = if (r.hasConst()) "${r.const}f" else null,
        ltVal = if (r.hasLt()) "${r.lt}f" else null,
        lteVal = if (r.hasLte()) "${r.lte}f" else null,
        gtVal = if (r.hasGt()) "${r.gt}f" else null,
        gteVal = if (r.hasGte()) "${r.gte}f" else null,
        inList = r.inList.map { "${it}f" },
        notInList = r.notInList.map { "${it}f" },
        ignore = ignoreMode,
        zeroLiteral = "0.0f",
        ltRaw = if (r.hasLt()) r.lt.toDouble() else null,
        lteRaw = if (r.hasLte()) r.lte.toDouble() else null,
        gtRaw = if (r.hasGt()) r.gt.toDouble() else null,
        gteRaw = if (r.hasGte()) r.gte.toDouble() else null,
        finite = r.finite,
    )

    private fun convertDoubleRules(r: build.buf.validate.DoubleRules, ignoreMode: IgnoreMode) = NumericRuleSet(
        rulePrefix = "double",
        constVal = if (r.hasConst()) "${r.const}" else null,
        ltVal = if (r.hasLt()) "${r.lt}" else null,
        lteVal = if (r.hasLte()) "${r.lte}" else null,
        gtVal = if (r.hasGt()) "${r.gt}" else null,
        gteVal = if (r.hasGte()) "${r.gte}" else null,
        inList = r.inList.map { "$it" },
        notInList = r.notInList.map { "$it" },
        ignore = ignoreMode,
        zeroLiteral = "0.0",
        ltRaw = if (r.hasLt()) r.lt else null,
        lteRaw = if (r.hasLte()) r.lte else null,
        gtRaw = if (r.hasGt()) r.gt else null,
        gteRaw = if (r.hasGte()) r.gte else null,
        finite = r.finite,
    )

    // ── Repeated ──

    private fun convertRepeatedRules(r: build.buf.validate.RepeatedRules, ignoreMode: IgnoreMode): RepeatedRuleSet {
        return RepeatedRuleSet(
            minItems = if (r.hasMinItems()) r.minItems.toLong() else null,
            maxItems = if (r.hasMaxItems()) r.maxItems.toLong() else null,
            unique = r.hasUnique() && r.unique,
            ignore = ignoreMode,
            items = if (r.hasItems()) convertFieldRules(r.items) else null,
        )
    }

    // ── Map ──

    private fun convertMapRules(r: build.buf.validate.MapRules, ignoreMode: IgnoreMode): MapRuleSet = MapRuleSet(
        minPairs = if (r.hasMinPairs()) r.minPairs.toLong() else null,
        maxPairs = if (r.hasMaxPairs()) r.maxPairs.toLong() else null,
        ignore = ignoreMode,
        keys = if (r.hasKeys()) convertFieldRules(r.keys) else null,
        values = if (r.hasValues()) convertFieldRules(r.values) else null,
    )

    // ── Duration ──

    private fun convertDurationRules(r: build.buf.validate.DurationRules): DurationRuleSet = DurationRuleSet(
        required = false,
        const = if (r.hasConst()) DurationValue(r.const.seconds, r.const.nanos) else null,
        lt = if (r.hasLt()) DurationValue(r.lt.seconds, r.lt.nanos) else null,
        lte = if (r.hasLte()) DurationValue(r.lte.seconds, r.lte.nanos) else null,
        gt = if (r.hasGt()) DurationValue(r.gt.seconds, r.gt.nanos) else null,
        gte = if (r.hasGte()) DurationValue(r.gte.seconds, r.gte.nanos) else null,
        inList = r.inList.map { DurationValue(it.seconds, it.nanos) },
        notInList = r.notInList.map { DurationValue(it.seconds, it.nanos) },
    )

    // ── Timestamp ──

    private fun convertTimestampRules(r: build.buf.validate.TimestampRules): TimestampRuleSet = TimestampRuleSet(
        required = false,
        const = if (r.hasConst()) DurationValue(r.const.seconds, r.const.nanos) else null,
        lt = if (r.hasLt()) DurationValue(r.lt.seconds, r.lt.nanos) else null,
        lte = if (r.hasLte()) DurationValue(r.lte.seconds, r.lte.nanos) else null,
        gt = if (r.hasGt()) DurationValue(r.gt.seconds, r.gt.nanos) else null,
        gte = if (r.hasGte()) DurationValue(r.gte.seconds, r.gte.nanos) else null,
        ltNow = r.hasLtNow() && r.ltNow,
        gtNow = r.hasGtNow() && r.gtNow,
        within = if (r.hasWithin()) DurationValue(r.within.seconds, r.within.nanos) else null,
    )

    // ── Predefined CEL Rule Extraction ──

    /**
     * Extracts predefined CEL rules from extensions on the type-specific rule messages.
     *
     * When a user writes:
     *   `(buf.validate.field).uint32.(uint32_even_proto2) = true`
     *
     * This sets an extension field (uint32_even_proto2) on the UInt32Rules message.
     * That extension field has `(buf.validate.predefined).cel` annotations.
     *
     * We iterate over all set fields (including extensions) on the type-specific rule message,
     * look for the `predefined` annotation on each extension field, and extract the CEL rules.
     */
    private fun extractPredefinedCelRules(c: FieldRules): List<PredefinedCelRule> {
        val typeMessage: Message? = when (c.typeCase) {
            FieldRules.TypeCase.FLOAT -> c.float
            FieldRules.TypeCase.DOUBLE -> c.double
            FieldRules.TypeCase.INT32 -> c.int32
            FieldRules.TypeCase.INT64 -> c.int64
            FieldRules.TypeCase.UINT32 -> c.uint32
            FieldRules.TypeCase.UINT64 -> c.uint64
            FieldRules.TypeCase.SINT32 -> c.sint32
            FieldRules.TypeCase.SINT64 -> c.sint64
            FieldRules.TypeCase.FIXED32 -> c.fixed32
            FieldRules.TypeCase.FIXED64 -> c.fixed64
            FieldRules.TypeCase.SFIXED32 -> c.sfixed32
            FieldRules.TypeCase.SFIXED64 -> c.sfixed64
            FieldRules.TypeCase.BOOL -> c.bool
            FieldRules.TypeCase.STRING -> c.string
            FieldRules.TypeCase.BYTES -> c.bytes
            FieldRules.TypeCase.ENUM -> c.enum
            FieldRules.TypeCase.REPEATED -> c.repeated
            FieldRules.TypeCase.MAP -> c.map
            FieldRules.TypeCase.DURATION -> c.duration
            FieldRules.TypeCase.TIMESTAMP -> c.timestamp
            else -> null
        }
        if (typeMessage == null) return emptyList()

        val result = mutableListOf<PredefinedCelRule>()

        // If the type message has unknown fields, try re-parsing with dynamic descriptors
        // to resolve custom predefined extensions
        val effectiveMessage = if (typeMessage.unknownFields.serializedSize > 0 && dynamicRuleDescriptors.isNotEmpty()) {
            val typeName = typeMessage.descriptorForType.fullName
            val dynDesc = dynamicRuleDescriptors[typeName]
            if (dynDesc != null) {
                try {
                    DynamicMessage.parseFrom(dynDesc, typeMessage.toByteString(), dynamicExtensionRegistry)
                } catch (_: Exception) {
                    typeMessage
                }
            } else typeMessage
        } else typeMessage

        // Iterate all fields (including extensions) that are set on the type-specific rule message
        for ((fieldDesc, fieldValue) in effectiveMessage.allFields) {
            // Only look at extension fields (standard fields like const, lt, etc. are not predefined)
            if (!fieldDesc.isExtension) continue

            // Get the predefined annotation from this extension field's options
            val fieldOptions = fieldDesc.toProto().options ?: continue
            if (!fieldOptions.hasExtension(ValidateProto.predefined)) continue
            val predefined: PredefinedRules = fieldOptions.getExtension(ValidateProto.predefined)
            if (predefined.celCount == 0) continue

            // Convert the extension field value to a Kotlin literal
            val ruleValueLiteral = fieldValueToKotlinLiteral(fieldDesc, fieldValue)

            for (rule in predefined.celList) {
                result.add(PredefinedCelRule(
                    id = rule.id ?: rule.expression ?: "",
                    expression = rule.expression ?: "",
                    message = rule.message ?: "",
                    ruleValue = ruleValueLiteral,
                ))
            }
        }

        return result
    }

    /**
     * Converts a protobuf field value to a Kotlin literal expression string.
     * This is used for the `rule` binding in predefined CEL expressions.
     */
    private fun fieldValueToKotlinLiteral(fieldDesc: Descriptors.FieldDescriptor, value: Any?): String {
        if (value == null) return "null"

        // Repeated field → list literal
        if (fieldDesc.isRepeated) {
            val list = value as List<*>
            val elements = list.map { scalarToKotlinLiteral(fieldDesc, it) }
            return "listOf(${elements.joinToString(", ")})"
        }

        return scalarToKotlinLiteral(fieldDesc, value)
    }

    private fun scalarToKotlinLiteral(fieldDesc: Descriptors.FieldDescriptor, value: Any?): String {
        if (value == null) return "null"
        return when (fieldDesc.type) {
            Descriptors.FieldDescriptor.Type.BOOL -> value.toString()
            Descriptors.FieldDescriptor.Type.INT32, Descriptors.FieldDescriptor.Type.SINT32,
            Descriptors.FieldDescriptor.Type.SFIXED32 -> value.toString()
            Descriptors.FieldDescriptor.Type.INT64, Descriptors.FieldDescriptor.Type.SINT64,
            Descriptors.FieldDescriptor.Type.SFIXED64 -> "${value}L"
            Descriptors.FieldDescriptor.Type.UINT32, Descriptors.FieldDescriptor.Type.FIXED32 -> value.toString()
            Descriptors.FieldDescriptor.Type.UINT64, Descriptors.FieldDescriptor.Type.FIXED64 -> "${value}L"
            Descriptors.FieldDescriptor.Type.FLOAT -> "${value}f"
            Descriptors.FieldDescriptor.Type.DOUBLE -> value.toString()
            Descriptors.FieldDescriptor.Type.STRING -> "\"${escapeForKotlinString(value as String)}\""
            Descriptors.FieldDescriptor.Type.BYTES -> {
                val bytes = value as ByteString
                bytesToKotlinLiteral(bytes.toByteArray())
            }
            Descriptors.FieldDescriptor.Type.ENUM -> {
                val enumVal = value as Descriptors.EnumValueDescriptor
                "${enumVal.number}"
            }
            Descriptors.FieldDescriptor.Type.MESSAGE -> {
                // For message-typed extension fields (e.g. Int64Value wrappers),
                // try to extract the value field
                val msg = value as Message
                val valueField = msg.descriptorForType.findFieldByName("value")
                if (valueField != null && msg.hasField(valueField)) {
                    scalarToKotlinLiteral(valueField, msg.getField(valueField))
                } else {
                    "null /* unsupported message type */"
                }
            }
            else -> value.toString()
        }
    }
}

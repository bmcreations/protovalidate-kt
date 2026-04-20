package dev.bmcreations.protovalidate.plugin

import dev.bmcreations.protovalidate.plugin.cel.*
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label

data class EmitContext(
    val sb: StringBuilder,
    val indent: String = "    ",
    val validatedTypes: Map<String, String> = emptyMap(),
    val neededImports: MutableSet<String> = mutableSetOf(),
    /** File syntax: "proto2", "proto3", or "editions" */
    val fileSyntax: String = "proto3",
    /** All nested types in the current message (for resolving map entry value types) */
    val nestedTypes: List<com.google.protobuf.DescriptorProtos.DescriptorProto> = emptyList(),
)

object FieldEmitter {

    /**
     * Strips backtick escaping from an accessor so it can be combined with a suffix.
     * e.g. `` `val` `` → `val`, then `val` + `List` → `valList`
     */
    private fun rawAccessor(accessor: String): String = accessor.removeSurrounding("`")

    fun emit(
        fieldProto: FieldDescriptorProto,
        ruleSet: FieldRuleSet,
        accessorPrefix: String,
        ctx: EmitContext
    ) {
        val rawFieldName = snakeToCamelLower(fieldProto.name)
        val fieldName = escapeIfKeyword(rawFieldName)
        val accessor = if (accessorPrefix.isEmpty()) fieldName else "$accessorPrefix.$fieldName"
        val quotedField = "\"${fieldProto.name}\""
        val indent = ctx.indent

        // Message-level rules (required / skip)
        if (ruleSet.message != null) {
            if (ruleSet.message.skip) return
        }

        // IGNORE_ALWAYS: skip all validation for this field
        if (ruleSet.ignore == IgnoreMode.ALWAYS) return

        val isMessageOrGroup = fieldProto.type == Type.TYPE_MESSAGE || fieldProto.type == Type.TYPE_GROUP
        val hasOtherRules = ruleSet.type != RuleType.NONE ||
            (isMessageOrGroup && fieldProto.label != Label.LABEL_REPEATED) ||
            ruleSet.celRules.isNotEmpty() || ruleSet.predefinedCelRules.isNotEmpty()

        // Presence-aware ignore: for fields with presence tracking, wrap in has*() guard.
        // UNSPECIFIED: for presence-tracking fields, skip if unset (this is the default proto behavior)
        // IF_UNPOPULATED: same as UNSPECIFIED for presence-tracking fields
        // IF_DEFAULT_VALUE: skip if zero/default value (handled by type-specific emitters for non-presence fields)
        val needsPresenceGuard = ruleSet.ignore == IgnoreMode.UNSPECIFIED ||
            ruleSet.ignore == IgnoreMode.IF_UNPOPULATED ||
            ruleSet.ignore == IgnoreMode.IF_DEFAULT_VALUE
        val hasTypeRequired = ruleSet.duration?.required == true ||
            ruleSet.timestamp?.required == true || ruleSet.any?.required == true
        val hasIgnorePresenceGuard = needsPresenceGuard &&
            fieldProto.label != Label.LABEL_REPEATED &&
            fieldHasPresence(fieldProto, ctx.fileSyntax) &&
            ruleSet.message?.required != true && // don't double-guard with required
            !hasTypeRequired // type-specific required needs to be outside the guard

        if (hasIgnorePresenceGuard) {
            val hasExpr = if (accessorPrefix.isEmpty()) "has${snakeToCamel(fieldProto.name)}()"
                else "$accessorPrefix.has${snakeToCamel(fieldProto.name)}()"
            // For IF_DEFAULT_VALUE on oneof fields, also check the value isn't zero/empty
            val isOneofField = fieldProto.hasOneofIndex() && !fieldProto.proto3Optional
            val guardExpr = if (isOneofField && ruleSet.ignore == IgnoreMode.IF_DEFAULT_VALUE) {
                val nonZeroCheck = scalarZeroCheck(fieldProto, accessor)
                if (nonZeroCheck != null) "$hasExpr && $nonZeroCheck" else hasExpr
            } else {
                hasExpr
            }
            ctx.sb.appendLine("${indent}if ($guardExpr) {")
        }

        // From here on, use effectiveIndent/effectiveCtx which account for the ignore guard
        val effectiveIndent = if (hasIgnorePresenceGuard) "$indent    " else indent
        val effectiveCtx = if (hasIgnorePresenceGuard) ctx.copy(indent = effectiveIndent) else ctx

        // In PGV mode (requiredOnlyExplicit), required on proto3 optional message fields is a no-op
        // (PGV treats 'optional' + required as "validate if present" without presence enforcement)
        val skipRequired = ruleSet.message?.requiredOnlyExplicit == true &&
            fieldProto.proto3Optional &&
            fieldProto.type == Type.TYPE_MESSAGE &&
            effectiveCtx.fileSyntax == "proto3"

        if (ruleSet.message?.required == true && !skipRequired) {
            if (fieldProto.label == Label.LABEL_REPEATED) {
                // Repeated/map fields: required means non-empty
                val isMap = fieldProto.type == Type.TYPE_MESSAGE && fieldProto.typeName.endsWith("Entry")
                val listAccessor = "${rawAccessor(accessor)}${if (isMap) "Map" else "List"}"
                effectiveCtx.sb.appendLine("${effectiveIndent}Validators.checkRequired(${listAccessor}.isNotEmpty(), $quotedField)?.let { violations += it }")
                if (hasOtherRules) {
                    // Short-circuit: only validate further if non-empty
                    effectiveCtx.sb.appendLine("${effectiveIndent}if (${listAccessor}.isNotEmpty()) {")
                }
            } else if (fieldHasPresence(fieldProto, effectiveCtx.fileSyntax)) {
                val hasAccessor = if (accessorPrefix.isEmpty()) {
                    "has${snakeToCamel(fieldProto.name)}()"
                } else {
                    "$accessorPrefix.has${snakeToCamel(fieldProto.name)}()"
                }
                effectiveCtx.sb.appendLine("${effectiveIndent}Validators.checkRequired($hasAccessor, $quotedField)?.let { violations += it }")
                if (hasOtherRules) {
                    effectiveCtx.sb.appendLine("${effectiveIndent}if ($hasAccessor) {")
                }
            } else {
                // Implicit presence scalar: required means not the zero value
                val zeroCheck = scalarZeroCheck(fieldProto, accessor)
                if (zeroCheck != null) {
                    effectiveCtx.sb.appendLine("${effectiveIndent}Validators.checkRequired($zeroCheck, $quotedField)?.let { violations += it }")
                    if (hasOtherRules) {
                        effectiveCtx.sb.appendLine("${effectiveIndent}if ($zeroCheck) {")
                    }
                }
            }
        }

        val requiredGuard = ruleSet.message?.required == true && !skipRequired && hasOtherRules
        val innerIndent = if (requiredGuard) "$effectiveIndent    " else effectiveIndent
        val innerCtx = if (requiredGuard) effectiveCtx.copy(indent = innerIndent) else effectiveCtx

        // Recursive message/group validation (if the nested type has a validator)
        if (isMessageOrGroup &&
            fieldProto.label != Label.LABEL_REPEATED &&
            ruleSet.message?.skip != true
        ) {
            val typeName = fieldProto.typeName
            val targetPkg = innerCtx.validatedTypes[typeName]
            if (targetPkg != null) {
                val hasAccessor = if (accessorPrefix.isEmpty()) {
                    "has${snakeToCamel(fieldProto.name)}()"
                } else {
                    "$accessorPrefix.has${snakeToCamel(fieldProto.name)}()"
                }
                // Skip the has-check if we're already inside a required guard or presence guard
                if ((!requiredGuard && !hasIgnorePresenceGuard) || !fieldHasPresence(fieldProto, innerCtx.fileSyntax)) {
                    innerCtx.sb.appendLine("${innerIndent}if ($hasAccessor) {")
                    innerCtx.sb.appendLine("$innerIndent    $accessor.validate().let { result ->")
                    innerCtx.sb.appendLine("$innerIndent        if (result is ValidationResult.Invalid) {")
                    innerCtx.sb.appendLine($$"$$innerIndent            violations += result.violations.map { it.copy(field = if (it.field.isEmpty()) \"$${fieldProto.name}\" else \"$${fieldProto.name}.${it.field}\") }")
                    innerCtx.sb.appendLine("$innerIndent        }")
                    innerCtx.sb.appendLine("$innerIndent    }")
                    innerCtx.sb.appendLine("${innerIndent}}")
                } else {
                    innerCtx.sb.appendLine("$innerIndent$accessor.validate().let { result ->")
                    innerCtx.sb.appendLine("$innerIndent    if (result is ValidationResult.Invalid) {")
                    innerCtx.sb.appendLine($$"$$innerIndent        violations += result.violations.map { it.copy(field = if (it.field.isEmpty()) \"$${fieldProto.name}\" else \"$${fieldProto.name}.${it.field}\") }")
                    innerCtx.sb.appendLine("$innerIndent    }")
                    innerCtx.sb.appendLine("$innerIndent}")
                }
                innerCtx.neededImports.add(targetPkg)
            }
        }

        // Check if this is a repeated (list) field — handle before type dispatch
        if (fieldProto.label == Label.LABEL_REPEATED) {
            // Detect map fields from the proto type name (map entries are synthetic message types
            // whose name ends with "Entry")
            val isMapField = ruleSet.type == RuleType.MAP ||
                (fieldProto.type == Type.TYPE_MESSAGE && fieldProto.typeName.endsWith("Entry"))
            if (isMapField && ruleSet.map != null) {
                // Resolve key/value proto types from the map entry message
                val entryName = fieldProto.typeName.substringAfterLast(".")
                val entryType = innerCtx.nestedTypes.find { it.name == entryName }
                val mapKeyProtoType = entryType?.fieldList?.find { it.number == 1 }?.type
                val mapValueProtoType = entryType?.fieldList?.find { it.number == 2 }?.type
                emitMapRules(ruleSet.map, accessor, quotedField, innerCtx, mapKeyProtoType, mapValueProtoType)
            } else if (!isMapField) {
                emitRepeatedRules(ruleSet, accessor, quotedField, fieldProto, innerCtx)
            }
            // Emit CEL rules for the repeated/map field itself
            // For these, `this` in CEL refers to the list/map accessor
            val celFieldType = if (isMapField) CelFieldType.MAP else CelFieldType.REPEATED
            val celAccessor = if (isMapField) {
                "${rawAccessor(accessor)}Map"
            } else {
                "${rawAccessor(accessor)}List"
            }
            val celRuleSet = ruleSet.copy(type = if (isMapField) RuleType.MAP else RuleType.REPEATED)

            // Determine map value type or repeated element type from nested types
            val mapValType: CelFieldType
            val elemType: CelFieldType
            if (isMapField) {
                mapValType = resolveMapValueType(fieldProto, innerCtx.nestedTypes)
                elemType = CelFieldType.UNKNOWN
            } else {
                mapValType = CelFieldType.UNKNOWN
                elemType = protoTypeToCelFieldType(fieldProto.type)
            }
            emitCelRules(celRuleSet, celAccessor, quotedField, innerCtx, fieldProto.type, mapValType, elemType)
            if (requiredGuard) effectiveCtx.sb.appendLine("${effectiveIndent}}")
            if (hasIgnorePresenceGuard) ctx.sb.appendLine("${indent}}")
            return
        }

        // WKT wrapper unwrapping: if the field is a message wrapping a scalar, unwrap to .value
        val isWktWrapper = fieldProto.type == Type.TYPE_MESSAGE && isWktWrapperType(fieldProto.typeName)
        val effectiveAccessor = if (isWktWrapper) "$accessor.value" else accessor
        val wktNeedsGuard = isWktWrapper && !requiredGuard && !hasIgnorePresenceGuard
        val wktCtx = if (isWktWrapper) {
            if (wktNeedsGuard) {
                // Wrap scalar checks inside a has-check for the wrapper message
                val hasAccessor = if (accessorPrefix.isEmpty()) {
                    "has${snakeToCamel(fieldProto.name)}()"
                } else {
                    "$accessorPrefix.has${snakeToCamel(fieldProto.name)}()"
                }
                innerCtx.sb.appendLine("${innerIndent}if ($hasAccessor) {")
                innerCtx.copy(indent = "$innerIndent    ")
            } else {
                // Already inside a required guard that checks presence
                innerCtx
            }
        } else null

        // When the presence guard already handles ignore at the field level,
        // suppress the zero-value guard in type-specific emitters
        val suppressInnerIgnore = hasIgnorePresenceGuard

        when (ruleSet.type) {
            RuleType.STRING -> ruleSet.string?.let {
                val rules = if (suppressInnerIgnore) it.copy(ignore = IgnoreMode.UNSPECIFIED) else it
                emitStringRules(rules, effectiveAccessor, quotedField, wktCtx ?: innerCtx)
            }
            RuleType.BYTES -> ruleSet.bytes?.let {
                val rules = if (suppressInnerIgnore) it.copy(ignore = IgnoreMode.UNSPECIFIED) else it
                emitBytesRules(rules, effectiveAccessor, quotedField, wktCtx ?: innerCtx)
            }
            RuleType.INT32, RuleType.INT64, RuleType.UINT32, RuleType.UINT64,
            RuleType.SINT32, RuleType.SINT64, RuleType.FIXED32, RuleType.FIXED64,
            RuleType.SFIXED32, RuleType.SFIXED64, RuleType.FLOAT, RuleType.DOUBLE ->
                ruleSet.numeric?.let {
                    val rules = if (suppressInnerIgnore) it.copy(ignore = IgnoreMode.UNSPECIFIED) else it
                    emitNumericRules(rules, effectiveAccessor, quotedField, wktCtx ?: innerCtx)
                }
            RuleType.BOOL -> ruleSet.bool?.let { emitBoolRules(it, effectiveAccessor, quotedField, wktCtx ?: innerCtx) }
            RuleType.ENUM -> ruleSet.enum?.let { emitEnumRules(it, effectiveAccessor, quotedField, wktCtx ?: innerCtx) }
            RuleType.REPEATED -> emitRepeatedRules(ruleSet, accessor, quotedField, fieldProto, innerCtx)
            RuleType.MAP -> ruleSet.map?.let {
                val entryName = fieldProto.typeName.substringAfterLast(".")
                val entryType = innerCtx.nestedTypes.find { it.name == entryName }
                val mapKeyProtoType = entryType?.fieldList?.find { it.number == 1 }?.type
                val mapValueProtoType = entryType?.fieldList?.find { it.number == 2 }?.type
                emitMapRules(it, accessor, quotedField, innerCtx, mapKeyProtoType, mapValueProtoType)
            }
            RuleType.DURATION -> ruleSet.duration?.let { emitDurationRules(it, accessor, quotedField, innerCtx) }
            RuleType.TIMESTAMP -> ruleSet.timestamp?.let { emitTimestampRules(it, accessor, quotedField, innerCtx) }
            RuleType.ANY -> ruleSet.any?.let { emitAnyRules(it, accessor, quotedField, innerCtx) }
            RuleType.FIELD_MASK -> ruleSet.fieldMask?.let { emitFieldMaskRules(it, accessor, quotedField, innerCtx) }
            else -> {}
        }

        // Emit CEL rules (field-level and predefined)
        val celCtx = wktCtx ?: innerCtx
        emitCelRules(ruleSet, effectiveAccessor, quotedField, celCtx, fieldProto.type)

        if (wktNeedsGuard) {
            innerCtx.sb.appendLine("${innerIndent}}")
        }

        if (requiredGuard) {
            effectiveCtx.sb.appendLine("${effectiveIndent}}")
        }

        if (hasIgnorePresenceGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    fun emitValueRules(
        ruleSet: FieldRuleSet,
        accessor: String,
        fieldExpr: String,
        ctx: EmitContext,
        protoFieldType: Type? = null,
    ) {
        // IGNORE_ALWAYS: skip all validation for this sub-constraint
        if (ruleSet.ignore == IgnoreMode.ALWAYS) return

        when (ruleSet.type) {
            RuleType.STRING -> ruleSet.string?.let { emitStringRules(it, accessor, fieldExpr, ctx) }
            RuleType.BYTES -> ruleSet.bytes?.let { emitBytesRules(it, accessor, fieldExpr, ctx) }
            RuleType.INT32, RuleType.INT64, RuleType.UINT32, RuleType.UINT64,
            RuleType.SINT32, RuleType.SINT64, RuleType.FIXED32, RuleType.FIXED64,
            RuleType.SFIXED32, RuleType.SFIXED64, RuleType.FLOAT, RuleType.DOUBLE ->
                ruleSet.numeric?.let { emitNumericRules(it, accessor, fieldExpr, ctx) }
            RuleType.BOOL -> ruleSet.bool?.let { emitBoolRules(it, accessor, fieldExpr, ctx) }
            RuleType.ENUM -> ruleSet.enum?.let { emitEnumRules(it, accessor, fieldExpr, ctx) }
            // Duration, Timestamp, and Any items are always present (they come from a repeated list),
            // so we emit their validation without a has-check guard.
            RuleType.DURATION -> ruleSet.duration?.let { emitDurationItemRules(it, accessor, fieldExpr, ctx) }
            RuleType.TIMESTAMP -> ruleSet.timestamp?.let { emitTimestampItemRules(it, accessor, fieldExpr, ctx) }
            RuleType.ANY -> ruleSet.any?.let { emitAnyItemRules(it, accessor, fieldExpr, ctx) }
            else -> {}
        }

        // Emit CEL rules for sub-constraints (repeated items, map keys/values)
        emitCelRules(ruleSet, accessor, fieldExpr, ctx, protoFieldType)
    }

    /**
     * Emits duration validation rules for a repeated-field item (no has-check guard needed
     * since items in a repeated list are always present).
     */
    private fun emitDurationItemRules(rules: DurationRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val durHasLower = rules.gt != null || rules.gte != null
        val durHasUpper = rules.lt != null || rules.lte != null
        val hasIn = rules.inList.isNotEmpty()
        val hasNotIn = rules.notInList.isNotEmpty()
        val hasConst = rules.const != null

        if (!hasConst && !durHasLower && !durHasUpper && !hasIn && !hasNotIn) return

        rules.const?.let { c ->
            ctx.sb.appendLine("${indent}Validators.checkDurationConst($accessor.seconds, $accessor.nanos, ${c.seconds}L, ${c.nanos}, $field)?.let { violations += it }")
        }

        if (durHasLower && durHasUpper) {
            val lower = rules.gt ?: rules.gte!!
            val upper = rules.lt ?: rules.lte!!
            val lowerInclusive = rules.gt == null
            val upperInclusive = rules.lt == null
            ctx.sb.appendLine("${indent}Validators.checkDurationRange($accessor.seconds, $accessor.nanos, ${lower.seconds}L, ${lower.nanos}, ${upper.seconds}L, ${upper.nanos}, $lowerInclusive, $upperInclusive, $field)?.let { violations += it }")
        } else {
            rules.lt?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkDurationLt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.lte?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkDurationLte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.gt?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkDurationGt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.gte?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkDurationGte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
        }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
            ctx.sb.appendLine("${indent}Validators.checkDurationIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
            ctx.sb.appendLine("${indent}Validators.checkDurationNotIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
        }
    }

    /**
     * Emits Any validation rules for a repeated-field item (no has-check guard needed
     * since items in a repeated list are always present).
     */
    private fun emitAnyItemRules(rules: AnyRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${indent}Validators.checkAnyIn($accessor.typeUrl, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${indent}Validators.checkAnyNotIn($accessor.typeUrl, listOf($items), $field)?.let { violations += it }")
        }
    }

    /**
     * Emits Timestamp validation rules for a repeated-field item (no has-check guard needed
     * since items in a repeated list are always present).
     */
    private fun emitTimestampItemRules(rules: TimestampRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val tsHasLower = rules.gt != null || rules.gte != null
        val tsHasUpper = rules.lt != null || rules.lte != null
        val hasChecks = rules.const != null || tsHasLower || tsHasUpper ||
            rules.ltNow || rules.gtNow || rules.within != null ||
            rules.inList.isNotEmpty() || rules.notInList.isNotEmpty()

        if (!hasChecks) return

        rules.const?.let { c ->
            ctx.sb.appendLine("${indent}Validators.checkTimestampConst($accessor.seconds, $accessor.nanos, ${c.seconds}L, ${c.nanos}, $field)?.let { violations += it }")
        }

        if (tsHasLower && tsHasUpper) {
            val lower = rules.gt ?: rules.gte!!
            val upper = rules.lt ?: rules.lte!!
            val lowerInclusive = rules.gt == null
            val upperInclusive = rules.lt == null
            ctx.sb.appendLine("${indent}Validators.checkTimestampRange($accessor.seconds, $accessor.nanos, ${lower.seconds}L, ${lower.nanos}, ${upper.seconds}L, ${upper.nanos}, $lowerInclusive, $upperInclusive, $field)?.let { violations += it }")
        } else {
            rules.lt?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkTimestampLt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.lte?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkTimestampLte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.gt?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkTimestampGt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
            rules.gte?.let { v ->
                ctx.sb.appendLine("${indent}Validators.checkTimestampGte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
            }
        }

        if (rules.ltNow) {
            ctx.sb.appendLine("${indent}Validators.checkTimestampLtNow($accessor.seconds, $accessor.nanos, $field)?.let { violations += it }")
        }
        if (rules.gtNow) {
            ctx.sb.appendLine("${indent}Validators.checkTimestampGtNow($accessor.seconds, $accessor.nanos, $field)?.let { violations += it }")
        }
        rules.within?.let { w ->
            ctx.sb.appendLine("${indent}Validators.checkTimestampWithin($accessor.seconds, $accessor.nanos, ${w.seconds}L, ${w.nanos}, $field)?.let { violations += it }")
        }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
            ctx.sb.appendLine("${indent}Validators.checkTimestampIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
            ctx.sb.appendLine("${indent}Validators.checkTimestampNotIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
        }
    }

    // ── String ──

    private fun emitStringRules(rules: StringRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasIgnoreGuard = rules.ignore == IgnoreMode.IF_UNPOPULATED || rules.ignore == IgnoreMode.IF_DEFAULT_VALUE
        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}if ($accessor.isNotEmpty()) {")
        }
        val i = if (hasIgnoreGuard) "$indent    " else indent

        rules.const?.let { ctx.sb.appendLine("${i}Validators.checkStringConst($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.len?.let { ctx.sb.appendLine("${i}Validators.checkStringLen($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.minLen?.let { ctx.sb.appendLine("${i}Validators.checkStringMinLen($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.maxLen?.let { ctx.sb.appendLine("${i}Validators.checkStringMaxLen($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.lenBytes?.let { ctx.sb.appendLine("${i}Validators.checkStringLenBytes($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.minBytes?.let { ctx.sb.appendLine("${i}Validators.checkStringMinBytes($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.maxBytes?.let { ctx.sb.appendLine("${i}Validators.checkStringMaxBytes($accessor, ${it}L, $field)?.let { violations += it }") }
        rules.pattern?.let { ctx.sb.appendLine("${i}Validators.checkStringPattern($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.prefix?.let { ctx.sb.appendLine("${i}Validators.checkStringPrefix($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.suffix?.let { ctx.sb.appendLine("${i}Validators.checkStringSuffix($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.contains?.let { ctx.sb.appendLine("${i}Validators.checkStringContains($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.notContains?.let { ctx.sb.appendLine("${i}Validators.checkStringNotContains($accessor, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${i}Validators.checkStringIn($accessor, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${i}Validators.checkStringNotIn($accessor, listOf($items), $field)?.let { violations += it }")
        }

        // Well-known types
        when (rules.wellKnown) {
            StringWellKnown.EMAIL -> ctx.sb.appendLine("${i}Validators.checkStringEmail($accessor, $field)?.let { violations += it }")
            StringWellKnown.EMAIL_PGV -> ctx.sb.appendLine("${i}Validators.checkStringEmailPgv($accessor, $field)?.let { violations += it }")
            StringWellKnown.URI -> ctx.sb.appendLine("${i}Validators.checkStringUri($accessor, $field)?.let { violations += it }")
            StringWellKnown.URI_REF -> ctx.sb.appendLine("${i}Validators.checkStringUriRef($accessor, $field)?.let { violations += it }")
            StringWellKnown.UUID -> ctx.sb.appendLine("${i}Validators.checkStringUuid($accessor, $field)?.let { violations += it }")
            StringWellKnown.HOSTNAME -> ctx.sb.appendLine("${i}Validators.checkStringHostname($accessor, $field)?.let { violations += it }")
            StringWellKnown.IP -> ctx.sb.appendLine("${i}Validators.checkStringIp($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV4 -> ctx.sb.appendLine("${i}Validators.checkStringIpv4($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV6 -> ctx.sb.appendLine("${i}Validators.checkStringIpv6($accessor, $field)?.let { violations += it }")
            StringWellKnown.ADDRESS -> ctx.sb.appendLine("${i}Validators.checkStringAddress($accessor, $field)?.let { violations += it }")
            StringWellKnown.HTTP_HEADER_NAME -> ctx.sb.appendLine("${i}Validators.checkStringHttpHeaderName($accessor, ${rules.strict}, $field)?.let { violations += it }")
            StringWellKnown.HTTP_HEADER_VALUE -> ctx.sb.appendLine("${i}Validators.checkStringHttpHeaderValue($accessor, ${rules.strict}, $field)?.let { violations += it }")
            StringWellKnown.HOST_AND_PORT -> ctx.sb.appendLine("${i}Validators.checkStringHostAndPort($accessor, $field)?.let { violations += it }")
            StringWellKnown.ULID -> ctx.sb.appendLine("${i}Validators.checkStringUlid($accessor, $field)?.let { violations += it }")
            StringWellKnown.TUUID -> ctx.sb.appendLine("${i}Validators.checkStringTuuid($accessor, $field)?.let { violations += it }")
            StringWellKnown.IP_WITH_PREFIXLEN -> ctx.sb.appendLine("${i}Validators.checkStringIpWithPrefixlen($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV4_WITH_PREFIXLEN -> ctx.sb.appendLine("${i}Validators.checkStringIpv4WithPrefixlen($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV6_WITH_PREFIXLEN -> ctx.sb.appendLine("${i}Validators.checkStringIpv6WithPrefixlen($accessor, $field)?.let { violations += it }")
            StringWellKnown.IP_PREFIX -> ctx.sb.appendLine("${i}Validators.checkStringIpPrefix($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV4_PREFIX -> ctx.sb.appendLine("${i}Validators.checkStringIpv4Prefix($accessor, $field)?.let { violations += it }")
            StringWellKnown.IPV6_PREFIX -> ctx.sb.appendLine("${i}Validators.checkStringIpv6Prefix($accessor, $field)?.let { violations += it }")
            StringWellKnown.PROTOBUF_FQN -> ctx.sb.appendLine("${i}Validators.checkStringProtobufFqn($accessor, $field)?.let { violations += it }")
            StringWellKnown.PROTOBUF_DOT_FQN -> ctx.sb.appendLine("${i}Validators.checkStringProtobufDotFqn($accessor, $field)?.let { violations += it }")
            null -> {}
        }

        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Bytes ──

    private fun emitBytesRules(rules: BytesRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val ba = "$accessor.toByteArray()"
        val hasIgnoreGuard = rules.ignore == IgnoreMode.IF_UNPOPULATED || rules.ignore == IgnoreMode.IF_DEFAULT_VALUE
        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}if (!$accessor.isEmpty) {")
        }
        val i = if (hasIgnoreGuard) "$indent    " else indent

        rules.const?.let { ctx.sb.appendLine("${i}Validators.checkBytesConst($ba, ${bytesToKotlinLiteral(it)}, $field)?.let { violations += it }") }
        rules.len?.let { ctx.sb.appendLine("${i}Validators.checkBytesLen($ba, ${it}L, $field)?.let { violations += it }") }
        rules.minLen?.let { ctx.sb.appendLine("${i}Validators.checkBytesMinLen($ba, ${it}L, $field)?.let { violations += it }") }
        rules.maxLen?.let { ctx.sb.appendLine("${i}Validators.checkBytesMaxLen($ba, ${it}L, $field)?.let { violations += it }") }
        rules.pattern?.let { ctx.sb.appendLine("${i}Validators.checkBytesPattern($ba, \"${escapeForKotlinString(it)}\", $field)?.let { violations += it }") }
        rules.prefix?.let { ctx.sb.appendLine("${i}Validators.checkBytesPrefix($ba, ${bytesToKotlinLiteral(it)}, $field)?.let { violations += it }") }
        rules.suffix?.let { ctx.sb.appendLine("${i}Validators.checkBytesSuffix($ba, ${bytesToKotlinLiteral(it)}, $field)?.let { violations += it }") }
        rules.contains?.let { ctx.sb.appendLine("${i}Validators.checkBytesContains($ba, ${bytesToKotlinLiteral(it)}, $field)?.let { violations += it }") }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { bytesToKotlinLiteral(it) }
            ctx.sb.appendLine("${i}Validators.checkBytesIn($ba, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { bytesToKotlinLiteral(it) }
            ctx.sb.appendLine("${i}Validators.checkBytesNotIn($ba, listOf($items), $field)?.let { violations += it }")
        }

        when (rules.wellKnown) {
            BytesWellKnown.IP -> ctx.sb.appendLine("${i}Validators.checkBytesIp($ba, $field)?.let { violations += it }")
            BytesWellKnown.IPV4 -> ctx.sb.appendLine("${i}Validators.checkBytesIpv4($ba, $field)?.let { violations += it }")
            BytesWellKnown.IPV6 -> ctx.sb.appendLine("${i}Validators.checkBytesIpv6($ba, $field)?.let { violations += it }")
            BytesWellKnown.UUID -> ctx.sb.appendLine("${i}Validators.checkBytesUuid($ba, $field)?.let { violations += it }")
            null -> {}
        }

        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Numeric (all int/float/double types) ──

    private fun emitNumericRules(rules: NumericRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasIgnoreGuard = rules.ignore == IgnoreMode.IF_UNPOPULATED || rules.ignore == IgnoreMode.IF_DEFAULT_VALUE
        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}if ($accessor != ${rules.zeroLiteral}) {")
        }
        val i = if (hasIgnoreGuard) "$indent    " else indent

        rules.constVal?.let {
            ctx.sb.appendLine("${i}Validators.checkConst($accessor, $it, $field, \"${rules.rulePrefix}.const\")?.let { violations += it }")
        }

        if (rules.finite) {
            ctx.sb.appendLine("${i}Validators.checkFinite($accessor, $field, \"${rules.rulePrefix}.finite\")?.let { violations += it }")
        }

        // When both lower (gt/gte) and upper (lt/lte) bounds are present, use combined range check
        val hasLower = rules.gtVal != null || rules.gteVal != null
        val hasUpper = rules.ltVal != null || rules.lteVal != null

        if (hasLower && hasUpper) {
            val lowerVal = rules.gtVal ?: rules.gteVal!!
            val upperVal = rules.ltVal ?: rules.lteVal!!
            val lowerInclusive = rules.gtVal == null // gte = inclusive
            val upperInclusive = rules.ltVal == null // lte = inclusive
            ctx.sb.appendLine("${i}Validators.checkRange($accessor, $lowerVal, $upperVal, $lowerInclusive, $upperInclusive, $field, \"${rules.rulePrefix}\")?.let { violations += it }")
        } else {
            rules.ltVal?.let {
                ctx.sb.appendLine("${i}Validators.checkLt($accessor, $it, $field, \"${rules.rulePrefix}.lt\")?.let { violations += it }")
            }
            rules.lteVal?.let {
                ctx.sb.appendLine("${i}Validators.checkLte($accessor, $it, $field, \"${rules.rulePrefix}.lte\")?.let { violations += it }")
            }
            rules.gtVal?.let {
                ctx.sb.appendLine("${i}Validators.checkGt($accessor, $it, $field, \"${rules.rulePrefix}.gt\")?.let { violations += it }")
            }
            rules.gteVal?.let {
                ctx.sb.appendLine("${i}Validators.checkGte($accessor, $it, $field, \"${rules.rulePrefix}.gte\")?.let { violations += it }")
            }
        }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ")
            ctx.sb.appendLine("${i}Validators.checkIn($accessor, listOf($items), $field, \"${rules.rulePrefix}.in\")?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ")
            ctx.sb.appendLine("${i}Validators.checkNotIn($accessor, listOf($items), $field, \"${rules.rulePrefix}.not_in\")?.let { violations += it }")
        }

        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Bool ──

    private fun emitBoolRules(rules: BoolRuleSet, accessor: String, field: String, ctx: EmitContext) {
        rules.const?.let {
            ctx.sb.appendLine("${ctx.indent}Validators.checkBoolConst($accessor, $it, $field)?.let { violations += it }")
        }
    }

    // ── Enum ──

    private fun emitEnumRules(rules: EnumRuleSet, accessor: String, field: String, ctx: EmitContext) {
        // For top-level fields: myField → myFieldValue (protobuf-java convention)
        // For repeated items: item → item.number (enum value in a list)
        val numericAccessor = if (accessor == "item") "item.number" else "${rawAccessor(accessor)}Value"
        rules.const?.let {
            ctx.sb.appendLine("${ctx.indent}Validators.checkEnumConst($numericAccessor, $it, $field)?.let { violations += it }")
        }
        if (rules.definedOnly) {
            ctx.sb.appendLine("${ctx.indent}if ($accessor.toString() == \"UNRECOGNIZED\") {")
            ctx.sb.appendLine("${ctx.indent}    violations += dev.bmcreations.protovalidate.FieldViolation($field, \"enum.defined_only\", \"value must be a defined enum value\")")
            ctx.sb.appendLine("${ctx.indent}}")
        }
        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ")
            ctx.sb.appendLine("${ctx.indent}Validators.checkEnumIn($numericAccessor, listOf($items), $field)?.let { violations += it }")
        }
        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ")
            ctx.sb.appendLine("${ctx.indent}Validators.checkEnumNotIn($numericAccessor, listOf($items), $field)?.let { violations += it }")
        }
    }

    // ── Repeated ──

    private fun emitRepeatedRules(
        ruleSet: FieldRuleSet,
        accessor: String,
        field: String,
        fieldProto: FieldDescriptorProto,
        ctx: EmitContext
    ) {
        val indent = ctx.indent
        val repeated = ruleSet.repeated ?: return

        val hasIgnoreGuard = repeated.ignore == IgnoreMode.IF_UNPOPULATED || repeated.ignore == IgnoreMode.IF_DEFAULT_VALUE
        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}if (${rawAccessor(accessor)}List.isNotEmpty()) {")
        }
        val i = if (hasIgnoreGuard) "$indent    " else indent

        repeated.minItems?.let {
            ctx.sb.appendLine("${i}Validators.checkMinItems(${rawAccessor(accessor)}List.size, ${it}L, $field)?.let { violations += it }")
        }
        repeated.maxItems?.let {
            ctx.sb.appendLine("${i}Validators.checkMaxItems(${rawAccessor(accessor)}List.size, ${it}L, $field)?.let { violations += it }")
        }
        if (repeated.unique) {
            ctx.sb.appendLine("${i}Validators.checkUnique(${rawAccessor(accessor)}List, $field)?.let { violations += it }")
        }

        // Per-item validation
        val items = repeated.items
        if (items != null) {
            val skipItems = items.message?.skip == true

            if (!skipItems) {
                val rawFieldName = fieldProto.name
                ctx.sb.appendLine("${i}for ((index, item) in ${rawAccessor(accessor)}List.withIndex()) {")
                val itemCtx = ctx.copy(indent = "$i    ")

                // For WKT wrapper types (Int32Value, etc.), item is a message — unwrap to .value
                val isWrappedItem = fieldProto.type == Type.TYPE_MESSAGE && isWktWrapperType(fieldProto.typeName)
                val itemAccessor = if (isWrappedItem) "item.value" else "item"

                // If items have type-specific rules, emit them (includes CEL rules)
                // Pass the proto field type for proper CEL type inference (e.g., enum → .number)
                val itemProtoType = if (isWrappedItem) null else fieldProto.type
                if (items.type != RuleType.NONE) {
                    emitValueRules(items, itemAccessor, $$"\"$${rawFieldName}[$index]\"", itemCtx, itemProtoType)
                } else if (items.celRules.isNotEmpty() || items.predefinedCelRules.isNotEmpty()) {
                    // Items only have CEL rules (no type-specific rules)
                    emitCelRules(items, itemAccessor, $$"\"$${rawFieldName}[$index]\"", itemCtx, itemProtoType)
                }

                // Recursive validation for message items
                if (fieldProto.type == Type.TYPE_MESSAGE) {
                    val typeName = fieldProto.typeName
                    val targetPkg = ctx.validatedTypes[typeName]
                    if (targetPkg != null) {
                        ctx.sb.appendLine("$i    item.validate().let { result ->")
                        ctx.sb.appendLine("$i        if (result is ValidationResult.Invalid) {")
                        ctx.sb.appendLine($$"$$i            violations += result.violations.map { it.copy(field = \"$${rawFieldName}[$index].${it.field}\") }")
                        ctx.sb.appendLine("$i        }")
                        ctx.sb.appendLine("$i    }")
                        ctx.neededImports.add(targetPkg)
                    }
                }

                ctx.sb.appendLine("${i}}")
            }
        } else if (fieldProto.type == Type.TYPE_MESSAGE) {
            // No item rules but this is a repeated message field — still do recursive validation
            val typeName = fieldProto.typeName
            val targetPkg = ctx.validatedTypes[typeName]
            if (targetPkg != null) {
                val rawFieldName = fieldProto.name
                ctx.sb.appendLine("${i}for ((index, item) in ${rawAccessor(accessor)}List.withIndex()) {")
                ctx.sb.appendLine("$i    item.validate().let { result ->")
                ctx.sb.appendLine("$i        if (result is ValidationResult.Invalid) {")
                ctx.sb.appendLine($$"$$i            violations += result.violations.map { it.copy(field = \"$${rawFieldName}[$index].${it.field}\") }")
                ctx.sb.appendLine("$i        }")
                ctx.sb.appendLine("$i    }")
                ctx.sb.appendLine("${i}}")
                ctx.neededImports.add(targetPkg)
            }
        }

        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Map ──

    private fun emitMapRules(rules: MapRuleSet, accessor: String, field: String, ctx: EmitContext, keyProtoType: Type? = null, valueProtoType: Type? = null) {
        val indent = ctx.indent
        val hasIgnoreGuard = rules.ignore == IgnoreMode.IF_UNPOPULATED || rules.ignore == IgnoreMode.IF_DEFAULT_VALUE
        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}if (${rawAccessor(accessor)}Map.isNotEmpty()) {")
        }
        val i = if (hasIgnoreGuard) "$indent    " else indent

        rules.minPairs?.let {
            ctx.sb.appendLine("${i}Validators.checkMinPairs(${rawAccessor(accessor)}Map.size, ${it}L, $field)?.let { violations += it }")
        }
        rules.maxPairs?.let {
            ctx.sb.appendLine("${i}Validators.checkMaxPairs(${rawAccessor(accessor)}Map.size, ${it}L, $field)?.let { violations += it }")
        }

        // Per-key/value validation
        val keyRules = rules.keys?.takeIf { it.type != RuleType.NONE || it.celRules.isNotEmpty() || it.predefinedCelRules.isNotEmpty() }
        val valueRules = rules.values?.takeIf { it.type != RuleType.NONE || it.celRules.isNotEmpty() || it.predefinedCelRules.isNotEmpty() }
        if (keyRules != null || valueRules != null) {
            val rawFieldName = field.removeSurrounding("\"")
            ctx.sb.appendLine("${i}for ((mapKey, mapValue) in ${rawAccessor(accessor)}Map) {")
            if (keyRules != null) {
                val keyCtx = ctx.copy(indent = "$i    ")
                emitValueRules(keyRules, "mapKey", $$"\"$${rawFieldName}[$mapKey].key\"", keyCtx, keyProtoType)
            }
            if (valueRules != null) {
                val valCtx = ctx.copy(indent = "$i    ")
                emitValueRules(valueRules, "mapValue",
                    $$"\"$${rawFieldName}[$mapKey].value\"", valCtx, valueProtoType)
            }
            ctx.sb.appendLine("${i}}")
        }

        if (hasIgnoreGuard) {
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Duration ──

    private fun emitDurationRules(rules: DurationRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasAccessor = "has${rawAccessor(accessor).replaceFirstChar { it.uppercaseChar() }}()"
        if (rules.required) {
            ctx.sb.appendLine("${indent}Validators.checkDurationRequired($hasAccessor, $field)?.let { violations += it }")
        }
        val hasConst = rules.const != null
        val durHasLower = rules.gt != null || rules.gte != null
        val durHasUpper = rules.lt != null || rules.lte != null
        val hasIn = rules.inList.isNotEmpty()
        val hasNotIn = rules.notInList.isNotEmpty()

        if (hasConst || durHasLower || durHasUpper || hasIn || hasNotIn) {
            ctx.sb.appendLine("${indent}if ($hasAccessor) {")
            val i = "$indent    "

            rules.const?.let { c ->
                ctx.sb.appendLine("${i}Validators.checkDurationConst($accessor.seconds, $accessor.nanos, ${c.seconds}L, ${c.nanos}, $field)?.let { violations += it }")
            }

            if (durHasLower && durHasUpper) {
                val lower = rules.gt ?: rules.gte!!
                val upper = rules.lt ?: rules.lte!!
                val lowerInclusive = rules.gt == null
                val upperInclusive = rules.lt == null
                ctx.sb.appendLine("${i}Validators.checkDurationRange($accessor.seconds, $accessor.nanos, ${lower.seconds}L, ${lower.nanos}, ${upper.seconds}L, ${upper.nanos}, $lowerInclusive, $upperInclusive, $field)?.let { violations += it }")
            } else {
                rules.lt?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkDurationLt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.lte?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkDurationLte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.gt?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkDurationGt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.gte?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkDurationGte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
            }

            if (rules.inList.isNotEmpty()) {
                val items = rules.inList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
                ctx.sb.appendLine("${i}Validators.checkDurationIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
            }
            if (rules.notInList.isNotEmpty()) {
                val items = rules.notInList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
                ctx.sb.appendLine("${i}Validators.checkDurationNotIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
            }

            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── Timestamp ──

    private fun emitTimestampRules(rules: TimestampRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasAccessor = "has${rawAccessor(accessor).replaceFirstChar { it.uppercaseChar() }}()"
        if (rules.required) {
            ctx.sb.appendLine("${indent}Validators.checkTimestampRequired($hasAccessor, $field)?.let { violations += it }")
        }
        val tsHasLower = rules.gt != null || rules.gte != null
        val tsHasUpper = rules.lt != null || rules.lte != null
        val hasChecks = rules.const != null || tsHasLower || tsHasUpper ||
            rules.ltNow || rules.gtNow || rules.within != null ||
            rules.inList.isNotEmpty() || rules.notInList.isNotEmpty()

        if (hasChecks) {
            ctx.sb.appendLine("${indent}if ($hasAccessor) {")
            val i = "$indent    "

            rules.const?.let { c ->
                ctx.sb.appendLine("${i}Validators.checkTimestampConst($accessor.seconds, $accessor.nanos, ${c.seconds}L, ${c.nanos}, $field)?.let { violations += it }")
            }

            if (tsHasLower && tsHasUpper) {
                val lower = rules.gt ?: rules.gte!!
                val upper = rules.lt ?: rules.lte!!
                val lowerInclusive = rules.gt == null
                val upperInclusive = rules.lt == null
                ctx.sb.appendLine("${i}Validators.checkTimestampRange($accessor.seconds, $accessor.nanos, ${lower.seconds}L, ${lower.nanos}, ${upper.seconds}L, ${upper.nanos}, $lowerInclusive, $upperInclusive, $field)?.let { violations += it }")
            } else {
                rules.lt?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkTimestampLt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.lte?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkTimestampLte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.gt?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkTimestampGt($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
                rules.gte?.let { v ->
                    ctx.sb.appendLine("${i}Validators.checkTimestampGte($accessor.seconds, $accessor.nanos, ${v.seconds}L, ${v.nanos}, $field)?.let { violations += it }")
                }
            }

            if (rules.ltNow) {
                ctx.sb.appendLine("${i}Validators.checkTimestampLtNow($accessor.seconds, $accessor.nanos, $field)?.let { violations += it }")
            }
            if (rules.gtNow) {
                ctx.sb.appendLine("${i}Validators.checkTimestampGtNow($accessor.seconds, $accessor.nanos, $field)?.let { violations += it }")
            }
            rules.within?.let { w ->
                ctx.sb.appendLine("${i}Validators.checkTimestampWithin($accessor.seconds, $accessor.nanos, ${w.seconds}L, ${w.nanos}, $field)?.let { violations += it }")
            }

            if (rules.inList.isNotEmpty()) {
                val items = rules.inList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
                ctx.sb.appendLine("${i}Validators.checkTimestampIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
            }
            if (rules.notInList.isNotEmpty()) {
                val items = rules.notInList.joinToString(", ") { v -> "Pair(${v.seconds}L, ${v.nanos})" }
                ctx.sb.appendLine("${i}Validators.checkTimestampNotIn($accessor.seconds, $accessor.nanos, listOf($items), $field)?.let { violations += it }")
            }

            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── WKT wrapper helpers ──

    private val WKT_WRAPPER_SUFFIXES = setOf(
        ".google.protobuf.DoubleValue",
        ".google.protobuf.FloatValue",
        ".google.protobuf.Int64Value",
        ".google.protobuf.UInt64Value",
        ".google.protobuf.Int32Value",
        ".google.protobuf.UInt32Value",
        ".google.protobuf.BoolValue",
        ".google.protobuf.StringValue",
        ".google.protobuf.BytesValue",
    )

    private fun isWktWrapperType(typeName: String): Boolean =
        WKT_WRAPPER_SUFFIXES.any { typeName.endsWith(it) }

    // ── Presence helpers ──

    /**
     * Returns true if the field has explicit presence tracking (i.e., has a `has*()` method).
     * - Proto2: all singular fields have presence
     * - Proto3: only `optional` fields (proto3Optional=true) and message-type fields
     * - Editions: check resolved features.field_presence; EXPLICIT and LEGACY_REQUIRED have presence
     */
    private fun fieldHasPresence(field: FieldDescriptorProto, fileSyntax: String): Boolean {
        // Message and enum types in proto2 always have presence; message fields always do in all syntaxes
        if (field.type == Type.TYPE_MESSAGE) return true

        return when (fileSyntax) {
            "proto2" -> true // All singular proto2 fields have presence
            "proto3" -> {
                // Proto3 fields only have presence if they're `optional` (proto3Optional flag)
                // or if they're in a real oneof
                field.proto3Optional || (field.hasOneofIndex() && !field.proto3Optional)
            }
            "editions" -> {
                // Check resolved features on the field
                if (field.options?.hasFeatures() == true) {
                    val presence = field.options.features.fieldPresence
                    // EXPLICIT (1) and LEGACY_REQUIRED (3) have presence; IMPLICIT (2) does not
                    presence != com.google.protobuf.DescriptorProtos.FeatureSet.FieldPresence.IMPLICIT
                } else {
                    // Default for editions is EXPLICIT
                    true
                }
            }
            else -> true
        }
    }

    /**
     * Returns a boolean expression that is true when the implicit-presence scalar field
     * is NOT the zero/default value. Used for `required` checks on fields without `has*()`.
     */
    private fun scalarZeroCheck(field: FieldDescriptorProto, accessor: String): String? {
        return when (field.type) {
            Type.TYPE_STRING -> "$accessor.isNotEmpty()"
            Type.TYPE_BYTES -> "!$accessor.isEmpty"
            Type.TYPE_BOOL -> accessor // bool: true = set, false = zero
            Type.TYPE_INT32, Type.TYPE_SINT32, Type.TYPE_SFIXED32 -> "$accessor != 0"
            Type.TYPE_INT64, Type.TYPE_SINT64, Type.TYPE_SFIXED64 -> "$accessor != 0L"
            Type.TYPE_UINT32, Type.TYPE_FIXED32 -> "$accessor != 0"
            Type.TYPE_UINT64, Type.TYPE_FIXED64 -> "$accessor != 0L"
            Type.TYPE_FLOAT -> "$accessor != 0.0f"
            Type.TYPE_DOUBLE -> "$accessor != 0.0"
            Type.TYPE_ENUM -> "${rawAccessor(accessor)}Value != 0"
            else -> null
        }
    }

    // ── Any ──

    private fun emitAnyRules(rules: AnyRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasAny = "has${rawAccessor(accessor).replaceFirstChar { it.uppercaseChar() }}()"
        if (rules.required) {
            ctx.sb.appendLine("${indent}Validators.checkRequired($hasAny, $field)?.let { violations += it }")
        }
        if (rules.inList.isNotEmpty()) {
            ctx.sb.appendLine("${indent}if ($hasAny) {")
            val items = rules.inList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("$indent    Validators.checkAnyIn($accessor.typeUrl, listOf($items), $field)?.let { violations += it }")
            ctx.sb.appendLine("${indent}}")
        }
        if (rules.notInList.isNotEmpty()) {
            ctx.sb.appendLine("${indent}if ($hasAny) {")
            val items = rules.notInList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("$indent    Validators.checkAnyNotIn($accessor.typeUrl, listOf($items), $field)?.let { violations += it }")
            ctx.sb.appendLine("${indent}}")
        }
    }

    // ── FieldMask ──

    private fun emitFieldMaskRules(rules: FieldMaskRuleSet, accessor: String, field: String, ctx: EmitContext) {
        val indent = ctx.indent
        val hasAccessor = "has${rawAccessor(accessor).replaceFirstChar { it.uppercaseChar() }}()"

        val hasChecks = rules.constPaths != null || rules.inList.isNotEmpty() || rules.notInList.isNotEmpty()
        if (!hasChecks) return

        ctx.sb.appendLine("${indent}if ($hasAccessor) {")
        val i = "$indent    "

        rules.constPaths?.let { paths ->
            val items = paths.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${i}Validators.checkFieldMaskConst($accessor.pathsList, listOf($items), $field)?.let { violations += it }")
        }

        if (rules.inList.isNotEmpty()) {
            val items = rules.inList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${i}Validators.checkFieldMaskIn($accessor.pathsList, listOf($items), $field)?.let { violations += it }")
        }

        if (rules.notInList.isNotEmpty()) {
            val items = rules.notInList.joinToString(", ") { "\"${escapeForKotlinString(it)}\"" }
            ctx.sb.appendLine("${i}Validators.checkFieldMaskNotIn($accessor.pathsList, listOf($items), $field)?.let { violations += it }")
        }

        ctx.sb.appendLine("${indent}}")
    }

    // ── CEL Rules ──

    /**
     * Emits CEL-based validation rules for a field.
     * This handles both direct `(field).cel` rules and predefined CEL rules from extensions.
     *
     * @param protoFieldType optional proto field type for better type inference when ruleSet.type is NONE
     */
    private fun emitCelRules(
        ruleSet: FieldRuleSet,
        accessor: String,
        field: String,
        ctx: EmitContext,
        protoFieldType: Type? = null,
        mapValueType: CelFieldType = CelFieldType.UNKNOWN,
        elementType: CelFieldType = CelFieldType.UNKNOWN,
    ) {
        if (ruleSet.celRules.isEmpty() && ruleSet.predefinedCelRules.isEmpty()) return


        val indent = ctx.indent

        // Determine the CelFieldType from the RuleType, falling back to proto field type
        val celFieldType = if (ruleSet.type != RuleType.NONE) {
            ruleTypeToCelFieldType(ruleSet.type)
        } else if (protoFieldType != null) {
            protoTypeToCelFieldType(protoFieldType)
        } else {
            CelFieldType.UNKNOWN
        }

        // Adjust accessor for CEL context:
        // - Enum fields: CEL compares with int literals, so use the numeric value accessor
        // - Proto3 has getFieldValue() (int), proto2 only has getField() (enum) → use .number
        val celAccessor = when (celFieldType) {
            CelFieldType.ENUM -> {
                if (accessor == "item" || accessor == "item.value" || accessor == "mapKey" || accessor == "mapValue") {
                    // Repeated/map enum items: use .number for CEL integer comparison
                    "$accessor.number"
                } else if (ctx.fileSyntax == "proto2") {
                    // Proto2: no *Value getter, use .number on the enum accessor
                    "$accessor.number"
                } else {
                    val raw = rawAccessor(accessor)
                    "${raw}Value"
                }
            }
            else -> accessor
        }

        // Emit direct CEL rules
        for ((index, rule) in ruleSet.celRules.withIndex()) {
            emitSingleCelRule(rule.id, rule.expression, rule.message, celAccessor, field, celFieldType, null, ctx, mapValueType, elementType, celIndex = index, isCelExpression = rule.isCelExpression)
        }

        // Emit predefined CEL rules
        for (rule in ruleSet.predefinedCelRules) {
            emitSingleCelRule(rule.id, rule.expression, rule.message, celAccessor, field, celFieldType, rule.ruleValue, ctx, mapValueType, elementType)
        }
    }

    private fun protoTypeToCelFieldType(type: Type): CelFieldType = when (type) {
        Type.TYPE_INT32, Type.TYPE_SINT32, Type.TYPE_SFIXED32 -> CelFieldType.INT32
        Type.TYPE_INT64, Type.TYPE_SINT64, Type.TYPE_SFIXED64 -> CelFieldType.INT64
        Type.TYPE_UINT32, Type.TYPE_FIXED32 -> CelFieldType.UINT32
        Type.TYPE_UINT64, Type.TYPE_FIXED64 -> CelFieldType.UINT64
        Type.TYPE_FLOAT -> CelFieldType.FLOAT
        Type.TYPE_DOUBLE -> CelFieldType.DOUBLE
        Type.TYPE_STRING -> CelFieldType.STRING
        Type.TYPE_BYTES -> CelFieldType.BYTES
        Type.TYPE_BOOL -> CelFieldType.BOOL
        Type.TYPE_ENUM -> CelFieldType.ENUM
        Type.TYPE_MESSAGE, Type.TYPE_GROUP -> CelFieldType.MESSAGE
        else -> CelFieldType.UNKNOWN
    }

    /**
     * Resolves the value type of a map field by looking up its Entry message in nestedTypes.
     * Map entry messages have field 1 = key, field 2 = value.
     */
    private fun resolveMapValueType(
        fieldProto: FieldDescriptorProto,
        nestedTypes: List<com.google.protobuf.DescriptorProtos.DescriptorProto>
    ): CelFieldType {
        // The type name is something like ".pkg.MessageName.FieldEntry"
        // The nested type name is just "FieldEntry" (last segment)
        val entryName = fieldProto.typeName.substringAfterLast(".")
        val entryType = nestedTypes.find { it.name == entryName } ?: return CelFieldType.UNKNOWN
        // Field number 2 is the value field in a map entry
        val valueField = entryType.fieldList.find { it.number == 2 } ?: return CelFieldType.UNKNOWN
        return protoTypeToCelFieldType(valueField.type)
    }

    /**
     * Emits Kotlin validation code for a single CEL expression.
     */
    private fun emitSingleCelRule(
        id: String,
        expression: String,
        message: String,
        accessor: String,
        field: String,
        celFieldType: CelFieldType,
        ruleValue: String?,
        ctx: EmitContext,
        mapValueType: CelFieldType = CelFieldType.UNKNOWN,
        elementType: CelFieldType = CelFieldType.UNKNOWN,
        celIndex: Int = -1,
        isCelExpression: Boolean = false,
    ) {
        val indent = ctx.indent
        val extraArgs = buildString {
            if (celIndex >= 0) append(", celIndex = $celIndex")
            if (isCelExpression) append(", isCelExpression = true")
        }

        try {
            val parser = CelParser(expression)
            val ast = parser.parse()

            val celCtx = CelContext(
                thisAccessor = accessor,
                ruleValue = ruleValue,
                fieldType = celFieldType,
                mapValueType = mapValueType,
                elementType = elementType,
            )

            // Check if this is the ternary violation pattern: expr ? '' : 'msg'
            val ternaryCondition = CelTranspiler.extractTernaryCondition(ast, celCtx)
            if (ternaryCondition != null) {
                val ternaryMsg = CelTranspiler.extractTernaryMessage(ast, celCtx)
                val msgLiteral = if (ternaryMsg != null && !ternaryMsg.contains("\${")) {
                    "\"${escapeForKotlinString(ternaryMsg)}\""
                } else if (ternaryMsg != null) {
                    // Dynamic message with interpolation
                    "\"$ternaryMsg\""
                } else {
                    "\"\""
                }
                ctx.sb.appendLine("${indent}if (!($ternaryCondition)) { violations += FieldViolation($field, \"${escapeForKotlinString(id)}\", $msgLiteral$extraArgs) }")
                return
            }

            // Otherwise: boolean-returning expression (valid when true)
            val kotlinExpr = CelTranspiler.transpile(ast, celCtx)
            val msgLiteral = if (message.isNotEmpty()) {
                "\"${escapeForKotlinString(message)}\""
            } else {
                "\"\""
            }
            ctx.sb.appendLine("${indent}if (!($kotlinExpr)) { violations += FieldViolation($field, \"${escapeForKotlinString(id)}\", $msgLiteral$extraArgs) }")
        } catch (e: CelRuntimeException) {
            // Runtime error (e.g., dyn()) → emit RuntimeException at runtime
            ctx.sb.appendLine("${indent}throw RuntimeException(\"${escapeForKotlinString(e.message ?: "runtime error")}\")")
        } catch (e: CelParseException) {
            // Unsupported expression → emit CompilationError at runtime
            ctx.sb.appendLine("${indent}throw dev.bmcreations.protovalidate.CompilationError(\"unsupported CEL expression: ${escapeForKotlinString(expression)}\")")
        }
    }

    /**
     * Emits message-level CEL rules.
     * Called from CodeGenerator for `(buf.validate.message).cel` constraints.
     */
    fun emitMessageCelRule(
        rule: MessageCelRule,
        ctx: EmitContext,
        celIndex: Int = -1,
    ) {
        val indent = ctx.indent
        val extraArgs = buildString {
            if (celIndex >= 0) append(", celIndex = $celIndex")
            if (rule.isCelExpression) append(", isCelExpression = true")
            append(", isMessageLevelCel = true")
        }

        try {
            val parser = CelParser(rule.expression)
            val ast = parser.parse()

            val celCtx = CelContext(
                thisAccessor = "this",
                ruleValue = null,
                fieldType = CelFieldType.MESSAGE,
            )

            // Check if this is the ternary violation pattern
            val ternaryCondition = CelTranspiler.extractTernaryCondition(ast, celCtx)
            if (ternaryCondition != null) {
                val ternaryMsg = CelTranspiler.extractTernaryMessage(ast, celCtx)
                val msgLiteral = if (ternaryMsg != null && !ternaryMsg.contains("\${")) {
                    "\"${escapeForKotlinString(ternaryMsg)}\""
                } else if (ternaryMsg != null) {
                    "\"$ternaryMsg\""
                } else {
                    "\"\""
                }
                ctx.sb.appendLine("${indent}if (!($ternaryCondition)) { violations += FieldViolation(\"\", \"${escapeForKotlinString(rule.id)}\", $msgLiteral$extraArgs) }")
                return
            }

            // Boolean-returning expression
            val kotlinExpr = CelTranspiler.transpile(ast, celCtx)
            val msgLiteral = if (rule.message.isNotEmpty()) {
                "\"${escapeForKotlinString(rule.message)}\""
            } else {
                "\"\""
            }
            ctx.sb.appendLine("${indent}if (!($kotlinExpr)) { violations += FieldViolation(\"\", \"${escapeForKotlinString(rule.id)}\", $msgLiteral$extraArgs) }")
        } catch (e: CelRuntimeException) {
            ctx.sb.appendLine("${indent}throw RuntimeException(\"${escapeForKotlinString(e.message ?: "runtime error")}\")")
        } catch (e: CelParseException) {
            ctx.sb.appendLine("${indent}throw dev.bmcreations.protovalidate.CompilationError(\"unsupported CEL expression: ${escapeForKotlinString(rule.expression)}\")")
        }
    }

    private fun ruleTypeToCelFieldType(type: RuleType): CelFieldType = when (type) {
        RuleType.INT32 -> CelFieldType.INT32
        RuleType.INT64 -> CelFieldType.INT64
        RuleType.UINT32 -> CelFieldType.UINT32
        RuleType.UINT64 -> CelFieldType.UINT64
        RuleType.SINT32 -> CelFieldType.SINT32
        RuleType.SINT64 -> CelFieldType.SINT64
        RuleType.FIXED32 -> CelFieldType.FIXED32
        RuleType.FIXED64 -> CelFieldType.FIXED64
        RuleType.SFIXED32 -> CelFieldType.SFIXED32
        RuleType.SFIXED64 -> CelFieldType.SFIXED64
        RuleType.FLOAT -> CelFieldType.FLOAT
        RuleType.DOUBLE -> CelFieldType.DOUBLE
        RuleType.STRING -> CelFieldType.STRING
        RuleType.BYTES -> CelFieldType.BYTES
        RuleType.BOOL -> CelFieldType.BOOL
        RuleType.ENUM -> CelFieldType.ENUM
        RuleType.DURATION -> CelFieldType.DURATION
        RuleType.TIMESTAMP -> CelFieldType.TIMESTAMP
        RuleType.REPEATED -> CelFieldType.REPEATED
        RuleType.MAP -> CelFieldType.MAP
        else -> CelFieldType.UNKNOWN
    }
}

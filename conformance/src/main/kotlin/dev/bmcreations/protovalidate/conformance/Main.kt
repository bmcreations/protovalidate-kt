package dev.bmcreations.protovalidate.conformance

import build.buf.validate.FieldPath
import build.buf.validate.FieldPathElement
import build.buf.validate.Violations
import build.buf.validate.Violation
import buf.validate.conformance.harness.TestConformanceRequest
import buf.validate.conformance.harness.TestConformanceResponse
import buf.validate.conformance.harness.TestResult
import dev.bmcreations.protovalidate.FieldViolation
import dev.bmcreations.protovalidate.ValidationResult
import com.google.protobuf.Any
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message

fun main() {
    val input = System.`in`.readBytes()
    val request = TestConformanceRequest.parseFrom(input)

    val results = mutableMapOf<String, TestResult>()

    for ((name, anyMsg) in request.casesMap) {
        results[name] = executeCase(anyMsg)
    }

    val response = TestConformanceResponse.newBuilder()
        .putAllResults(results)
        .build()

    response.writeTo(System.out)
}

private fun executeCase(anyMsg: Any): TestResult {
    return try {
        val typeName = anyMsg.typeUrl.substringAfterLast('/')

        val messageClass = resolveMessageClass(typeName)
            ?: return TestResult.newBuilder()
                .setRuntimeError("Unknown type: $typeName")
                .build()

        val parseFrom = messageClass.getMethod("parseFrom", ByteArray::class.java)
        val message = parseFrom.invoke(null, anyMsg.value.toByteArray()) as Message

        // If no validator exists, the message has no constraints and is always valid.
        val validatorClass = resolveValidatorClass(typeName)
            ?: return TestResult.newBuilder()
                .setSuccess(true)
                .build()

        val validateMethod = validatorClass.methods.find {
            it.name == "validate" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(messageClass)
        } ?: return TestResult.newBuilder()
            .setRuntimeError("No validate() method found in ${validatorClass.name} for $typeName")
            .build()

        val result = validateMethod.invoke(null, message) as ValidationResult

        when (result) {
            is ValidationResult.Valid -> TestResult.newBuilder()
                .setSuccess(true)
                .build()

            is ValidationResult.Invalid -> {
                val descriptor = message.descriptorForType
                val violations = Violations.newBuilder()
                for (v in result.violations) {
                    val builder = Violation.newBuilder()
                        .setRuleId(v.rule)

                    // _empty violations have no message text in conformance output
                    if (!v.rule.endsWith("_empty")) {
                        builder.setMessage(v.message)
                    }

                    // For message.oneof violations, emit only field_name (no field_number) and
                    // no rule path — the violation refers to the group constraint, not a specific
                    // field rule.
                    if (v.rule == "message.oneof") {
                        if (v.field.isNotBlank()) {
                            val element = FieldPathElement.newBuilder().setFieldName(v.field)
                            builder.setField(FieldPath.newBuilder().addElements(element).build())
                        }
                        // No rule path for message.oneof
                    } else if (v.field.isBlank() && v.rule != "required") {
                        // Message-level constraints: no rule path for any message-level cel rules
                        if (v.rule.startsWith("library.")) {
                            // Library constraints: no rule path, keep message
                        } else if (!v.isCelExpression) {
                            // Named message cel rules: no rule path, no message
                            builder.clearMessage()
                        }
                        // cel_expression: keep message, no rule path
                    } else {
                        // Build field path from descriptor
                        val fieldPathProto = buildFieldPath(v.field, descriptor)
                        fieldPathProto?.let { builder.setField(it) }

                        if (v.isMessageLevelCel) {
                            // Nested message-level CEL propagated from child: no rule path, no message
                            builder.clearMessage()
                        } else {
                            // For oneof required violations (field path points to a oneof group, not a real
                            // field), the last element has fieldNumber == 0 — omit the rule path entirely.
                            val isOneofRequired = v.rule == "required" && fieldPathProto != null &&
                                fieldPathProto.elementsCount > 0 &&
                                fieldPathProto.getElements(fieldPathProto.elementsCount - 1).fieldNumber == 0
                            if (!isOneofRequired) {
                                // Build rule path from the rule ID
                                buildRulePath(v.rule, v.field, descriptor, v.celIndex, v.isCelExpression)?.let { builder.setRule(it) }
                            }
                        }
                    }

                    // Set for_key if this violation is for a map key
                    if (v.forKey || v.field.endsWith(".key")) {
                        builder.setForKey(true)
                    }

                    violations.addViolations(builder)
                }
                TestResult.newBuilder()
                    .setValidationError(violations)
                    .build()
            }
        }
    } catch (e: ClassNotFoundException) {
        TestResult.newBuilder()
            .setRuntimeError("Class not found: ${e.message}")
            .build()
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException
        if (cause is dev.bmcreations.protovalidate.CompilationError) {
            TestResult.newBuilder()
                .setCompilationError(cause.message ?: "compilation error")
                .build()
        } else if (cause is RuntimeException) {
            TestResult.newBuilder()
                .setRuntimeError(cause.message ?: "runtime error")
                .build()
        } else {
            TestResult.newBuilder()
                .setUnexpectedError("Validation threw: ${cause.javaClass.simpleName}: ${cause.message}")
                .build()
        }
    } catch (e: Exception) {
        TestResult.newBuilder()
            .setUnexpectedError("${e.javaClass.simpleName}: ${e.message}")
            .build()
    }
}

/**
 * Builds a FieldPath from a dotted field string and a message descriptor.
 * Handles simple paths like "val", nested paths like "nested.field",
 * indexed paths like "val[0]" for repeated fields,
 * and map key/value paths like "val[key].key" and "val[key].value".
 */
private fun buildFieldPath(fieldPath: String, descriptor: Descriptors.Descriptor): FieldPath? {
    if (fieldPath.isBlank()) return null

    val builder = FieldPath.newBuilder()
    var currentDescriptor = descriptor

    // Split by dots to get path segments
    val segments = fieldPath.split(".")
    var i = 0
    while (i < segments.size) {
        val segment = segments[i]

        // Parse "field[something]" → field name + optional subscript value
        val indexedMatch = Regex("""^(\w+)\[(.+)]$""").find(segment)
        val fieldName = indexedMatch?.groupValues?.get(1) ?: segment
        val subscriptValue = indexedMatch?.groupValues?.get(2)

        val fieldDesc = currentDescriptor.findFieldByName(fieldName)

        if (fieldDesc == null) {
            // Check if this is a oneof group name — oneofs are not fields but can appear in
            // violation field paths for oneof.required violations.
            val oneofDesc = currentDescriptor.oneofs.firstOrNull { it.name == fieldName }
            if (oneofDesc != null) {
                // Oneof required: emit an element with only field_name, no field_number/type
                val element = FieldPathElement.newBuilder().setFieldName(fieldName)
                builder.addElements(element)
            }
            // Whether it was a oneof or truly unknown, we stop here
            return builder.build().takeIf { it.elementsCount > 0 }
        }

        val element = FieldPathElement.newBuilder()
            .setFieldNumber(fieldDesc.number)
            .setFieldName(fieldName)
            .setFieldType(toFieldType(fieldDesc))

        if (fieldDesc.isMapField && subscriptValue != null) {
            // For map fields with a subscript, set key_type, value_type, and key subscript
            val entryDesc = fieldDesc.messageType
            val keyFieldDesc = entryDesc.findFieldByName("key")
            val valueFieldDesc = entryDesc.findFieldByName("value")
            if (keyFieldDesc != null) element.setKeyType(toFieldType(keyFieldDesc))
            if (valueFieldDesc != null) element.setValueType(toFieldType(valueFieldDesc))

            // Set the map key subscript based on key type
            if (keyFieldDesc != null) {
                setMapKeySubscript(element, keyFieldDesc, subscriptValue)
            }

            // If the next segment is "key" or "value", consume it (it's not a real field descent)
            if (i + 1 < segments.size && (segments[i + 1] == "key" || segments[i + 1] == "value")) {
                i++ // skip the "key"/"value" suffix segment
            }

            // Descend into the map value type for subsequent path segments
            if (valueFieldDesc != null && valueFieldDesc.type == FieldDescriptor.Type.MESSAGE) {
                currentDescriptor = valueFieldDesc.messageType
            }
        } else {
            // For repeated fields or plain fields: handle numeric index
            val numericIndex = subscriptValue?.toLongOrNull()
            if (numericIndex != null) {
                element.setIndex(numericIndex)
            }
        }

        builder.addElements(element)

        // Descend into nested message or group types (but not into map entry types)
        if ((fieldDesc.type == FieldDescriptor.Type.MESSAGE || fieldDesc.type == FieldDescriptor.Type.GROUP) && !fieldDesc.isMapField) {
            currentDescriptor = fieldDesc.messageType
        }

        i++
    }

    return builder.build()
}

/**
 * Sets the appropriate map key subscript on a FieldPathElement builder
 * based on the key field descriptor type and the raw string key value.
 */
private fun setMapKeySubscript(
    element: FieldPathElement.Builder,
    keyFieldDesc: FieldDescriptor,
    rawKey: String
) {
    when (keyFieldDesc.type) {
        FieldDescriptor.Type.BOOL -> element.setBoolKey(rawKey == "true")
        FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32,
        FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64 -> {
            rawKey.toLongOrNull()?.let { element.setIntKey(it) }
        }
        FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32,
        FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 -> {
            rawKey.toLongOrNull()?.let { element.setUintKey(it) }
        }
        FieldDescriptor.Type.STRING -> element.setStringKey(rawKey)
        else -> {} // unsupported key type — leave unset
    }
}


/**
 * Determines the sub-constraint context from the field path.
 * Returns one of: "repeated.items", "map.keys", "map.values", or null for top-level.
 */
private fun detectSubConstraintContext(fieldPath: String): String? {
    // Map key: path ends with "[...].key" (e.g., "val[someKey].key")
    if (Regex("""\[.+]\.key$""").containsMatchIn(fieldPath)) return "map.keys"
    // Map value: path ends with "[...].value" (e.g., "val[someKey].value")
    if (Regex("""\[.+]\.value$""").containsMatchIn(fieldPath)) return "map.values"
    // Repeated/map item: path ends with "[...]" (no further ".field" after the bracket)
    // e.g., "val[0]" or "val[key]" — direct item-level violation
    // But NOT "val[1].val" which is a field within a nested message
    if (Regex("""\[.+]$""").containsMatchIn(fieldPath)) return "repeated.items"
    return null
}

/**
 * Builds a rule FieldPath from a rule ID string.
 *
 * Rule IDs follow the pattern "type.rule" (e.g., "bool.const", "string.min_len").
 * This maps to the FieldConstraints proto structure:
 *   FieldConstraints.bool → BoolRules.const
 *
 * For sub-constraints (repeated items, map keys/values), the rule path is prefixed:
 *   repeated → items → {type} → {rule}
 *   map → keys → {type} → {rule}
 *   map → values → {type} → {rule}
 */
private fun buildRulePath(ruleId: String, fieldPath: String, messageDescriptor: Descriptors.Descriptor, celIndex: Int = -1, isCelExpression: Boolean = false): FieldPath? {
    val constraintsDescriptor = build.buf.validate.FieldRules.getDescriptor()

    // Special case: plain "required" with no type prefix maps directly to FieldRules.required
    if (ruleId == "required") {
        return buildRequiredRulePath()
    }

    // CEL rule IDs: any rule that doesn't match the standard "type.rule" pattern
    // and isn't a special case (required, message.oneof, etc.) is a CEL rule.
    // CEL rules map to: cel[index] in the FieldRules or MessageRules descriptor.
    // For predefined rules, the path goes through the type-specific rules message.
    val celPath = buildCelRulePath(ruleId, fieldPath, messageDescriptor, celIndex, isCelExpression)
    if (celPath != null) return celPath

    // Special case: "string.well_known_regex" (and its sub-variants like
    // "string.well_known_regex.header_name", "string.well_known_regex.header_name_empty",
    // "string.well_known_regex.header_value_empty") all map to string → well_known_regex.
    if (ruleId == "string.well_known_regex" || ruleId.startsWith("string.well_known_regex.")) {
        return buildStringWellKnownRegexRulePath(constraintsDescriptor, fieldPath)
    }

    val parts = ruleId.split(".")
    if (parts.size < 2) return null

    val typePart = parts[0]  // e.g., "bool", "string", "int32"
    val rulePart = parts.subList(1, parts.size).joinToString("_")  // e.g., "const", "min_len"

    // Special cases
    if (typePart == "message" && rulePart == "required") {
        return buildRequiredRulePath()
    }
    if (typePart == "oneof" && rulePart == "required") {
        return null // oneof required is at a different level
    }
    if (typePart == "enum" && rulePart == "defined_only") {
        return buildEnumDefinedOnlyRulePath(constraintsDescriptor)
    }

    val builder = FieldPath.newBuilder()

    // Detect if this violation comes from a repeated items or map keys/values sub-constraint
    val subContext = detectSubConstraintContext(fieldPath)

    // For sub-constraints, navigate through the container (repeated/map) and sub-field
    // (items/keys/values) first, prepending those path elements.
    val effectiveConstraintsDescriptor: Descriptors.Descriptor = when (subContext) {
        "repeated.items" -> {
            val repeatedField = constraintsDescriptor.findFieldByName("repeated") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(repeatedField.number)
                    .setFieldName("repeated")
                    .setFieldType(toFieldType(repeatedField))
            )
            val repeatedRulesDesc = repeatedField.messageType
            val itemsField = repeatedRulesDesc.findFieldByName("items") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(itemsField.number)
                    .setFieldName("items")
                    .setFieldType(toFieldType(itemsField))
            )
            itemsField.messageType
        }
        "map.keys" -> {
            val mapField = constraintsDescriptor.findFieldByName("map") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(mapField.number)
                    .setFieldName("map")
                    .setFieldType(toFieldType(mapField))
            )
            val mapRulesDesc = mapField.messageType
            val keysField = mapRulesDesc.findFieldByName("keys") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(keysField.number)
                    .setFieldName("keys")
                    .setFieldType(toFieldType(keysField))
            )
            keysField.messageType
        }
        "map.values" -> {
            val mapField = constraintsDescriptor.findFieldByName("map") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(mapField.number)
                    .setFieldName("map")
                    .setFieldType(toFieldType(mapField))
            )
            val mapRulesDesc = mapField.messageType
            val valuesField = mapRulesDesc.findFieldByName("values") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(valuesField.number)
                    .setFieldName("values")
                    .setFieldType(toFieldType(valuesField))
            )
            valuesField.messageType
        }
        else -> constraintsDescriptor
    }

    // Find the type field in FieldConstraints (e.g., "bool", "string", "int32")
    val typeField = effectiveConstraintsDescriptor.findFieldByName(typePart) ?: return null

    builder.addElements(
        FieldPathElement.newBuilder()
            .setFieldNumber(typeField.number)
            .setFieldName(typePart)
            .setFieldType(toFieldType(typeField))
    )

    // Find the rule field in the type-specific rules message
    if (typeField.type == FieldDescriptor.Type.MESSAGE) {
        val rulesDescriptor = typeField.messageType

        // Handle combined range rule IDs like "gt_lt", "gte_lte", "gt_lt_exclusive", etc.
        // The rule path always points to the lower bound field (gt or gte).
        val rangeMatch = Regex("""^(g(?:t|te))_(l(?:t|te))(?:_exclusive)?$""").find(rulePart)
        // Strip _empty suffix before descriptor lookup (e.g., "ipv4_empty" → "ipv4")
        val baseRulePart = rulePart.removeSuffix("_empty")
        val resolvedRulePart = rangeMatch?.groupValues?.get(1) ?: baseRulePart

        val ruleField = rulesDescriptor.findFieldByName(resolvedRulePart)
        if (ruleField != null) {
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(ruleField.number)
                    .setFieldName(resolvedRulePart)
                    .setFieldType(toFieldType(ruleField))
            )
        } else {
            // Not a standard field — try to find as an extension (predefined rules)
            val ext = findExtensionOnRules(rulesDescriptor, ruleId, messageDescriptor)
            if (ext != null) {
                builder.addElements(
                    FieldPathElement.newBuilder()
                        .setFieldNumber(ext.number)
                        .setFieldName("[${ext.fullName}]")
                        .setFieldType(toFieldType(ext))
                )
            } else {
                // Neither standard field nor extension found — this is likely a CEL rule
                // with a rule_id that happens to start with a standard type prefix.
                // Fall back to CEL path.
                val celFieldName = if (isCelExpression) "cel_expression" else "cel"
                val celField = constraintsDescriptor.findFieldByName(celFieldName)
                if (celField != null) {
                    val idx = if (celIndex >= 0) celIndex.toLong() else 0L
                    return FieldPath.newBuilder().addElements(
                        FieldPathElement.newBuilder()
                            .setFieldNumber(celField.number)
                            .setFieldName(celFieldName)
                            .setFieldType(toFieldType(celField))
                            .setIndex(idx)
                    ).build()
                }
            }
        }
    }

    return builder.build()
}

/**
 * Builds the rule path for a message-level CEL rule.
 * Points to MessageRules.cel[index] in the message constraint options.
 */
private fun buildMessageCelRulePath(ruleId: String, messageDescriptor: Descriptors.Descriptor, celIndex: Int = -1, isCelExpression: Boolean = false): FieldPath? {
    val messageRulesDescriptor = build.buf.validate.MessageRules.getDescriptor()
    val builder = FieldPath.newBuilder()
    val index = if (celIndex >= 0) celIndex.toLong() else 0L

    if (isCelExpression) {
        val celExprField = messageRulesDescriptor.findFieldByName("cel_expression")
        if (celExprField != null) {
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(celExprField.number)
                    .setFieldName("cel_expression")
                    .setFieldType(toFieldType(celExprField))
                    .setIndex(index)
            )
        }
    } else {
        val celField = messageRulesDescriptor.findFieldByName("cel")
        if (celField != null) {
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(celField.number)
                    .setFieldName("cel")
                    .setFieldType(toFieldType(celField))
                    .setIndex(index)
            )
        }
    }

    return builder.build()
}

/**
 * Attempts to build a rule path for a CEL rule ID.
 * Returns null if the ruleId matches the standard "type.rule" pattern (handled elsewhere).
 *
 * CEL rule paths map to either:
 * - FieldRules.cel[index] for field-level CEL rules
 * - The predefined extension path for predefined CEL rules
 */
private fun buildCelRulePath(ruleId: String, fieldPath: String, messageDescriptor: Descriptors.Descriptor, celIndex: Int = -1, isCelExpression: Boolean = false): FieldPath? {
    val constraintsDescriptor = build.buf.validate.FieldRules.getDescriptor()

    // Standard type prefixes that indicate non-CEL rules
    val standardTypePrefixes = setOf(
        "bool", "string", "bytes", "int32", "int64", "uint32", "uint64",
        "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64",
        "float", "double", "enum", "repeated", "map", "duration", "timestamp",
        "any", "message", "oneof", "field_mask"
    )
    val parts = ruleId.split(".")
    if (parts.size >= 2 && parts[0] in standardTypePrefixes) {
        // This looks like a standard rule — let the normal handler process it
        return null
    }

    // This is a CEL rule. Build the path to FieldRules.cel field.
    val builder = FieldPath.newBuilder()
    val index = if (celIndex >= 0) celIndex.toLong() else 0L
    val celFieldName = if (isCelExpression) "cel_expression" else "cel"

    // Helper to add the final cel/cel_expression element
    fun addCelElement(constraintsDesc: Descriptors.Descriptor) {
        val celField = constraintsDesc.findFieldByName(celFieldName) ?: return
        builder.addElements(
            FieldPathElement.newBuilder()
                .setFieldNumber(celField.number)
                .setFieldName(celFieldName)
                .setFieldType(toFieldType(celField))
                .setIndex(index)
        )
    }

    // For sub-constraints, prefix with repeated.items / map.keys / map.values
    val subContext = detectSubConstraintContext(fieldPath)
    when (subContext) {
        "repeated.items" -> {
            val repeatedField = constraintsDescriptor.findFieldByName("repeated") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(repeatedField.number)
                    .setFieldName("repeated")
                    .setFieldType(toFieldType(repeatedField))
            )
            val repeatedRulesDesc = repeatedField.messageType
            val itemsField = repeatedRulesDesc.findFieldByName("items") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(itemsField.number)
                    .setFieldName("items")
                    .setFieldType(toFieldType(itemsField))
            )
            addCelElement(itemsField.messageType)
        }
        "map.keys" -> {
            val mapField = constraintsDescriptor.findFieldByName("map") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(mapField.number)
                    .setFieldName("map")
                    .setFieldType(toFieldType(mapField))
            )
            val mapRulesDesc = mapField.messageType
            val keysField = mapRulesDesc.findFieldByName("keys") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(keysField.number)
                    .setFieldName("keys")
                    .setFieldType(toFieldType(keysField))
            )
            addCelElement(keysField.messageType)
        }
        "map.values" -> {
            val mapField = constraintsDescriptor.findFieldByName("map") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(mapField.number)
                    .setFieldName("map")
                    .setFieldType(toFieldType(mapField))
            )
            val mapRulesDesc = mapField.messageType
            val valuesField = mapRulesDesc.findFieldByName("values") ?: return null
            builder.addElements(
                FieldPathElement.newBuilder()
                    .setFieldNumber(valuesField.number)
                    .setFieldName("values")
                    .setFieldType(toFieldType(valuesField))
            )
            addCelElement(valuesField.messageType)
        }
        else -> {
            addCelElement(constraintsDescriptor)
        }
    }

    return builder.build()
}

private fun buildRequiredRulePath(): FieldPath {
    val rulesDescriptor = build.buf.validate.FieldRules.getDescriptor()
    val requiredField = rulesDescriptor.findFieldByName("required")
        ?: return FieldPath.getDefaultInstance()
    return FieldPath.newBuilder()
        .addElements(
            FieldPathElement.newBuilder()
                .setFieldNumber(requiredField.number)
                .setFieldName("required")
                .setFieldType(toFieldType(requiredField))
        )
        .build()
}

/**
 * Builds the rule path for any "string.well_known_regex*" rule ID.
 * All variants (bare, .header_name, .header_name_empty, .header_value_empty, etc.)
 * resolve to: string (TYPE_MESSAGE, field 14) → well_known_regex (TYPE_ENUM, field 24).
 */
private fun buildStringWellKnownRegexRulePath(
    constraintsDescriptor: Descriptors.Descriptor,
    @Suppress("UNUSED_PARAMETER") fieldPath: String
): FieldPath? {
    val builder = FieldPath.newBuilder()

    // Removed sub-constraint context prefixing — rule path should be direct (string → well_known_regex)

    val stringField = constraintsDescriptor.findFieldByName("string") ?: return null
    builder.addElements(
        FieldPathElement.newBuilder()
            .setFieldNumber(stringField.number)
            .setFieldName("string")
            .setFieldType(toFieldType(stringField))
    )

    val wellKnownRegexField = stringField.messageType.findFieldByName("well_known_regex") ?: return null
    builder.addElements(
        FieldPathElement.newBuilder()
            .setFieldNumber(wellKnownRegexField.number)
            .setFieldName("well_known_regex")
            .setFieldType(toFieldType(wellKnownRegexField))
    )

    return builder.build()
}

private fun buildEnumDefinedOnlyRulePath(constraintsDescriptor: Descriptors.Descriptor): FieldPath {
    val enumField = constraintsDescriptor.findFieldByName("enum")!!
    val definedOnlyField = enumField.messageType.findFieldByName("defined_only")!!
    return FieldPath.newBuilder()
        .addElements(
            FieldPathElement.newBuilder()
                .setFieldNumber(enumField.number)
                .setFieldName("enum")
                .setFieldType(toFieldType(enumField))
        )
        .addElements(
            FieldPathElement.newBuilder()
                .setFieldNumber(definedOnlyField.number)
                .setFieldName("defined_only")
                .setFieldType(toFieldType(definedOnlyField))
        )
        .build()
}

/**
 * Finds an extension field on a rules descriptor that matches the predefined rule ID.
 * Searches the message's file descriptor and its dependencies for extensions targeting
 * the given rules message type.
 */
private fun findExtensionOnRules(
    rulesDescriptor: Descriptors.Descriptor,
    ruleId: String,
    messageDescriptor: Descriptors.Descriptor
): FieldDescriptor? {
    // Collect all file descriptors reachable from the message's file
    val visited = mutableSetOf<String>()
    val filesToSearch = mutableListOf<Descriptors.FileDescriptor>()

    fun collectFiles(fd: Descriptors.FileDescriptor) {
        if (fd.fullName in visited) return
        visited.add(fd.fullName)
        filesToSearch.add(fd)
        for (dep in fd.dependencies) {
            collectFiles(dep)
        }
    }
    collectFiles(messageDescriptor.file)

    // Search for extensions targeting our rules descriptor
    val rulesFullName = rulesDescriptor.fullName
    val expectedExtName = ruleId.replace(".", "_")
    // The ruleId format is "type.rule_name.suffix" e.g. "sint32.even.edition_2023"
    // The extension name strips the type prefix dots: "sint32_even_edition_2023"
    // Also try without the type prefix (the type part is the field in FieldConstraints)
    val typePart = ruleId.substringBefore(".")
    val rulePartUnderscored = ruleId.substringAfter(".").replace(".", "_")

    var bestMatch: FieldDescriptor? = null
    for (file in filesToSearch) {
        for (ext in file.extensions) {
            if (ext.containingType.fullName == rulesFullName) {
                val extSimpleName = ext.name
                // Exact match on full rule_id with dots → underscores
                if (extSimpleName == expectedExtName) {
                    return ext
                }
                // Match rule part only (without type prefix): e.g., "even_edition_2023"
                if (extSimpleName == "${typePart}_$rulePartUnderscored") {
                    return ext
                }
                // Match just the rule part suffix (extension may have different type prefix)
                if (extSimpleName.endsWith("_$rulePartUnderscored")) {
                    bestMatch = ext
                }
                // Match by suffix: check if the extension name ends with the same suffix
                // (e.g., rule_id "timestamp.time_range.proto2" → suffix "_proto2" or "_edition_2023")
                if (bestMatch == null) {
                    val suffixParts = ruleId.split(".")
                    if (suffixParts.size >= 3) {
                        val suffix = suffixParts.last() // "proto2" or "edition_2023"
                        if (extSimpleName.endsWith("_$suffix") && extSimpleName.startsWith("${typePart}_")) {
                            bestMatch = ext
                        }
                    }
                }
            }
        }
    }
    return bestMatch
}

private fun toFieldType(fd: FieldDescriptor): com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type {
    return when (fd.type) {
        FieldDescriptor.Type.DOUBLE -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE
        FieldDescriptor.Type.FLOAT -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT
        FieldDescriptor.Type.INT64 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64
        FieldDescriptor.Type.UINT64 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64
        FieldDescriptor.Type.INT32 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32
        FieldDescriptor.Type.FIXED64 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64
        FieldDescriptor.Type.FIXED32 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32
        FieldDescriptor.Type.BOOL -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL
        FieldDescriptor.Type.STRING -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING
        FieldDescriptor.Type.GROUP -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_GROUP
        FieldDescriptor.Type.MESSAGE -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE
        FieldDescriptor.Type.BYTES -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES
        FieldDescriptor.Type.UINT32 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32
        FieldDescriptor.Type.ENUM -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM
        FieldDescriptor.Type.SFIXED32 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32
        FieldDescriptor.Type.SFIXED64 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64
        FieldDescriptor.Type.SINT32 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32
        FieldDescriptor.Type.SINT64 -> com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64
    }
}

// ── Type Resolution ──

/**
 * Maps well-known proto type names (e.g. "google.protobuf.Any") to their Java class names.
 * The proto package "google.protobuf" maps to the Java package "com.google.protobuf".
 */
private val WELL_KNOWN_TYPE_MAP: Map<String, String> = mapOf(
    "google.protobuf.Any" to "com.google.protobuf.Any",
    "google.protobuf.Timestamp" to "com.google.protobuf.Timestamp",
    "google.protobuf.Duration" to "com.google.protobuf.Duration",
    "google.protobuf.FieldMask" to "com.google.protobuf.FieldMask",
    "google.protobuf.Struct" to "com.google.protobuf.Struct",
    "google.protobuf.Value" to "com.google.protobuf.Value",
    "google.protobuf.ListValue" to "com.google.protobuf.ListValue",
    "google.protobuf.DoubleValue" to "com.google.protobuf.DoubleValue",
    "google.protobuf.FloatValue" to "com.google.protobuf.FloatValue",
    "google.protobuf.Int64Value" to "com.google.protobuf.Int64Value",
    "google.protobuf.UInt64Value" to "com.google.protobuf.UInt64Value",
    "google.protobuf.Int32Value" to "com.google.protobuf.Int32Value",
    "google.protobuf.UInt32Value" to "com.google.protobuf.UInt32Value",
    "google.protobuf.BoolValue" to "com.google.protobuf.BoolValue",
    "google.protobuf.StringValue" to "com.google.protobuf.StringValue",
    "google.protobuf.BytesValue" to "com.google.protobuf.BytesValue",
)

private fun resolveMessageClass(protoTypeName: String): Class<*>? {
    // Check the well-known type map first (google.protobuf.* → com.google.protobuf.*)
    WELL_KNOWN_TYPE_MAP[protoTypeName]?.let { javaName ->
        tryLoadClass(javaName)?.let { return it }
    }
    tryLoadClass(protoTypeName)?.let { return it }
    val parts = protoTypeName.split('.')
    for (i in parts.size - 1 downTo 1) {
        val pkg = parts.subList(0, i).joinToString(".")
        val nested = parts.subList(i, parts.size).joinToString("$")
        tryLoadClass("$pkg.$nested")?.let { return it }
    }
    return null
}

private fun resolveValidatorClass(protoTypeName: String): Class<*>? {
    val parts = protoTypeName.split('.')
    val messageName = parts.last()
    val pkg = parts.dropLast(1).joinToString(".")
    tryLoadClass("$pkg.${messageName}ValidatorKt")?.let { return it }
    if (parts.size > 1) {
        for (i in parts.size - 2 downTo 1) {
            val candidatePkg = parts.subList(0, i).joinToString(".")
            val messageNames = parts.subList(i, parts.size).joinToString("")
            tryLoadClass("$candidatePkg.${messageNames}ValidatorKt")?.let { return it }
        }
    }
    return null
}

private fun tryLoadClass(name: String): Class<*>? {
    return try {
        Class.forName(name)
    } catch (_: ClassNotFoundException) {
        null
    }
}

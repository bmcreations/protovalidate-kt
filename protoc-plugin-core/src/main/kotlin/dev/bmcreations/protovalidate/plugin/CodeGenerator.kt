package dev.bmcreations.protovalidate.plugin

import dev.bmcreations.protovalidate.plugin.cel.*
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

object CodeGenerator {

    fun generate(
        fileProto: FileDescriptorProto,
        validatedTypes: Map<String, String>,
        extractor: RuleExtractor
    ): List<CodeGeneratorResponse.File> {
        val javaPackage = if (fileProto.options.hasJavaPackage()) {
            fileProto.options.javaPackage
        } else {
            fileProto.`package`
        }

        val javaMultipleFiles = fileProto.options.javaMultipleFiles

        val outerClassName = if (fileProto.options.hasJavaOuterClassname()) {
            fileProto.options.javaOuterClassname
        } else {
            outerClassNameFromFileName(fileProto.name)
        }

        val results = mutableListOf<CodeGeneratorResponse.File>()

        // Determine file syntax for presence-checking decisions
        val fileSyntax = when {
            fileProto.syntax == "editions" || fileProto.hasEdition() -> FileSyntax.EDITIONS
            fileProto.syntax == "proto2" || fileProto.syntax.isEmpty() -> FileSyntax.PROTO2
            else -> FileSyntax.PROTO3
        }

        for (messageProto in fileProto.messageTypeList) {
            generateForMessage(
                messageProto = messageProto,
                javaPackage = javaPackage,
                javaMultipleFiles = javaMultipleFiles,
                outerClassName = outerClassName,
                parentNames = emptyList(),
                validatedTypes = validatedTypes,
                extractor = extractor,
                results = results,
                fileSyntax = fileSyntax
            )
        }

        return results
    }

    private fun generateForMessage(
        messageProto: DescriptorProto,
        javaPackage: String,
        javaMultipleFiles: Boolean,
        outerClassName: String,
        parentNames: List<String>,
        validatedTypes: Map<String, String>,
        extractor: RuleExtractor,
        results: MutableList<CodeGeneratorResponse.File>,
        fileSyntax: FileSyntax
    ) {
        // Check disabled / ignored
        if (messageProto.options != null) {
            if (extractor.isMessageDisabled(messageProto.options)) return
            if (extractor.isMessageIgnored(messageProto.options)) return
        }

        // Collect fields with validation rules
        val validatedFields = messageProto.fieldList.mapNotNull { field ->
            val rules = extractor.getFieldRules(field.options)
            if (rules != null) field to rules else null
        }

        // Collect oneofs with required constraint
        val requiredOneofs = messageProto.oneofDeclList.mapIndexedNotNull { index, oneof ->
            if (oneof.options != null && extractor.isOneofRequired(oneof.options)) {
                index to oneof
            } else null
        }

        // Collect message-level oneof rules
        val messageOneofRules = if (messageProto.options != null) {
            extractor.getMessageOneofRules(messageProto.options)
        } else {
            emptyList()
        }

        // Collect message-level CEL rules
        val messageCelRules = if (messageProto.options != null) {
            extractor.getMessageCelRules(messageProto.options)
        } else {
            emptyList()
        }

        // Check if any message/group fields reference types that have validators,
        // including map fields whose value type has a validator.
        val hasRecursiveFields = messageProto.fieldList.any { field ->
            if (field.type != Type.TYPE_MESSAGE && field.type != Type.TYPE_GROUP) return@any false
            if (validatedTypes.containsKey(field.typeName)) return@any true
            // For map fields: the typeName points to a synthetic entry type. Check if the
            // value field of that entry type has a validator.
            val entryType = messageProto.nestedTypeList.firstOrNull { nested ->
                nested.options?.mapEntry == true && field.typeName.endsWith(".${nested.name}")
            }
            if (entryType != null) {
                val valueField = entryType.fieldList.firstOrNull { it.name == "value" }
                valueField != null && validatedTypes.containsKey(valueField.typeName)
            } else false
        }

        // Filter out message-level CEL rules that use unsupported library functions.
        // If ALL message CEL rules are unsupported and there are no other rules, skip generation
        // entirely so that hand-written validators can handle these types.
        val supportedMessageCelRules = messageCelRules.filter { rule ->
            try {
                val ast = CelParser(rule.expression).parse()
                // Also try transpiling to catch unsupported functions (isHostname, isEmail, etc.)
                val celCtx = CelContext(
                    thisAccessor = "this",
                    ruleValue = null,
                    fieldType = CelFieldType.MESSAGE,
                )
                CelTranspiler.transpile(ast, celCtx)
                true
            } catch (_: CelRuntimeException) {
                true // dyn() etc. — will emit a throw at runtime, still "supported"
            } catch (_: CelParseException) {
                false
            }
        }
        val hasOtherRules = validatedFields.isNotEmpty() || requiredOneofs.isNotEmpty() ||
            hasRecursiveFields || messageOneofRules.isNotEmpty()

        val hasUnsupportedCelRules = messageCelRules.size > supportedMessageCelRules.size

        if (hasOtherRules || supportedMessageCelRules.isNotEmpty() || hasUnsupportedCelRules) {
            // Validate message.oneof rules at compile time
            val messageFieldNames = messageProto.fieldList.map { it.name }.toSet()
            val compilationError = validateMessageOneofRules(messageOneofRules, messageFieldNames)

            val code = when {
                compilationError != null -> {
                    generateMessageOneofErrorValidator(
                        messageProto = messageProto,
                        javaPackage = javaPackage,
                        javaMultipleFiles = javaMultipleFiles,
                        outerClassName = outerClassName,
                        parentNames = parentNames,
                        errorMessage = compilationError
                    )
                }
                else -> {
                    // Check for type mismatches — if any field has rules that don't match its proto type,
                    // generate an error validator instead of broken code.
                    val mismatch = validatedFields.firstOrNull { (field, rules) ->
                        rules.type != RuleType.NONE && !isTypeCompatible(field, rules.type)
                    }

                    if (mismatch != null) {
                        val (field, rules) = mismatch
                        generateErrorValidator(
                            messageProto = messageProto,
                            javaPackage = javaPackage,
                            javaMultipleFiles = javaMultipleFiles,
                            outerClassName = outerClassName,
                            parentNames = parentNames,
                            fieldName = field.name,
                            ruleType = rules.type,
                            fieldType = field.type
                        )
                    } else {
                        generateValidator(
                            messageProto = messageProto,
                            javaPackage = javaPackage,
                            javaMultipleFiles = javaMultipleFiles,
                            outerClassName = outerClassName,
                            parentNames = parentNames,
                            validatedFields = validatedFields,
                            requiredOneofs = requiredOneofs,
                            messageOneofRules = messageOneofRules,
                            messageCelRules = supportedMessageCelRules,
                            validatedTypes = validatedTypes,
                            fileSyntax = fileSyntax
                        )
                    }
                }
            }

            val allNames = parentNames + messageProto.name
            val fileName = allNames.joinToString("") + "Validator.kt"
            val filePath = javaPackage.replace(".", "/") + "/" + fileName

            results.add(
                CodeGeneratorResponse.File.newBuilder()
                    .setName(filePath)
                    .setContent(code)
                    .build()
            )
        }

        // Recurse into nested messages
        for (nested in messageProto.nestedTypeList) {
            if (nested.options?.mapEntry == true) continue

            generateForMessage(
                messageProto = nested,
                javaPackage = javaPackage,
                javaMultipleFiles = javaMultipleFiles,
                outerClassName = outerClassName,
                parentNames = parentNames + messageProto.name,
                validatedTypes = validatedTypes,
                extractor = extractor,
                results = results,
                fileSyntax = fileSyntax
            )
        }
    }

    /**
     * Validates message.oneof rules at codegen time.
     * Returns a compilation error message if invalid, null if valid.
     *
     * Rules:
     * - At least one field must be specified in each oneof rule
     * - All field names must exist in the message
     * - No duplicate field names within a single oneof rule
     */
    private fun validateMessageOneofRules(
        rules: List<MessageOneofRuleSet>,
        messageFieldNames: Set<String>
    ): String? {
        for (rule in rules) {
            if (rule.fields.isEmpty()) {
                return "at least one field must be specified in oneof rule"
            }
            val seen = mutableSetOf<String>()
            for (fieldName in rule.fields) {
                if (fieldName !in messageFieldNames) {
                    return "field $fieldName not found in message"
                }
                if (!seen.add(fieldName)) {
                    return "duplicate $fieldName in oneof rule"
                }
            }
        }
        return null
    }

    private fun generateValidator(
        messageProto: DescriptorProto,
        javaPackage: String,
        javaMultipleFiles: Boolean,
        outerClassName: String,
        parentNames: List<String>,
        validatedFields: List<Pair<com.google.protobuf.DescriptorProtos.FieldDescriptorProto, FieldRuleSet>>,
        requiredOneofs: List<Pair<Int, com.google.protobuf.DescriptorProtos.OneofDescriptorProto>>,
        messageOneofRules: List<MessageOneofRuleSet>,
        messageCelRules: List<MessageCelRule> = emptyList(),
        validatedTypes: Map<String, String>,
        fileSyntax: FileSyntax
    ): String {
        // Build the fully-qualified receiver type
        val receiverType = buildReceiverType(
            javaMultipleFiles = javaMultipleFiles,
            outerClassName = outerClassName,
            parentNames = parentNames,
            messageName = messageProto.name
        )

        // Build a lookup map: field name → FieldDescriptorProto for presence checks
        val fieldByName = messageProto.fieldList.associateBy { it.name }

        // Collect all field names that participate in any message.oneof group.
        // These fields get implicit ignore (IGNORE_IF_ZERO_VALUE) for their field-level rules,
        // unless the field explicitly overrides its ignore mode (i.e., has a non-UNSPECIFIED ignore).
        val messageOneofFieldNames = messageOneofRules
            .flatMap { it.fields }
            .toSet()

        // Generate the body first to collect needed imports
        val bodySb = StringBuilder()
        val neededImports = mutableSetOf<String>()
        val ctx = EmitContext(
            sb = bodySb,
            indent = "    ",
            validatedTypes = validatedTypes,
            neededImports = neededImports,
            fileSyntax = fileSyntax,
            nestedTypes = messageProto.nestedTypeList
        )

        bodySb.appendLine("fun $receiverType.validate(): ValidationResult {")
        bodySb.appendLine("    val violations = mutableListOf<FieldViolation>()")

        // Emit `now` binding if any CEL expression references it
        val allCelExpressions = buildList {
            for ((_, rules) in validatedFields) {
                for (cel in rules.celRules) add(cel.expression)
                for (cel in rules.predefinedCelRules) add(cel.expression)
            }
            for (cel in messageCelRules) add(cel.expression)
        }
        val needsNow = allCelExpressions.any { expr ->
            try {
                val ast = CelParser(expr).parse()
                CelTranspiler.referencesNow(ast)
            } catch (_: Exception) { false }
        }
        if (needsNow) {
            bodySb.appendLine("    val _celNow = System.currentTimeMillis() / 1000L")
        }

        // Emit oneof required checks (native proto oneof)
        for ((_, oneof) in requiredOneofs) {
            val oneofCaseName = snakeToCamel(oneof.name) + "Case"
            val notSetValue = oneof.name.replace("_", "").uppercase() + "_NOT_SET"
            bodySb.appendLine("    if (${snakeToCamelLower(oneof.name)}Case == $receiverType.$oneofCaseName.$notSetValue) {")
            bodySb.appendLine("        violations += FieldViolation(\"${oneof.name}\", \"required\", \"exactly one field is required in oneof\")")
            bodySb.appendLine("    }")
        }

        // Emit message.oneof checks
        for ((oneofIndex, oneofRule) in messageOneofRules.withIndex()) {
            emitMessageOneofCheck(oneofRule, oneofIndex, fieldByName, fileSyntax, bodySb)
        }

        // Build oneof index → name map for guarding field validation.
        // Skip synthetic oneofs (proto3 optional fields use synthetic oneofs with names starting with "_").
        val realOneofIndices = mutableSetOf<Int>()
        val oneofNames = mutableMapOf<Int, String>()
        messageProto.oneofDeclList.forEachIndexed { i, o ->
            // Synthetic oneofs start with "_" and contain exactly one field
            val isSynthetic = o.name.startsWith("_")
            if (!isSynthetic) {
                realOneofIndices.add(i)
                oneofNames[i] = o.name
            }
        }

        // Emit field checks
        for ((field, rules) in validatedFields) {
            // Determine if this field is subject to implicit ignore from a message.oneof group.
            // A field in a message.oneof group should have its validations skipped when not set,
            // unless the field has an explicit ignore override (ALWAYS skips everything, other
            // explicit modes take precedence over the implicit-ignore behavior).
            val isInMessageOneof = field.name in messageOneofFieldNames
            val hasExplicitIgnoreOverride = rules.ignore != IgnoreMode.UNSPECIFIED

            // If IGNORE_ALWAYS is set explicitly, FieldEmitter will skip everything — no wrapping needed.
            // For other cases where the field is in a message.oneof group and doesn't have an
            // explicit ignore override, we apply implicit ignore (wrap in a "field is set" guard).
            val needsImplicitIgnoreWrap = isInMessageOneof && !hasExplicitIgnoreOverride &&
                rules.ignore != IgnoreMode.ALWAYS

            if (field.hasOneofIndex() && field.oneofIndex in realOneofIndices) {
                val oneofName = oneofNames[field.oneofIndex]
                if (oneofName != null) {
                    val caseAccessor = "${snakeToCamelLower(oneofName)}Case"
                    val caseEnum = "$receiverType.${snakeToCamel(oneofName)}Case"
                    val caseValue = field.name.uppercase()

                    // For required fields in a oneof, emit the required check OUTSIDE
                    // the oneof case guard — required means the field must be set even
                    // when another member is active. Only type-specific rules go inside.
                    if (rules.message?.required == true && rules.ignore == IgnoreMode.ALWAYS) {
                        // IGNORE_ALWAYS takes precedence — skip all validation including required
                    } else if (rules.message?.required == true) {
                        val quotedField = "\"${field.name}\""
                        // Required = this field must be the active case
                        bodySb.appendLine("    Validators.checkRequired($caseAccessor == $caseEnum.$caseValue, $quotedField)?.let { violations += it }")
                        // Type-specific rules only when this field is active
                        val hasTypeRules = rules.type != RuleType.NONE
                        if (hasTypeRules) {
                            bodySb.appendLine("    if ($caseAccessor == $caseEnum.$caseValue) {")
                            val oneofCtx = EmitContext(
                                sb = bodySb,
                                indent = "        ",
                                validatedTypes = validatedTypes,
                                neededImports = neededImports,
                                fileSyntax = fileSyntax,
                                nestedTypes = messageProto.nestedTypeList
                            )
                            // Emit only type-specific rules, skip the required check
                            val rulesWithoutRequired = rules.copy(message = rules.message.copy(required = false))
                            FieldEmitter.emit(field, rulesWithoutRequired, "", oneofCtx)
                            bodySb.appendLine("    }")
                        }
                    } else {
                        // Only validate this field when it's the active oneof member
                        bodySb.appendLine("    if ($caseAccessor == $caseEnum.$caseValue) {")
                        val oneofCtx = EmitContext(
                            sb = bodySb,
                            indent = "        ",
                            validatedTypes = validatedTypes,
                            neededImports = neededImports,
                            fileSyntax = fileSyntax,
                            nestedTypes = messageProto.nestedTypeList
                        )
                        FieldEmitter.emit(field, rules, "", oneofCtx)
                        bodySb.appendLine("    }")
                    }
                } else {
                    if (needsImplicitIgnoreWrap) {
                        emitWithImplicitIgnoreWrap(field, rules, fileSyntax, bodySb, neededImports, validatedTypes, messageProto.nestedTypeList)
                    } else {
                        FieldEmitter.emit(field, rules, "", ctx)
                    }
                }
            } else {
                if (needsImplicitIgnoreWrap) {
                    emitWithImplicitIgnoreWrap(field, rules, fileSyntax, bodySb, neededImports, validatedTypes, messageProto.nestedTypeList)
                } else {
                    FieldEmitter.emit(field, rules, "", ctx)
                }
            }
        }

        // Emit recursive validation for message/group fields that have no explicit rules
        // but whose type has a validator
        val validatedFieldNames = validatedFields.map { it.first.name }.toSet()
        for (field in messageProto.fieldList) {
            if (field.name in validatedFieldNames) continue // already handled above
            if (field.type != Type.TYPE_MESSAGE && field.type != Type.TYPE_GROUP) continue
            val typeName = field.typeName
            val rawFieldName = field.name
            val rawAccessor = snakeToCamelLower(field.name).removeSurrounding("`")

            // Check if this is a map field (repeated message with a synthetic mapEntry type)
            val entryType = messageProto.nestedTypeList.firstOrNull { nested ->
                nested.options?.mapEntry == true && typeName.endsWith(".${nested.name}")
            }
            if (entryType != null) {
                // Map field: check if the value type has a validator and emit recursive calls
                val valueField = entryType.fieldList.firstOrNull { it.name == "value" }
                if (valueField != null) {
                    val valuePkg = validatedTypes[valueField.typeName]
                    if (valuePkg != null) {
                        neededImports.add(valuePkg)
                        bodySb.appendLine("    for ((mapKey, mapValue) in ${rawAccessor}Map) {")
                        bodySb.appendLine("        mapValue.validate().let { result ->")
                        bodySb.appendLine("            if (result is ValidationResult.Invalid) {")
                        bodySb.appendLine("                violations += result.violations.map { it.copy(field = \"$rawFieldName[\$mapKey].\${it.field}\") }")
                        bodySb.appendLine("            }")
                        bodySb.appendLine("        }")
                        bodySb.appendLine("    }")
                    }
                }
                continue
            }

            val targetPkg = validatedTypes[typeName] ?: continue

            val fieldName = escapeIfKeyword(snakeToCamelLower(field.name))
            neededImports.add(targetPkg)

            if (field.label == Label.LABEL_REPEATED) {
                bodySb.appendLine("    for ((index, item) in ${rawAccessor}List.withIndex()) {")
                bodySb.appendLine("        item.validate().let { result ->")
                bodySb.appendLine("            if (result is ValidationResult.Invalid) {")
                bodySb.appendLine("                violations += result.violations.map { it.copy(field = \"$rawFieldName[\$index].\${it.field}\") }")
                bodySb.appendLine("            }")
                bodySb.appendLine("        }")
                bodySb.appendLine("    }")
            } else {
                val hasAccessor = "has${snakeToCamel(field.name)}()"
                bodySb.appendLine("    if ($hasAccessor) {")
                bodySb.appendLine("        $fieldName.validate().let { result ->")
                bodySb.appendLine("            if (result is ValidationResult.Invalid) {")
                bodySb.appendLine("                violations += result.violations.map { it.copy(field = if (it.field.isEmpty()) \"$rawFieldName\" else \"$rawFieldName.\${it.field}\") }")
                bodySb.appendLine("            }")
                bodySb.appendLine("        }")
                bodySb.appendLine("    }")
            }
        }

        // Emit message-level CEL rules
        val msgFieldNames = messageProto.fieldList.map { it.name }.toSet()
        val msgFieldTypes = messageProto.fieldList.associate {
            snakeToCamelLower(it.name) to it.type
        }
        for ((celIdx, rule) in messageCelRules.withIndex()) {
            // Pre-check: verify the expression is valid at code-generation time.
            // If it references unknown fields or uses type-incompatible methods, emit CompilationError.
            val celError = try {
                val ast = CelParser(rule.expression).parse()
                val fieldRefs = CelTranspiler.extractThisFieldRefs(ast)
                val unknownFields = fieldRefs - msgFieldNames
                if (unknownFields.isNotEmpty()) {
                    "CEL expression references unknown field(s): ${unknownFields.joinToString()}"
                } else {
                    // Check for type-incompatible method calls (e.g., startsWith on an int)
                    detectTypeMismatch(ast, msgFieldTypes)
                }
            } catch (e: CelParseException) {
                e.message ?: "unsupported CEL expression"
            } catch (_: Exception) {
                null
            }

            if (celError != null) {
                bodySb.appendLine("    throw dev.bmcreations.protovalidate.CompilationError(\"${escapeForKotlinString(celError)}\")")
            } else {
                FieldEmitter.emitMessageCelRule(rule, ctx, celIndex = celIdx)
            }
        }

        bodySb.appendLine("    return if (violations.isEmpty()) ValidationResult.Valid")
        bodySb.appendLine("           else ValidationResult.Invalid(violations)")
        bodySb.appendLine("}")
        bodySb.appendLine()

        // Now build the full file with imports
        val sb = StringBuilder()
        sb.appendLine("// Generated by protoc-gen-validate-kt. DO NOT EDIT.")
        sb.appendLine("package $javaPackage")
        sb.appendLine()
        sb.appendLine("import dev.bmcreations.protovalidate.FieldViolation")
        sb.appendLine("import dev.bmcreations.protovalidate.ValidationResult")
        sb.appendLine("import dev.bmcreations.protovalidate.Validators")

        // Add imports for cross-package recursive validate() calls
        for (importPkg in neededImports.sorted()) {
            if (importPkg != javaPackage) {
                sb.appendLine("import $importPkg.validate")
            }
        }

        sb.appendLine()
        sb.append(bodySb)

        return sb.toString()
    }

    /**
     * Emits a message.oneof constraint check.
     *
     * For each oneof group:
     * - Count the number of "set" fields
     * - If required and count == 0: "one of X must be set" or "one of X, Y must be set"
     * - If required and count > 1: "only one of X, Y can be set"
     * - If not required and count > 1: "only one of X, Y can be set"
     *
     * Field "set" detection for proto3 scalar fields: not the zero value.
     * For message fields / explicit-presence fields: has*() check.
     */
    private fun emitMessageOneofCheck(
        rule: MessageOneofRuleSet,
        ruleIndex: Int,
        fieldByName: Map<String, FieldDescriptorProto>,
        fileSyntax: FileSyntax,
        sb: StringBuilder
    ) {
        val fieldNames = rule.fields
        val required = rule.required

        // Build a list of field set-expressions for each field in the group
        // e.g. "strField.isNotEmpty()" for a proto3 string, "hasMsgField()" for a message field
        val setExprs = fieldNames.map { name ->
            val field = fieldByName[name]!! // validated to exist
            fieldSetExpression(field, fileSyntax)
        }

        // Compute the count of set fields using a val.
        // Use ruleIndex to ensure uniqueness across multiple oneof groups with overlapping fields.
        val countVarName = "_oneof_count_$ruleIndex"
        sb.appendLine("    val $countVarName = listOf(${setExprs.joinToString(", ")}).count { it }")

        val fieldList = fieldNames.joinToString(", ")

        if (required) {
            // Exactly one must be set: violations if count == 0 or count > 1
            sb.appendLine("    if ($countVarName == 0) {")
            sb.appendLine("        violations += FieldViolation(\"\", \"message.oneof\", \"one of $fieldList must be set\")")
            sb.appendLine("    }")
            sb.appendLine("    if ($countVarName > 1) {")
            sb.appendLine("        violations += FieldViolation(\"\", \"message.oneof\", \"only one of $fieldList can be set\")")
            sb.appendLine("    }")
        } else {
            // At most one can be set: violation only if count > 1
            sb.appendLine("    if ($countVarName > 1) {")
            sb.appendLine("        violations += FieldViolation(\"\", \"message.oneof\", \"only one of $fieldList can be set\")")
            sb.appendLine("    }")
        }
    }

    /**
     * Returns a Kotlin boolean expression that evaluates to true when the field is "set"
     * (i.e., not the zero/default value for implicit-presence fields, or has*() for
     * explicit-presence fields).
     */
    private fun fieldSetExpression(field: FieldDescriptorProto, fileSyntax: FileSyntax): String {
        val accessor = escapeIfKeyword(snakeToCamelLower(field.name))
        val rawAccessor = snakeToCamelLower(field.name).removeSurrounding("`")

        if (field.label == Label.LABEL_REPEATED) {
            return "${rawAccessor}List.isNotEmpty()"
        }

        // Message fields always have explicit presence
        if (field.type == Type.TYPE_MESSAGE || field.type == Type.TYPE_GROUP) {
            return "has${snakeToCamel(field.name)}()"
        }

        // For explicit-presence fields (proto2 all scalars, proto3 optional, editions explicit)
        val hasPresence = when (fileSyntax) {
            FileSyntax.PROTO2 -> true
            FileSyntax.PROTO3 -> field.proto3Optional || field.hasOneofIndex()
            FileSyntax.EDITIONS -> {
                if (field.options?.hasFeatures() == true) {
                    val presence = field.options.features.fieldPresence
                    presence != com.google.protobuf.DescriptorProtos.FeatureSet.FieldPresence.IMPLICIT
                } else {
                    true
                }
            }
        }

        if (hasPresence) {
            return "has${snakeToCamel(field.name)}()"
        }

        // Proto3 implicit-presence scalar: "set" means not the zero value
        return when (field.type) {
            Type.TYPE_STRING -> "$accessor.isNotEmpty()"
            Type.TYPE_BYTES -> "!${rawAccessor}.isEmpty"
            Type.TYPE_BOOL -> accessor
            Type.TYPE_INT32, Type.TYPE_SINT32, Type.TYPE_SFIXED32 -> "$accessor != 0"
            Type.TYPE_INT64, Type.TYPE_SINT64, Type.TYPE_SFIXED64 -> "$accessor != 0L"
            Type.TYPE_UINT32, Type.TYPE_FIXED32 -> "$accessor != 0"
            Type.TYPE_UINT64, Type.TYPE_FIXED64 -> "$accessor != 0L"
            Type.TYPE_FLOAT -> "$accessor != 0.0f"
            Type.TYPE_DOUBLE -> "$accessor != 0.0"
            Type.TYPE_ENUM -> "${rawAccessor}Value != 0"
            else -> "true" // unknown: assume set
        }
    }

    /**
     * Emits field validation wrapped in an implicit-ignore guard.
     * Fields in a message.oneof group have their validations skipped when not set
     * (as if IGNORE_IF_ZERO_VALUE were set on the field), unless the field explicitly
     * overrides its ignore mode.
     */
    private fun emitWithImplicitIgnoreWrap(
        field: FieldDescriptorProto,
        rules: FieldRuleSet,
        fileSyntax: FileSyntax,
        bodySb: StringBuilder,
        neededImports: MutableSet<String>,
        validatedTypes: Map<String, String>,
        nestedTypes: List<DescriptorProto> = emptyList()
    ) {
        val setExpr = fieldSetExpression(field, fileSyntax)
        bodySb.appendLine("    if ($setExpr) {")
        val innerCtx = EmitContext(
            sb = bodySb,
            indent = "        ",
            validatedTypes = validatedTypes,
            neededImports = neededImports,
            fileSyntax = fileSyntax,
            nestedTypes = nestedTypes
        )
        // Suppress the inner zero-value guard since we're already guarded by setExpr.
        // We do this by replacing UNSPECIFIED ignore with ALWAYS-skip-zero equivalent.
        // The FieldEmitter already has logic that if the outer presence guard is applied,
        // inner guards are suppressed. We replicate that by using a modified rules that
        // tells FieldEmitter the field has already been guarded.
        // The simplest approach: pass the rules as-is but note the outer guard means we don't
        // need field-level zero checks. We can achieve this by wrapping the emit and
        // the FieldEmitter will still apply its own guards correctly inside.
        // However the FieldEmitter doesn't know we're already guarded, so for scalar fields with
        // UNSPECIFIED ignore it might not emit a zero guard (which we've already applied).
        // This is fine: the outer guard handles it correctly.
        FieldEmitter.emit(field, rules, "", innerCtx)
        bodySb.appendLine("    }")
    }

    /**
     * Detects type mismatches in CEL expressions for message-level rules.
     * Returns an error message if a mismatch is found, null otherwise.
     *
     * Checks: string methods (startsWith, endsWith, contains, matches) called on non-string fields.
     */
    private fun detectTypeMismatch(
        expr: CelExpr,
        fieldTypes: Map<String, Type>
    ): String? {
        return when (expr) {
            is CelExpr.Call -> {
                val stringMethods = setOf("startsWith", "endsWith", "contains", "matches")
                if (expr.function in stringMethods && expr.receiver is CelExpr.FieldAccess) {
                    val fieldAccess = expr.receiver as CelExpr.FieldAccess
                    if (fieldAccess.receiver is CelExpr.This) {
                        val fieldType = fieldTypes[fieldAccess.field]
                        if (fieldType != null && fieldType != Type.TYPE_STRING) {
                            return "type mismatch: ${expr.function} called on non-string field ${fieldAccess.field}"
                        }
                    }
                }
                // Recurse into sub-expressions
                expr.receiver?.let { detectTypeMismatch(it, fieldTypes) }?.let { return it }
                for (arg in expr.args) {
                    detectTypeMismatch(arg, fieldTypes)?.let { return it }
                }
                null
            }
            is CelExpr.Binary -> {
                detectTypeMismatch(expr.left, fieldTypes)
                    ?: detectTypeMismatch(expr.right, fieldTypes)
            }
            is CelExpr.Unary -> detectTypeMismatch(expr.operand, fieldTypes)
            is CelExpr.FieldAccess -> detectTypeMismatch(expr.receiver, fieldTypes)
            is CelExpr.IndexAccess -> {
                detectTypeMismatch(expr.receiver, fieldTypes)
                    ?: detectTypeMismatch(expr.key, fieldTypes)
            }
            is CelExpr.Ternary -> {
                detectTypeMismatch(expr.cond, fieldTypes)
                    ?: detectTypeMismatch(expr.then, fieldTypes)
                    ?: detectTypeMismatch(expr.else_, fieldTypes)
            }
            is CelExpr.Comprehension -> {
                detectTypeMismatch(expr.iter, fieldTypes)
                    ?: detectTypeMismatch(expr.body, fieldTypes)
            }
            else -> null
        }
    }

    private fun buildReceiverType(
        javaMultipleFiles: Boolean,
        outerClassName: String,
        parentNames: List<String>,
        messageName: String
    ): String {
        return if (javaMultipleFiles) {
            (parentNames + messageName).joinToString(".")
        } else {
            (listOf(outerClassName) + parentNames + messageName).joinToString(".")
        }
    }

    private fun isTypeCompatible(field: FieldDescriptorProto, ruleType: RuleType): Boolean {
        // Repeated/map fields can have REPEATED or MAP rules, or scalar rules for per-item validation
        if (field.label == Label.LABEL_REPEATED) {
            return ruleType == RuleType.REPEATED || ruleType == RuleType.MAP
        }

        return when (field.type) {
            Type.TYPE_INT32 -> ruleType == RuleType.INT32
            Type.TYPE_INT64 -> ruleType == RuleType.INT64
            Type.TYPE_UINT32 -> ruleType == RuleType.UINT32
            Type.TYPE_UINT64 -> ruleType == RuleType.UINT64
            Type.TYPE_SINT32 -> ruleType == RuleType.SINT32
            Type.TYPE_SINT64 -> ruleType == RuleType.SINT64
            Type.TYPE_FIXED32 -> ruleType == RuleType.FIXED32
            Type.TYPE_FIXED64 -> ruleType == RuleType.FIXED64
            Type.TYPE_SFIXED32 -> ruleType == RuleType.SFIXED32
            Type.TYPE_SFIXED64 -> ruleType == RuleType.SFIXED64
            Type.TYPE_FLOAT -> ruleType == RuleType.FLOAT
            Type.TYPE_DOUBLE -> ruleType == RuleType.DOUBLE
            Type.TYPE_BOOL -> ruleType == RuleType.BOOL
            Type.TYPE_STRING -> ruleType == RuleType.STRING
            Type.TYPE_BYTES -> ruleType == RuleType.BYTES
            Type.TYPE_ENUM -> ruleType == RuleType.ENUM
            Type.TYPE_MESSAGE -> {
                val typeName = field.typeName
                when (ruleType) {
                    RuleType.DURATION -> typeName.endsWith(WellKnownTypes.DURATION)
                    RuleType.TIMESTAMP -> typeName.endsWith(WellKnownTypes.TIMESTAMP)
                    RuleType.ANY -> typeName.endsWith(WellKnownTypes.ANY)
                    RuleType.FIELD_MASK -> typeName.endsWith(WellKnownTypes.FIELD_MASK)
                    else -> {
                        // Allow scalar rules on WKT wrapper types
                        WellKnownTypes.WRAPPER_TO_RULE_TYPE.entries.any { (suffix, expectedType) ->
                            typeName.endsWith(suffix) && ruleType == expectedType
                        }
                    }
                }
            }
            else -> true
        }
    }

    private fun generateErrorValidator(
        messageProto: DescriptorProto,
        javaPackage: String,
        javaMultipleFiles: Boolean,
        outerClassName: String,
        parentNames: List<String>,
        fieldName: String,
        ruleType: RuleType,
        fieldType: Type
    ): String {
        val receiverType = buildReceiverType(javaMultipleFiles, outerClassName, parentNames, messageProto.name)
        val ruleTypeName = ruleType.name.lowercase()
        val fieldTypeName = fieldType.name.removePrefix("TYPE_").lowercase()

        val sb = StringBuilder()
        sb.appendLine("// Generated by protoc-gen-validate-kt. DO NOT EDIT.")
        sb.appendLine("package $javaPackage")
        sb.appendLine()
        sb.appendLine("import dev.bmcreations.protovalidate.CompilationError")
        sb.appendLine("import dev.bmcreations.protovalidate.ValidationResult")
        sb.appendLine()
        sb.appendLine("fun $receiverType.validate(): ValidationResult {")
        sb.appendLine("    throw CompilationError(\"$ruleTypeName rules on $fieldTypeName field\")")
        sb.appendLine("}")
        sb.appendLine()

        return sb.toString()
    }

    private fun generateMessageOneofErrorValidator(
        messageProto: DescriptorProto,
        javaPackage: String,
        javaMultipleFiles: Boolean,
        outerClassName: String,
        parentNames: List<String>,
        errorMessage: String
    ): String {
        val receiverType = buildReceiverType(javaMultipleFiles, outerClassName, parentNames, messageProto.name)

        val sb = StringBuilder()
        sb.appendLine("// Generated by protoc-gen-validate-kt. DO NOT EDIT.")
        sb.appendLine("package $javaPackage")
        sb.appendLine()
        sb.appendLine("import dev.bmcreations.protovalidate.CompilationError")
        sb.appendLine("import dev.bmcreations.protovalidate.ValidationResult")
        sb.appendLine()
        sb.appendLine("fun $receiverType.validate(): ValidationResult {")
        sb.appendLine("    throw CompilationError(\"$errorMessage\")")
        sb.appendLine("}")
        sb.appendLine()

        return sb.toString()
    }
}

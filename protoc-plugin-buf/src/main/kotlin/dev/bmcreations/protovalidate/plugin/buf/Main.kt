package dev.bmcreations.protovalidate.plugin.buf

import dev.bmcreations.protovalidate.plugin.CodeGenerator
import dev.bmcreations.protovalidate.plugin.RuleExtractor
import com.google.protobuf.Descriptors
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DynamicMessage
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

fun main() {
    val extractor = BufRuleExtractor()
    val registry = extractor.createRegistry()

    // First pass: parse to discover extensions
    val rawBytes = System.`in`.readBytes()
    val initialRequest = CodeGeneratorRequest.parseFrom(rawBytes, registry)

    // Build full extension registry including user-defined predefined rule extensions
    val dynamicRegistry = ExtensionRegistry.newInstance()
    val dynamicDescriptors = registerCustomExtensions(initialRequest.protoFileList, registry, dynamicRegistry)

    // Set dynamic descriptors on extractor for predefined rule extraction
    extractor.dynamicRuleDescriptors = dynamicDescriptors
    extractor.dynamicExtensionRegistry = dynamicRegistry

    // Re-parse with full registry (still needed for proper field resolution)
    val request = CodeGeneratorRequest.parseFrom(rawBytes, registry)

    val filesToGenerate = request.fileToGenerateList.toSet()

    // First pass: scan ALL proto files to find which messages have validation rules.
    val validatedTypes = mutableMapOf<String, String>()
    for (fileProto in request.protoFileList) {
        val javaPackage = if (fileProto.options.hasJavaPackage()) {
            fileProto.options.javaPackage
        } else {
            fileProto.`package`
        }
        val protoPackage = fileProto.`package`
        val prefix = if (protoPackage.isEmpty()) "." else ".$protoPackage."

        scanMessages(fileProto.messageTypeList, prefix, javaPackage, extractor, validatedTypes)
    }

    // Second pass: generate validators for files we were asked to process.
    val responseBuilder = CodeGeneratorResponse.newBuilder()
    responseBuilder.supportedFeatures =
        (CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong() or
         CodeGeneratorResponse.Feature.FEATURE_SUPPORTS_EDITIONS.number.toLong())
    responseBuilder.minimumEdition = com.google.protobuf.DescriptorProtos.Edition.EDITION_2023.number
    responseBuilder.maximumEdition = com.google.protobuf.DescriptorProtos.Edition.EDITION_2023.number

    for (fileProto in request.protoFileList) {
        if (fileProto.name !in filesToGenerate) continue

        val generatedFiles = CodeGenerator.generate(fileProto, validatedTypes, extractor)
        for (file in generatedFiles) {
            responseBuilder.addFile(file)
        }
    }

    responseBuilder.build().writeTo(System.out)
}

private fun scanMessages(
    messages: List<DescriptorProto>,
    parentPrefix: String,
    javaPackage: String,
    extractor: RuleExtractor,
    result: MutableMap<String, String>
) {
    for (msg in messages) {
        val fullName = "$parentPrefix${msg.name}"

        val hasValidatedFields = msg.fieldList.any { field ->
            extractor.getFieldRules(field.options) != null
        }
        val hasRequiredOneofs = msg.oneofDeclList.any { oneof ->
            oneof.options != null && extractor.isOneofRequired(oneof.options)
        }
        val hasMessageCelRules = msg.options != null &&
            extractor.getMessageCelRules(msg.options).isNotEmpty()

        if (hasValidatedFields || hasRequiredOneofs || hasMessageCelRules) {
            result[fullName] = javaPackage
        }

        scanMessages(msg.nestedTypeList, "$fullName.", javaPackage, extractor, result)
    }
}

/**
 * Builds FileDescriptors from the proto files and registers any custom extensions
 * that target buf.validate rule messages (FloatRules, Int32Rules, etc.).
 * Returns a map of rule message full name → dynamic Descriptor for re-parsing.
 */
private fun registerCustomExtensions(
    protoFiles: List<FileDescriptorProto>,
    registry: ExtensionRegistry,
    dynamicRegistry: ExtensionRegistry
): Map<String, Descriptors.Descriptor> {
    // Target message full names for buf.validate rule types
    val ruleMessageNames = setOf(
        "buf.validate.FloatRules", "buf.validate.DoubleRules",
        "buf.validate.Int32Rules", "buf.validate.Int64Rules",
        "buf.validate.UInt32Rules", "buf.validate.UInt64Rules",
        "buf.validate.SInt32Rules", "buf.validate.SInt64Rules",
        "buf.validate.Fixed32Rules", "buf.validate.Fixed64Rules",
        "buf.validate.SFixed32Rules", "buf.validate.SFixed64Rules",
        "buf.validate.BoolRules",
        "buf.validate.StringRules", "buf.validate.BytesRules",
        "buf.validate.EnumRules",
        "buf.validate.RepeatedRules", "buf.validate.MapRules",
        "buf.validate.DurationRules", "buf.validate.TimestampRules",
        "buf.validate.AnyRules",
    )

    // Build file descriptors from proto files so we can resolve extensions dynamically
    val filesByName = protoFiles.associateBy { it.name }
    val builtDescriptors = mutableMapOf<String, Descriptors.FileDescriptor>()

    fun buildDescriptor(name: String): Descriptors.FileDescriptor? {
        builtDescriptors[name]?.let { return it }
        val fileProto = filesByName[name] ?: return null
        val deps = fileProto.dependencyList.mapNotNull { buildDescriptor(it) }.toTypedArray()
        return try {
            val fd = Descriptors.FileDescriptor.buildFrom(fileProto, deps)
            builtDescriptors[name] = fd
            fd
        } catch (_: Exception) {
            null
        }
    }

    // Build all file descriptors
    for (name in filesByName.keys) {
        buildDescriptor(name)
    }

    // Find and register extensions targeting rule messages
    val ruleDescriptors = mutableMapOf<String, Descriptors.Descriptor>()
    for (fd in builtDescriptors.values) {
        for (ext in fd.extensions) {
            val containingTypeName = ext.containingType.fullName
            if (containingTypeName in ruleMessageNames) {
                // Register in both registries
                if (ext.type == Descriptors.FieldDescriptor.Type.MESSAGE) {
                    val defaultInst = DynamicMessage.getDefaultInstance(ext.messageType)
                    registry.add(ext, defaultInst)
                    dynamicRegistry.add(ext, defaultInst)
                } else {
                    registry.add(ext)
                    dynamicRegistry.add(ext)
                }
                // Track the dynamic descriptor for this rule message type
                ruleDescriptors[containingTypeName] = ext.containingType
            }
        }
    }

    return ruleDescriptors
}

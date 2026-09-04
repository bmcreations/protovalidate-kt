package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

/**
 * The generation pipeline shared by both plugin entry points and by the golden
 * tests. A test that reimplemented this would drift from what the plugin
 * actually runs, so the entry points and the tests call the same code.
 *
 * What stays in each entry point is what genuinely differs between the two
 * dialects: how the request is parsed (buf discovers predefined-rule extensions
 * before it can parse rules) and which `CodeGeneratorResponse` features are
 * advertised.
 */
object PluginPipeline {

    /**
     * Maps the fully-qualified proto name of every message carrying validation
     * rules to the Java package its validator belongs in. Generation needs this
     * across all files in the request, not just the ones being generated, so a
     * validator can call into a validator for a type declared elsewhere.
     */
    fun scanValidatedTypes(
        protoFiles: List<FileDescriptorProto>,
        extractor: RuleExtractor
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (fileProto in protoFiles) {
            val javaPackage = if (fileProto.options.hasJavaPackage()) {
                fileProto.options.javaPackage
            } else {
                fileProto.`package`
            }
            val protoPackage = fileProto.`package`
            val prefix = if (protoPackage.isEmpty()) "." else ".$protoPackage."

            scanMessages(fileProto.messageTypeList, prefix, javaPackage, extractor, result)
        }
        return result
    }

    /** Generates validators for [filesToGenerate], resolving types against all of [protoFiles]. */
    fun generate(
        protoFiles: List<FileDescriptorProto>,
        filesToGenerate: Set<String>,
        extractor: RuleExtractor
    ): List<CodeGeneratorResponse.File> {
        val validatedTypes = scanValidatedTypes(protoFiles, extractor)
        return protoFiles
            .filter { it.name in filesToGenerate }
            .flatMap { CodeGenerator.generate(it, validatedTypes, extractor) }
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
            // Only buf has message-level CEL rules; the PGV extractor returns none.
            val hasMessageCelRules = msg.options != null &&
                extractor.getMessageCelRules(msg.options).isNotEmpty()

            if (hasValidatedFields || hasRequiredOneofs || hasMessageCelRules) {
                result[fullName] = javaPackage
            }

            scanMessages(msg.nestedTypeList, "$fullName.", javaPackage, extractor, result)
        }
    }
}

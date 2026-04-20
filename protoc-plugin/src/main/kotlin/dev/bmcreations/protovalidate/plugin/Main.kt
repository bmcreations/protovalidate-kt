package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

fun main() {
    val extractor = PgvRuleExtractor()
    val registry = extractor.createRegistry()
    val request = CodeGeneratorRequest.parseFrom(System.`in`, registry)

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
        CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong()

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

        if (hasValidatedFields || hasRequiredOneofs) {
            result[fullName] = javaPackage
        }

        // Recurse into nested messages
        scanMessages(msg.nestedTypeList, "$fullName.", javaPackage, extractor, result)
    }
}

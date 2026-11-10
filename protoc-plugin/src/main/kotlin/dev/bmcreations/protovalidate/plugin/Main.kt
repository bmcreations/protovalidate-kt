package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

fun main() {
    val extractor = PgvRuleExtractor()
    val registry = extractor.createRegistry()
    val request = CodeGeneratorRequest.parseFrom(System.`in`, registry)
    respond(request, extractor).writeTo(System.out)
}

/** Generates for [request]; a bad `target=` or unsupported input becomes the response's error. */
internal fun respond(request: CodeGeneratorRequest, extractor: PgvRuleExtractor): CodeGeneratorResponse {
    val responseBuilder = CodeGeneratorResponse.newBuilder()
    responseBuilder.supportedFeatures =
        CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong()

    try {
        responseBuilder.addAllFile(
            PluginPipeline.generate(
                protoFiles = request.protoFileList,
                filesToGenerate = request.fileToGenerateList.toSet(),
                extractor = extractor,
                target = GeneratorTarget.fromParameter(request.parameter),
            )
        )
    } catch (e: PluginOptionException) {
        responseBuilder.error = e.message
    }
    return responseBuilder.build()
}

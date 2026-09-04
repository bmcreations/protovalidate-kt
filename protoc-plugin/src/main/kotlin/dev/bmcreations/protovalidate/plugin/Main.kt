package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

fun main() {
    val extractor = PgvRuleExtractor()
    val registry = extractor.createRegistry()
    val request = CodeGeneratorRequest.parseFrom(System.`in`, registry)

    val responseBuilder = CodeGeneratorResponse.newBuilder()
    responseBuilder.supportedFeatures =
        CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.number.toLong()

    responseBuilder.addAllFile(
        PluginPipeline.generate(
            protoFiles = request.protoFileList,
            filesToGenerate = request.fileToGenerateList.toSet(),
            extractor = extractor
        )
    )

    responseBuilder.build().writeTo(System.out)
}

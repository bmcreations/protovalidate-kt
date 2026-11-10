package dev.bmcreations.protovalidate.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Writes the plugin's bundled `validate/validate.proto` under [outputDir]. */
@CacheableTask
abstract class ExtractValidateProtoTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun extract() {
        val protoFile = outputDir.get().asFile.resolve("validate/validate.proto")
        protoFile.parentFile.mkdirs()
        protoFile.writeBytes(readBundledValidateProto())
    }
}

internal fun readBundledValidateProto(): ByteArray =
    ProtovalidatePlugin::class.java.getResourceAsStream("/validate/validate.proto")
        ?.use { it.readBytes() }
        ?: error("protovalidate: bundled validate/validate.proto not found in plugin JAR")

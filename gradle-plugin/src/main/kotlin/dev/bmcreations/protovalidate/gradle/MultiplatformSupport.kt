package dev.bmcreations.protovalidate.gradle

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Kept out of ProtovalidatePlugin so the plugin class loads without the
// Kotlin Gradle plugin on the classpath.
internal object MultiplatformSupport {

    /**
     * KMP + WIRE: protobuf-gradle-plugin only feeds JVM source sets, so run
     * protoc here and put the validators in `commonMain`.
     */
    fun configure(project: Project, ext: ProtovalidateExtension) {
        val extractValidateProto = project.tasks.register(
            "extractProtovalidateWireIncludes",
            ExtractValidateProtoTask::class.java,
        ) { task ->
            task.outputDir.set(project.layout.buildDirectory.dir("protovalidate/wire-include"))
        }
        ext.validateProtoDir.value(extractValidateProto.flatMap { it.outputDir }).disallowChanges()

        project.afterEvaluate {
            if (ext.target.get() != MessageTarget.WIRE) return@afterEvaluate
            checkWireVariant(ext.variant.get())

            val version = resolvePluginVersion(project)
            val protobufVersion = resolveProtobufVersion()
            fun detached(notation: String) = project.configurations
                .detachedConfiguration(project.dependencies.create(notation))
                .apply { isTransitive = false }

            val generate = project.tasks.register(
                "generateProtovalidateWire",
                GenerateProtovalidateWireTask::class.java,
            ) { task ->
                task.group = "protovalidate"
                task.description = "Generates PGV validate() extensions for Wire messages into commonMain."
                task.protoDirs.from(ext.protoDirs)
                task.importDirs.from(ext.importDirs)
                task.validateProtoDir.set(ext.validateProtoDir)
                task.protoc.from(detached("com.google.protobuf:protoc:$protobufVersion:${protocClassifier()}@exe"))
                task.pluginJar.from(detached("dev.bmcreations:protovalidate-protoc-plugin:$version"))
                task.wellKnownTypesJar.from(detached("com.google.protobuf:protobuf-java:$protobufVersion"))
                task.pluginOptions.set(ext.wireBoxOneOfsMinSize.map { wirePluginOptions(it) }
                    .orElse(wirePluginOptions(null)))
                task.outputDir.set(project.layout.buildDirectory.dir("generated/protovalidate/wire/commonMain"))
            }

            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generate.flatMap { it.outputDir })
            project.dependencies.add(
                "commonMainImplementation",
                "dev.bmcreations:protovalidate-runtime:$version",
            )
            // IDE sync runs this task rather than the compile tasks.
            project.tasks.matching { it.name == "prepareKotlinIdeaImport" }.configureEach {
                it.dependsOn(generate)
            }
        }
    }
}

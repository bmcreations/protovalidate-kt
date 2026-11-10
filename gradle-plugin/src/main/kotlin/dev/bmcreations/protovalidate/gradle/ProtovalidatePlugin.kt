package dev.bmcreations.protovalidate.gradle

import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

class ProtovalidatePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "protovalidate",
            ProtovalidateExtension::class.java,
        )
        ext.variant.convention(ProtoVariant.BUF)
        ext.target.convention(MessageTarget.JAVA)
        ext.protoDirs.convention(project.layout.projectDirectory.dir("src/commonMain/proto"))

        project.pluginManager.withPlugin(KMP_PLUGIN_ID) {
            configureMultiplatform(project, ext)
        }
        project.pluginManager.withPlugin("com.google.protobuf") {
            configureProtobuf(project, ext)
        }
    }

    /**
     * KMP + WIRE: protobuf-gradle-plugin only feeds JVM source sets, so run
     * protoc here and put the validators in `commonMain`.
     */
    private fun configureMultiplatform(project: Project, ext: ProtovalidateExtension) {
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
                WIRE_GENERATE_TASK,
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

    private fun configureProtobuf(project: Project, ext: ProtovalidateExtension) {
        val protobuf = project.extensions.getByType(ProtobufExtension::class.java)
        val version = resolvePluginVersion(project)

        // Resolve both plugin fat JARs via detached configurations.
        // The protobuf-gradle-plugin's `artifact` mode appends a platform classifier
        // (e.g. osx-aarch_64) which doesn't apply to our JVM fat JARs.
        // Instead, we resolve the JAR ourselves and use `path` with a trampoline script.
        val bufJar = resolvePluginJar(project, "protoc-plugin-buf", version)
        val pgvJar = resolvePluginJar(project, "protoc-plugin", version)

        val trampolineDir = project.layout.buildDirectory.dir("protovalidate/bin").get().asFile
        val bufTrampolinePath = File(trampolineDir, "protoc-gen-validate-kt-buf").absolutePath
        val pgvTrampolinePath = File(trampolineDir, "protoc-gen-validate-kt").absolutePath

        // Register a task to create trampoline scripts during execution phase,
        // so they survive `clean` when running `clean assembleRelease`.
        val setupTrampolines = project.tasks.register("setupProtovalidateTrampolines") { task ->
            task.outputs.dir(trampolineDir)
            task.doLast {
                trampolineDir.mkdirs()
                writeTrampoline(trampolineDir, "protoc-gen-validate-kt-buf", bufJar, java = "java")
                writeTrampoline(trampolineDir, "protoc-gen-validate-kt", pgvJar, java = "java")
            }
        }

        // Register a task to extract validate/validate.proto during execution phase.
        val includeDir = project.layout.buildDirectory
            .dir("protovalidate/include").get().asFile
        val extractProtos = project.tasks.register("extractProtovalidateIncludes") { task ->
            task.outputs.dir(includeDir)
            task.doLast {
                val protoFile = File(includeDir, "validate/validate.proto")
                protoFile.parentFile.mkdirs()
                val stream = ProtovalidatePlugin::class.java
                    .getResourceAsStream("/validate/validate.proto")
                    ?: error("protovalidate: bundled validate/validate.proto not found in plugin JAR")
                protoFile.writeBytes(stream.readBytes())
            }
        }

        // Set paths at configuration time — protoc reads them at execution time.
        protobuf.plugins {
            it.create("validate-kt-buf") { locator ->
                locator.path = bufTrampolinePath
            }
            it.create("validate-kt") { locator ->
                locator.path = pgvTrampolinePath
            }
        }

        // generateProtoTasks actions are deferred by the protobuf plugin — they
        // run during afterEvaluate, so the variant property is resolved by then.
        protobuf.generateProtoTasks {
            val variant = ext.variant.get()
            val target = ext.target.get()
            checkWireVariant(variant, target)
            // KMP + WIRE generates through generateProtovalidateWire instead.
            if (target == MessageTarget.WIRE && project.pluginManager.hasPlugin(KMP_PLUGIN_ID)) {
                return@generateProtoTasks
            }
            val boxOneOfsMinSize = ext.wireBoxOneOfsMinSize.orNull
            val pluginName = when (variant) {
                ProtoVariant.BUF -> "validate-kt-buf"
                ProtoVariant.PGV -> "validate-kt"
            }

            it.all().configureEach { task ->
                task.dependsOn(setupTrampolines)

                task.plugins { plugins ->
                    plugins.create(pluginName) { options ->
                        if (target == MessageTarget.WIRE) {
                            wirePluginOptions(boxOneOfsMinSize).forEach(options::option)
                        }
                    }
                }

                // Wire's plugin generates the message classes; protobuf-java's
                // would clash with them by name.
                if (target == MessageTarget.WIRE) {
                    task.builtins { builtins ->
                        listOf("kotlin", "java").forEach { name ->
                            builtins.findByName(name)?.let(builtins::remove)
                        }
                    }
                }

                // PGV protos import validate/validate.proto — extract the bundled
                // copy and add it to protoc's include path.
                if (variant == ProtoVariant.PGV) {
                    task.dependsOn(extractProtos)
                    task.addIncludeDir(project.files(includeDir))
                }
            }
        }

        // Add runtime dependency.
        project.afterEvaluate {
            project.configurations.findByName("implementation")?.let {
                project.dependencies.add(
                    "implementation",
                    "dev.bmcreations:protovalidate-runtime:$version",
                )
            }
        }
    }

    private fun resolvePluginJar(project: Project, artifactId: String, version: String): File {
        val dep = project.dependencies.create("dev.bmcreations:protovalidate-$artifactId:$version")
        val config = project.configurations.detachedConfiguration(dep)
        config.isTransitive = false
        return config.singleFile
    }

    private fun resolvePluginVersion(project: Project): String {
        val stream = ProtovalidatePlugin::class.java
            .getResourceAsStream("/dev/bmcreations/protovalidate/gradle/version.txt")
        if (stream != null) {
            return stream.bufferedReader().readLine().trim()
        }

        val prop = project.findProperty("protovalidate.version") as? String
        if (prop != null) return prop

        error(
            "protovalidate: could not determine plugin version. " +
                "Set the 'protovalidate.version' project property."
        )
    }

    private fun resolveProtobufVersion(): String =
        ProtovalidatePlugin::class.java
            .getResourceAsStream("/dev/bmcreations/protovalidate/gradle/protobuf-version.txt")
            ?.bufferedReader()?.use { it.readLine().trim() }
            ?: error("protovalidate: bundled protobuf-version.txt not found in plugin JAR")

    private fun protocClassifier(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = when (val a = System.getProperty("os.arch")) {
            "aarch64", "arm64" -> "aarch_64"
            "amd64", "x86_64" -> "x86_64"
            else -> a
        }
        return when {
            os.contains("mac") -> "osx-$arch"
            os.contains("windows") -> "windows-$arch"
            else -> "linux-$arch"
        }
    }

    private companion object {
        const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
        const val WIRE_GENERATE_TASK = "generateProtovalidateWire"
    }
}

private fun checkWireVariant(variant: ProtoVariant, target: MessageTarget = MessageTarget.WIRE) {
    if (target == MessageTarget.WIRE && variant != ProtoVariant.PGV) {
        error(
            "protovalidate: target = WIRE supports PGV rules only; " +
                "set variant.set(ProtoVariant.PGV)."
        )
    }
}

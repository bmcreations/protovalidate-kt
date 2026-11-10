package dev.bmcreations.protovalidate.gradle

import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Project
import java.io.File

// Kept out of ProtovalidatePlugin so the plugin class loads without
// protobuf-gradle-plugin on the classpath.
internal object ProtobufSupport {

    fun configure(project: Project, ext: ProtovalidateExtension) {
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
}

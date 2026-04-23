package dev.bmcreations.protovalidate.gradle

import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class ProtovalidatePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "protovalidate",
            ProtovalidateExtension::class.java,
        )
        ext.variant.convention(ProtoVariant.BUF)

        project.pluginManager.withPlugin("com.google.protobuf") {
            configureProtobuf(project, ext)
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
                writeTrampoline(trampolineDir, "protoc-gen-validate-kt-buf", bufJar)
                writeTrampoline(trampolineDir, "protoc-gen-validate-kt", pgvJar)
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
            val pluginName = when (variant) {
                ProtoVariant.BUF -> "validate-kt-buf"
                ProtoVariant.PGV -> "validate-kt"
            }

            it.all().configureEach { task ->
                task.dependsOn(setupTrampolines)

                task.plugins { plugins ->
                    plugins.create(pluginName)
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

    private fun writeTrampoline(dir: File, name: String, jar: File): File {
        val script = File(dir, name)
        script.writeText(
            """
            |#!/bin/sh
            |exec java -jar "${jar.absolutePath}" "$@"
            """.trimMargin() + "\n"
        )
        script.setExecutable(true)
        return script
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
}

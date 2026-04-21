package dev.bmcreations.protovalidate.gradle

import com.google.protobuf.gradle.GenerateProtoTask
import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ProtovalidatePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "protovalidate",
            ProtovalidateExtension::class.java,
        )
        ext.variant.convention("buf")

        project.pluginManager.withPlugin("com.google.protobuf") {
            configureProtobuf(project, ext)
        }
    }

    private fun configureProtobuf(project: Project, ext: ProtovalidateExtension) {
        val protobuf = project.extensions.getByType(ProtobufExtension::class.java)
        val version = resolvePluginVersion(project)

        // Register both plugin executables eagerly — this must happen before
        // afterEvaluate where the protobuf plugin resolves tools.
        protobuf.plugins {
            it.create("validate-kt-buf") { locator ->
                locator.artifact = "dev.bmcreations.protovalidate:protoc-plugin-buf:$version@jar"
            }
            it.create("validate-kt") { locator ->
                locator.artifact = "dev.bmcreations.protovalidate:protoc-plugin:$version@jar"
            }
        }

        // generateProtoTasks actions are deferred by the protobuf plugin — they
        // run during afterEvaluate, so the variant property is resolved by then.
        protobuf.generateProtoTasks {
            val variant = ext.variant.get()
            val pluginName = when (variant) {
                "buf" -> "validate-kt-buf"
                "pgv" -> "validate-kt"
                else -> error("protovalidate: unknown variant '$variant'. Use 'buf' or 'pgv'.")
            }

            it.all().configureEach { task ->
                task.plugins { plugins ->
                    plugins.create(pluginName)
                }
            }
        }

        // Add runtime dependency. Use afterEvaluate so configurations are resolved.
        project.afterEvaluate {
            project.configurations.findByName("implementation")?.let {
                project.dependencies.add(
                    "implementation",
                    "dev.bmcreations.protovalidate:runtime:$version",
                )
            }
        }
    }

    private fun resolvePluginVersion(project: Project): String {
        // Read from the version resource baked in at publish time
        val stream = ProtovalidatePlugin::class.java
            .getResourceAsStream("/dev/bmcreations/protovalidate/gradle/version.txt")
        if (stream != null) {
            return stream.bufferedReader().readLine().trim()
        }

        // Fallback: check if the user set a version property
        val prop = project.findProperty("protovalidate.version") as? String
        if (prop != null) return prop

        error(
            "protovalidate: could not determine plugin version. " +
                "Set the 'protovalidate.version' project property."
        )
    }
}

package dev.bmcreations.protovalidate.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

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
            MultiplatformSupport.configure(project, ext)
        }
        project.pluginManager.withPlugin("com.google.protobuf") {
            ProtobufSupport.configure(project, ext)
        }
    }
}

internal const val KMP_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"

internal fun resolvePluginVersion(project: Project): String {
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

internal fun resolveProtobufVersion(): String =
    ProtovalidatePlugin::class.java
        .getResourceAsStream("/dev/bmcreations/protovalidate/gradle/protobuf-version.txt")
        ?.bufferedReader()?.use { it.readLine().trim() }
        ?: error("protovalidate: bundled protobuf-version.txt not found in plugin JAR")

internal fun protocClassifier(): String {
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

internal fun checkWireVariant(variant: ProtoVariant, target: MessageTarget = MessageTarget.WIRE) {
    if (target == MessageTarget.WIRE && variant != ProtoVariant.PGV) {
        error(
            "protovalidate: target = WIRE supports PGV rules only; " +
                "set variant.set(ProtoVariant.PGV)."
        )
    }
}

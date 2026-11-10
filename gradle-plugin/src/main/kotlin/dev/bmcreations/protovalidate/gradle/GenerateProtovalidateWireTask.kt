package dev.bmcreations.protovalidate.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Runs protoc with `protoc-gen-validate-kt` in Wire mode over [protoDirs],
 * writing `validate()` extensions to [outputDir].
 *
 * Every input is a file collection or value, so the task reads nothing from
 * `project` at execution time.
 */
@CacheableTask
abstract class GenerateProtovalidateWireTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {

    /** Roots of the protos to generate validators for. Each is also an include root. */
    @get:Internal
    abstract val protoDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val protoFiles: FileTree
        get() = protoDirs.asFileTree.matching { it.include("**/*.proto") }

    /** Include-only roots: protos here resolve imports but get no validators. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val importDirs: ConfigurableFileCollection

    /** Directory holding `validate/validate.proto`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val validateProtoDir: DirectoryProperty

    /** The protoc executable (a single file). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protoc: ConfigurableFileCollection

    /** The `protoc-plugin` fat jar (a single file). */
    @get:Classpath
    abstract val pluginJar: ConfigurableFileCollection

    /** protobuf-java, the source of the well-known-type protos. */
    @get:Classpath
    abstract val wellKnownTypesJar: ConfigurableFileCollection

    /** Plugin options, joined with `,` into the `--validate-kt_out` parameter. */
    @get:Input
    abstract val pluginOptions: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val work = temporaryDir.apply {
            deleteRecursively()
            mkdirs()
        }
        // Resolved files may be read-only cache entries; chmod a copy instead.
        val protocExe = protoc.singleFile.copyTo(File(work, "protoc"), overwrite = true)
            .apply { setExecutable(true) }
        val trampoline = writeTrampoline(work, "protoc-gen-validate-kt", pluginJar.singleFile)
        val wkt = extractWellKnownTypes(wellKnownTypesJar.singleFile, File(work, "wkt"))

        val roots = protoDirs.files.filter { it.isDirectory }
        val sources = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "proto" }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }.distinct().sorted()

        val includes = roots + importDirs.files.filter { it.isDirectory } +
            validateProtoDir.get().asFile + wkt
        val args = buildList {
            add("--plugin=protoc-gen-validate-kt=${trampoline.absolutePath}")
            add("--validate-kt_out=${pluginOptions.get().joinToString(",")}:${out.absolutePath}")
            includes.forEach { add("-I"); add(it.absolutePath) }
            addAll(sources)
        }
        logger.info("protovalidate: protoc {}", args.joinToString(" "))
        exec.exec {
            it.executable = protocExe.absolutePath
            it.args(args)
        }
    }

    private fun extractWellKnownTypes(jar: File, into: File): File {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("google/protobuf/") && it.name.endsWith(".proto") }
                .forEach { entry ->
                    val target = File(into, entry.name)
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                }
        }
        return into
    }
}

/** Options for `protoc-gen-validate-kt` in Wire mode. */
internal fun wirePluginOptions(boxOneOfsMinSize: Int?): List<String> = buildList {
    add("target=wire")
    if (boxOneOfsMinSize != null) add("wire_box_oneofs_min_size=$boxOneOfsMinSize")
}

/**
 * Writes a shell script that runs [jar] with the JVM running this build, so
 * protoc can invoke a JVM plugin.
 */
internal fun writeTrampoline(dir: File, name: String, jar: File, java: String = currentJava()): File {
    dir.mkdirs()
    val script = File(dir, name)
    script.writeText(
        """
        |#!/bin/sh
        |exec "$java" -jar "${jar.absolutePath}" "$@"
        """.trimMargin() + "\n"
    )
    script.setExecutable(true)
    return script
}

private fun currentJava(): String =
    File(System.getProperty("java.home"), "bin/java").takeIf { it.canExecute() }?.absolutePath ?: "java"

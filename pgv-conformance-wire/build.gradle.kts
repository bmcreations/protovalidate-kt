import org.apache.tools.ant.taskdefs.condition.Os

// PGV conformance against Wire message classes. The harness protos are
// compiled to Wire Kotlin and their validators generated with target=wire,
// both into commonMain, so compiling the js target shows the validators use
// only common APIs. The executor itself is JVM-only (it loads classes by name).
//
// Generated class names match :pgv-conformance's protobuf-java ones, which is
// why this is a separate module rather than a second source set there.

plugins {
    kotlin("multiplatform")
    id("com.squareup.wire")
}

val harnessProtos = rootProject.file("pgv-conformance/src/main/proto")
// golden/**/*.proto: the plugin's golden fixtures, compiled here against real
// Wire output (the golden test only compares text).
val goldenProtos = rootProject.file("protoc-plugin/src/test/proto")
val validateProtos = rootProject.file("protos")

// -PwireBoxOneOfsMinSize=N runs the harness with Wire's boxOneOfsMinSize = N,
// for both the Wire classes and the validators. The golden fixtures keep their
// own options whatever it is set to.
val harnessBoxOneOfsMinSize: Int? = providers.gradleProperty("wireBoxOneOfsMinSize").orNull?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { n -> n > 0 }) {
        "wireBoxOneOfsMinSize must be a positive integer, got '$it'"
    }
}
// golden/boxed/ is generated with this in the golden test too.
val goldenBoxOneOfsMinSize = 2

wire {
    sourcePath { srcDir(harnessProtos) }
    sourcePath { srcDir(goldenProtos) }
    protoPath { srcDir(validateProtos) }
    // Targets claim types in order, so each later one gets what is left.
    // Flipcash's planned Wire config: option annotations off, otherwise defaults.
    kotlin {
        includes = listOf("golden.boxed.*")
        boxOneOfsMinSize = goldenBoxOneOfsMinSize
        emitAppliedOptions = false
        emitDeclaredOptions = false
    }
    kotlin {
        includes = listOf("golden.*")
        emitAppliedOptions = false
        emitDeclaredOptions = false
    }
    kotlin {
        harnessBoxOneOfsMinSize?.let { boxOneOfsMinSize = it }
        emitAppliedOptions = false
        emitDeclaredOptions = false
    }
}

// --- validators: protoc + protoc-gen-validate-kt, target=wire ------------------

val protocClassifier = when {
    Os.isFamily(Os.FAMILY_MAC) ->
        if (System.getProperty("os.arch") == "aarch64") "osx-aarch_64" else "osx-x86_64"
    System.getProperty("os.arch") == "aarch64" -> "linux-aarch_64"
    else -> "linux-x86_64"
}
val protobufVersion = libs.versions.protobuf.get()

val protoc by configurations.creating { isTransitive = false }
val protobufJava by configurations.creating { isTransitive = false }
val wireSchema by configurations.creating { isTransitive = false }
dependencies {
    protoc("com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe")
    protobufJava(libs.protobuf.java)
    wireSchema("com.squareup.wire:wire-schema-jvm:7.1.2")
}

// protoc's well-known-type protos (validate.proto imports them).
val extractWellKnownProtos by tasks.registering(Sync::class) {
    from({ protobufJava.map { zipTree(it) } }) { include("google/protobuf/*.proto") }
    into(layout.buildDirectory.dir("wkt-protos"))
}

// wire/extensions.proto, which the wire_package fixture imports. Wire resolves
// it by itself; protoc needs it on the include path.
val extractWireProtos by tasks.registering(Sync::class) {
    from({ wireSchema.map { zipTree(it) } }) { include("wire/extensions.proto") }
    into(layout.buildDirectory.dir("wire-protos"))
}

val validatorsRoot = layout.buildDirectory.dir("generated/validate-kt-wire")

/**
 * Generates validators for [protos] (paths under [root]) with target=wire and
 * [boxOneOfsMinSize], into their own directory under [validatorsRoot].
 */
fun registerValidatorGeneration(
    name: String,
    root: File,
    include: String,
    boxOneOfsMinSize: Int?,
) = tasks.register<Exec>(name) {
    dependsOn(":protoc-plugin:jar", extractWellKnownProtos, extractWireProtos)
    val pluginScript = rootProject.file("protoc-plugin/protoc-gen-validate-kt")
    val pluginJar = rootProject.file("protoc-plugin/build/libs/protoc-plugin.jar")
    val wkt = layout.buildDirectory.dir("wkt-protos").get().asFile
    val wireProtos = layout.buildDirectory.dir("wire-protos").get().asFile
    val out = validatorsRoot.get().dir(name).asFile
    val sources = fileTree(root) { include(include) }
    val parameter = listOfNotNull("target=wire", boxOneOfsMinSize?.let { "wire_box_oneofs_min_size=$it" })
        .joinToString(",")
    inputs.file(pluginJar).withPropertyName("validateKtPluginJar")
    inputs.files(sources).withPropertyName("protos")
    inputs.files(protoc).withPropertyName("protoc")
    inputs.property("parameter", parameter)
    outputs.dir(out)
    doFirst {
        out.deleteRecursively()
        out.mkdirs()
        val exe = protoc.singleFile.apply { setExecutable(true) }
        executable = exe.absolutePath
        args(
            "--plugin=protoc-gen-validate-kt=${pluginScript.absolutePath}",
            "--validate-kt_out=$parameter:${out.absolutePath}",
            "-I", harnessProtos.absolutePath,
            "-I", goldenProtos.absolutePath,
            "-I", validateProtos.absolutePath,
            "-I", wkt.absolutePath,
            "-I", wireProtos.absolutePath,
        )
        args(sources.map { it.relativeTo(root).path })
    }
}

val generateWireValidators = registerValidatorGeneration(
    "generateWireValidators", harnessProtos, "**/*.proto", harnessBoxOneOfsMinSize,
)
// Flat fixtures: every golden proto except golden/boxed/.
val generateGoldenWireValidators = registerValidatorGeneration(
    "generateGoldenWireValidators", goldenProtos, "golden/*.proto", null,
)
val generateBoxedGoldenWireValidators = registerValidatorGeneration(
    "generateBoxedGoldenWireValidators", goldenProtos, "golden/boxed/**/*.proto", goldenBoxOneOfsMinSize,
)
val harnessValidatorsDir = validatorsRoot.map { it.dir("generateWireValidators") }

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain {
            listOf(generateWireValidators, generateGoldenWireValidators, generateBoxedGoldenWireValidators)
                .forEach { task -> kotlin.srcDir(files(validatorsRoot.map { it.dir(task.name) }).builtBy(task)) }
            dependencies {
                implementation(project(":runtime"))
                implementation("com.squareup.wire:wire-runtime:7.1.2")
            }
        }
    }
}

// --- executor jar --------------------------------------------------------------

val executorJar by tasks.registering(Jar::class) {
    archiveBaseName.set("pgv-conformance-wire")
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.conformance.pgv.wire.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    from(jvmMain.output.allOutputs)
    from({ jvmMain.runtimeDependencyFiles!!.map { if (it.isDirectory) it else zipTree(it) } })
}

// --- guard against false passes ------------------------------------------------

// The executor treats "no validator class" as valid, so a validator Wire mode
// misnamed or failed to emit would pass every valid-expected case. Both modes
// must emit the same validator files for the harness.
val checkValidatorParity by tasks.registering {
    dependsOn(generateWireValidators, ":pgv-conformance:generateProto")
    val wireOut = harnessValidatorsDir.get().asFile
    val javaOut = rootProject.file("pgv-conformance/build/generated/sources/proto/main/validate-kt")
    doLast {
        fun list(dir: File) = dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }
            .toSortedSet()
        val wire = list(wireOut)
        val java = list(javaOut)
        check(wire.isNotEmpty()) { "no Wire validators in $wireOut" }
        check(wire == java) {
            "validator files differ between target=wire and target=java:\n" +
                "  only wire: ${wire - java}\n  only java: ${java - wire}"
        }
        logger.lifecycle("validator parity: ${wire.size} files in both modes")
    }
}

tasks.named("check") { dependsOn(checkValidatorParity) }

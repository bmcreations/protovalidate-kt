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
// golden/*.proto: the plugin's golden fixtures, compiled here against real Wire
// output (the golden test only compares text).
val goldenProtos = rootProject.file("protoc-plugin/src/test/proto")
val validateProtos = rootProject.file("protos")

wire {
    sourcePath { srcDir(harnessProtos) }
    sourcePath { srcDir(goldenProtos) }
    protoPath { srcDir(validateProtos) }
    kotlin {
        // Flipcash's planned Wire config: option annotations off, otherwise defaults.
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
dependencies {
    protoc("com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe")
    protobufJava(libs.protobuf.java)
}

// protoc's well-known-type protos (validate.proto imports them).
val extractWellKnownProtos by tasks.registering(Sync::class) {
    from({ protobufJava.map { zipTree(it) } }) { include("google/protobuf/*.proto") }
    into(layout.buildDirectory.dir("wkt-protos"))
}

val validatorsDir = layout.buildDirectory.dir("generated/validate-kt-wire")

val generateWireValidators by tasks.registering(Exec::class) {
    dependsOn(":protoc-plugin:jar", extractWellKnownProtos)
    val pluginScript = rootProject.file("protoc-plugin/protoc-gen-validate-kt")
    val pluginJar = rootProject.file("protoc-plugin/build/libs/protoc-plugin.jar")
    val wkt = layout.buildDirectory.dir("wkt-protos").get().asFile
    val out = validatorsDir.get().asFile
    val sources = files(
        fileTree(harnessProtos) { include("**/*.proto") },
        fileTree(goldenProtos) { include("golden/*.proto") },
    )
    inputs.file(pluginJar).withPropertyName("validateKtPluginJar")
    inputs.files(sources).withPropertyName("protos")
    inputs.files(protoc).withPropertyName("protoc")
    outputs.dir(out)
    doFirst {
        out.deleteRecursively()
        out.mkdirs()
        val exe = protoc.singleFile.apply { setExecutable(true) }
        executable = exe.absolutePath
        args(
            "--plugin=protoc-gen-validate-kt=${pluginScript.absolutePath}",
            "--validate-kt_out=target=wire:${out.absolutePath}",
            "-I", harnessProtos.absolutePath,
            "-I", goldenProtos.absolutePath,
            "-I", validateProtos.absolutePath,
            "-I", wkt.absolutePath,
        )
        args(fileTree(harnessProtos) { include("**/*.proto") }.map { it.relativeTo(harnessProtos).path })
        args(fileTree(goldenProtos) { include("golden/*.proto") }.map { it.relativeTo(goldenProtos).path })
    }
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(files(validatorsDir).builtBy(generateWireValidators))
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
    val wireOut = validatorsDir.get().asFile
    val javaOut = rootProject.file("pgv-conformance/build/generated/sources/proto/main/validate-kt")
    doLast {
        fun list(dir: File) = dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }
            .filterNot { it.startsWith("golden/") }
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

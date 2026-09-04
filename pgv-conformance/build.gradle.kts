import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
}

val archSuffix = if (Os.isFamily(Os.FAMILY_MAC)) {
    if (System.getProperty("os.arch") == "aarch64") ":osx-aarch_64" else ":osx-x86_64"
} else ""

application {
    mainClass.set("dev.bmcreations.protovalidate.conformance.pgv.MainKt")
}

dependencies {
    implementation(project(":runtime"))
    implementation(libs.protobuf.java)
    implementation(kotlin("reflect"))
    implementation("io.envoyproxy.protoc-gen-validate:pgv-java-stub:0.6.13")
}

val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}$archSuffix"
    }
    plugins {
        create("validate-kt") {
            path = rootProject.file("protoc-plugin/protoc-gen-validate-kt").path
        }
    }
    generateProtoTasks {
        all().forEach {
            it.addIncludeDir(files(rootProject.file("protos/validate")))
            it.plugins {
                create("validate-kt")
            }
        }
    }
}

afterEvaluate {
    // The plugin jar is an input, not just a dependency: without this, editing the
    // generator leaves generateProto up-to-date and the suite runs against stale
    // generated code.
    tasks.withType<com.google.protobuf.gradle.GenerateProtoTask>().configureEach {
        dependsOn(":protoc-plugin:jar")
        inputs.file(rootProject.file("protoc-plugin/build/libs/protoc-plugin.jar"))
            .withPropertyName("validateKtPluginJar")
    }
}

// Fat jar for running as conformance executor
tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.conformance.pgv.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

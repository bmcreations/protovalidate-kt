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
    mainClass.set("dev.bmcreations.protovalidate.conformance.MainKt")
}

dependencies {
    implementation(project(":runtime"))
    implementation(libs.protobuf.java)
    implementation(kotlin("reflect"))
}

val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}$archSuffix"
    }
    plugins {
        create("validate-kt-buf") {
            path = rootProject.file("protoc-plugin-buf/protoc-gen-validate-kt-buf").path
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("validate-kt-buf")
            }
        }
    }
}

afterEvaluate {
    // The plugin jar is an input, not just a dependency: without this, editing the
    // generator leaves generateProto up-to-date and the suite runs against stale
    // generated code.
    tasks.withType<com.google.protobuf.gradle.GenerateProtoTask>().configureEach {
        dependsOn(":protoc-plugin-buf:jar")
        inputs.file(rootProject.file("protoc-plugin-buf/build/libs/protoc-plugin-buf.jar"))
            .withPropertyName("validateKtPluginJar")
    }
}

// Fat jar for running as conformance executor
tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.conformance.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
}

val archSuffix = if (Os.isFamily(Os.FAMILY_MAC)) ":osx-x86_64" else ""

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
    tasks.withType<com.google.protobuf.gradle.GenerateProtoTask>().configureEach {
        dependsOn(":protoc-plugin:jar")
    }
}

// Fat jar for running as conformance executor
tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.conformance.pgv.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

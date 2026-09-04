plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
    id("com.vanniktech.maven.publish")
}

application {
    mainClass.set("dev.bmcreations.protovalidate.plugin.buf.MainKt")
}

dependencies {
    implementation(project(":protoc-plugin-core"))
    implementation(libs.protobuf.java)
    testImplementation(libs.junit)
}

val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().forEach { task ->
            if (task.name == "generateTestProto") {
                // The golden test reads the descriptor set, not generated Java.
                task.builtins.removeIf { it.name == "java" }
                task.generateDescriptorSet = true
                task.descriptorSetOptions.includeImports = true
                task.descriptorSetOptions.path =
                    layout.buildDirectory.file("descriptors/golden.desc").get().asFile.path
                // Fixtures import "buf/validate/validate.proto", which this
                // module vendors under its own main source dir.
                task.addIncludeDir(files("src/main/proto"))
            }
        }
    }
}

tasks.named<Test>("test") {
    systemProperty(
        "golden.descriptorSet",
        layout.buildDirectory.file("descriptors/golden.desc").get().asFile.path
    )
    systemProperty("golden.dir", file("src/test/resources/golden").path)
    // ./gradlew :protoc-plugin-buf:test -PupdateGoldens rewrites the expected files.
    if (project.hasProperty("updateGoldens")) systemProperty("golden.update", "true")
}

// Fat JAR with all dependencies bundled — this is what protoc invokes.
tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.plugin.buf.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

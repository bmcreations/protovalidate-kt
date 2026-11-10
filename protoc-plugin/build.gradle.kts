plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
    id("com.vanniktech.maven.publish")
}

application {
    mainClass.set("dev.bmcreations.protovalidate.plugin.MainKt")
}

dependencies {
    implementation(project(":protoc-plugin-core"))
    implementation(libs.protobuf.java)
    testImplementation(libs.junit)
}

val protobufVersion = libs.versions.protobuf.get()

// wire/extensions.proto, which the wire_package fixture imports. Only protoc
// needs it; Wire resolves the import by itself.
val wireSchema by configurations.creating { isTransitive = false }
dependencies { wireSchema("com.squareup.wire:wire-schema-jvm:7.1.2") }
val extractWireProtos by tasks.registering(Sync::class) {
    from({ wireSchema.map { zipTree(it) } }) { include("wire/extensions.proto") }
    into(layout.buildDirectory.dir("wire-protos"))
}

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
                // Fixtures import "validate/validate.proto", so the include root
                // is protos/, one level above the main source dir.
                task.addIncludeDir(files(rootProject.file("protos")))
                task.dependsOn(extractWireProtos)
                task.addIncludeDir(files(layout.buildDirectory.dir("wire-protos")))
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
    systemProperty("golden.wireDir", file("src/test/resources/golden-wire").path)
    // ./gradlew :protoc-plugin:test -PupdateGoldens rewrites the expected files.
    if (project.hasProperty("updateGoldens")) systemProperty("golden.update", "true")
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("protos/validate"))
        }
    }
}

// Fat JAR with all dependencies bundled — this is what protoc invokes.
tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.plugin.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

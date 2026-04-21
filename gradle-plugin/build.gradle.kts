plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("com.google.protobuf:protobuf-gradle-plugin:0.9.6")
}

gradlePlugin {
    plugins {
        create("protovalidate") {
            id = "dev.bmcreations.protovalidate"
            displayName = "protovalidate-kt"
            description = "Kotlin code generation for buf.validate and PGV protobuf constraints"
            implementationClass = "dev.bmcreations.protovalidate.gradle.ProtovalidatePlugin"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}

// Bake the project version into a resource so the plugin can resolve
// artifact coordinates at runtime without hardcoding.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    val ver = providers.gradleProperty("VERSION_NAME")
    inputs.property("version", ver)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("dev/bmcreations/protovalidate/gradle")
        dir.mkdirs()
        dir.resolve("version.txt").writeText(ver.get())
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource.map { it.outputs.files.singleFile })
}

tasks.named("processResources") {
    dependsOn(generateVersionResource)
}

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("com.google.protobuf:protobuf-gradle-plugin:0.9.6")
    // KMP extension only; resolved from the consumer's build at runtime.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")

    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
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
    signAllPublications()
}

// Bake the project version into a resource so the plugin can resolve
// artifact coordinates at runtime without hardcoding.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    val ver = providers.gradleProperty("VERSION_NAME")
    // protoc and protobuf-java version for the KMP Wire task.
    val protobufVersion = libs.versions.protobuf.get()
    inputs.property("version", ver)
    inputs.property("protobufVersion", protobufVersion)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("dev/bmcreations/protovalidate/gradle")
        dir.mkdirs()
        dir.resolve("version.txt").writeText(ver.get())
        dir.resolve("protobuf-version.txt").writeText(protobufVersion)
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource.map { it.outputs.files.singleFile })
}

tasks.named("processResources") {
    dependsOn(generateVersionResource)
}

// Functional tests run real consumer builds (TestKit) that resolve this
// plugin, the runtime and the protoc plugin from build/test-repo, like a
// consumer resolving them from Maven Central.
val testRepoModules = listOf(":gradle-plugin", ":runtime", ":protoc-plugin", ":protoc-plugin-buf")
tasks.named<Test>("test") {
    testRepoModules.forEach { dependsOn("$it:publishAllPublicationsToTestRepoRepository") }
    val testRepo = rootProject.layout.buildDirectory.dir("test-repo")
    inputs.dir(testRepo).withPropertyName("testRepo").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("protovalidate.testRepo", testRepo.get().asFile.path)
    systemProperty("protovalidate.version", providers.gradleProperty("VERSION_NAME").get())
    systemProperty("protovalidate.testKitDir", layout.buildDirectory.dir("testkit").get().asFile.path)
    // Read-only view of this build's dependency cache, so fixture builds
    // don't download Kotlin and Wire again.
    systemProperty("protovalidate.roDepCache", gradle.gradleUserHomeDir.resolve("caches").path)
}

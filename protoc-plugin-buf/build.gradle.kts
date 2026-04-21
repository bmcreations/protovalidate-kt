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
}

val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
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

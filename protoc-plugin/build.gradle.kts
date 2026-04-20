plugins {
    kotlin("jvm")
    application
    id("com.google.protobuf")
}

application {
    mainClass.set("dev.bmcreations.protovalidate.plugin.MainKt")
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

sourceSets {
    main {
        proto {
            srcDir(rootProject.file("protos/validate"))
        }
    }
}

tasks.named<Jar>("jar") {
    manifest { attributes["Main-Class"] = "dev.bmcreations.protovalidate.plugin.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.3.20"
        id("com.google.protobuf") version "0.9.6"
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "protovalidate-kt"

include(
    ":runtime",
    ":protoc-plugin-core",
    ":protoc-plugin",
    ":protoc-plugin-buf",
    ":gradle-plugin",
    ":conformance",
    ":pgv-conformance",
)

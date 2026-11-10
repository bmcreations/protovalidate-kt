pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.3.20"
        kotlin("multiplatform") version "2.3.20"
        id("com.google.protobuf") version "0.9.6"
        id("com.vanniktech.maven.publish") version "0.36.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        google()
        mavenCentral()
        // Node.js and Yarn distributions for the runtime module's js target. The
        // Kotlin plugin adds these at project level, which PREFER_SETTINGS ignores.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
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

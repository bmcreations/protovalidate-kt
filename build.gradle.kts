plugins {
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") apply false
}

// Root build file for protovalidate-kt.

// Configure signing for all subprojects. CI provides the signing key via
// ORG_GRADLE_PROJECT_signingInMemoryKey* environment variables.
// Locally, signing is optional (isRequired = false).
val signingKey = providers.gradleProperty("signingInMemoryKey")
subprojects {
    plugins.withType<SigningPlugin> {
        configure<SigningExtension> {
            val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
            val key = signingKey.orNull
            val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
            if (key != null) {
                useInMemoryPgpKeys(keyId, key, password)
            }
        }
    }
    tasks.withType<Sign>().configureEach {
        isRequired = signingKey.isPresent
    }
}

// Exclude conformance modules from the default build — they generate a huge
// amount of code and are run separately in CI via their own job.
// To build them, target the module explicitly: ./gradlew :conformance:jar
val conformanceModules = setOf("conformance", "pgv-conformance")
val requestedTasks = gradle.startParameter.taskNames
val conformanceRequested = requestedTasks.any { arg ->
    conformanceModules.any { mod -> arg.startsWith(":$mod") }
}
if (!conformanceRequested) {
    gradle.taskGraph.whenReady {
        allTasks
            .filter { it.project.name in conformanceModules }
            .forEach { it.enabled = false }
    }
}

plugins {
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") apply false
}

// Root build file for protovalidate-kt.

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

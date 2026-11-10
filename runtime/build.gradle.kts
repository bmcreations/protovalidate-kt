import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()
    js(IR) {
        nodejs()
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The iOS simulator test tasks boot a simulator, so they are off unless
// -PruntimeIosSimulatorTests is passed. The test binaries still compile and
// link (linkDebugTestIos*) because the disabled tasks keep their dependencies.
val runIosSimulatorTests = providers.gradleProperty("runtimeIosSimulatorTests").isPresent
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    enabled = runIosSimulatorTests
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}

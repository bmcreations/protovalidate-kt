package dev.bmcreations.protovalidate.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Consumer builds applying kotlin-multiplatform, Wire 7.1.2 and this plugin,
 * resolved from the build-local test repo (see gradle-plugin/build.gradle.kts).
 */
class KmpWireFunctionalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testRepo = File(System.getProperty("protovalidate.testRepo"))
    private val version = System.getProperty("protovalidate.version")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun `generates, compiles and runs Wire validators in commonMain`() {
        val project = consumer(protovalidate = WIRE_PGV)
        writeProtos(project)
        writeJvmTest(project)

        val tasks = buildList {
            add("jvmTest")
            add("compileKotlinJs")
            if (isMac) add("compileKotlinIosSimulatorArm64")
        }
        val result = run(project, tasks + "--configuration-cache")

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateProtovalidateWire")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jvmTest")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlinJs")?.outcome)
        if (isMac) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlinIosSimulatorArm64")?.outcome)
        }
        val generated = project.resolve("build/generated/protovalidate/wire/commonMain/demo")
        assertTrue("no validators in $generated", generated.resolve("UserValidator.kt").isFile)
        // validate.proto is include-only: no validator generated for it.
        assertFalse(project.resolve("build/generated/protovalidate/wire/commonMain/validate").exists())

        val testResults = project.resolve("build/test-results/jvmTest").listFiles().orEmpty()
            .filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
        assertTrue(testResults, testResults.contains("tests=\"2\""))
        assertTrue(testResults, testResults.contains("failures=\"0\""))

        // Second run reuses the configuration cache and the generated sources.
        val rerun = run(project, tasks + "--configuration-cache")
        assertTrue(rerun.output, rerun.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.UP_TO_DATE, rerun.task(":generateProtovalidateWire")?.outcome)
    }

    @Test
    fun `Wire's generate task runs the validate proto extraction`() {
        val project = consumer(protovalidate = WIRE_PGV)
        writeProtos(project)
        val result = run(project, listOf("generateCommonMainProtos"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":extractProtovalidateWireIncludes")?.outcome)
        assertEquals(null, result.task(":generateProtovalidateWire"))
    }

    @Test
    fun `IDE sync runs validator generation`() {
        val project = consumer(protovalidate = WIRE_PGV)
        val result = run(project, listOf("prepareKotlinIdeaImport", "--dry-run"))
        assertTrue(result.output, result.output.contains(":generateProtovalidateWire SKIPPED"))
    }

    @Test
    fun `WIRE with BUF fails at configuration`() {
        val project = consumer(
            protovalidate = """
                variant.set(ProtoVariant.BUF)
                target.set(MessageTarget.WIRE)
            """,
        )
        val result = runner(project, listOf("help")).buildAndFail()
        assertTrue(result.output, result.output.contains("target = WIRE supports PGV rules only"))
    }

    @Test
    fun `JAVA target on KMP registers no Wire task`() {
        val project = consumer(protovalidate = "")
        val result = run(project, listOf("tasks", "--all"))
        assertFalse(result.output, result.output.contains("generateProtovalidateWire"))
    }

    @Test
    fun `protobuf-gradle-plugin JVM path still generates and compiles`() {
        val project = jvmConsumer("variant.set(ProtoVariant.PGV)")
        val proto = project.resolve("src/main/proto/demo/user.proto")
        proto.parentFile.mkdirs()
        proto.writeText(
            """
            syntax = "proto3";
            package demo;
            option java_package = "demo";
            option java_multiple_files = true;
            import "validate/validate.proto";
            message User { string email = 1 [(validate.rules).string.email = true]; }
            """.trimIndent()
        )
        val result = run(project, listOf("compileKotlin"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
        assertTrue(project.resolve("build/generated/sources/proto/main/validate-kt/demo/UserValidator.kt").isFile)
    }

    @Test
    fun `protobuf-gradle-plugin path rejects WIRE with BUF`() {
        val project = jvmConsumer("target.set(MessageTarget.WIRE)")
        val result = runner(project, listOf("help")).buildAndFail()
        assertTrue(result.output, result.output.contains("target = WIRE supports PGV rules only"))
    }

    private fun jvmConsumer(protovalidate: String): File {
        val project = consumer(protovalidate = "")
        project.resolve("build.gradle.kts").writeText(
            """
            import dev.bmcreations.protovalidate.gradle.MessageTarget
            import dev.bmcreations.protovalidate.gradle.ProtoVariant

            plugins {
                kotlin("jvm") version "2.3.20"
                id("com.google.protobuf") version "0.9.6"
                id("dev.bmcreations.protovalidate") version "$version"
            }

            dependencies { implementation("com.google.protobuf:protobuf-java:4.34.0") }

            protobuf { protoc { artifact = "com.google.protobuf:protoc:4.34.0" } }

            protovalidate { $protovalidate }
            """.trimIndent()
        )
        return project
    }

    private fun consumer(protovalidate: String): File {
        val dir = tmp.newFolder("consumer")
        val repo = testRepo.toURI()
        dir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    exclusiveContent {
                        forRepository { maven("$repo") }
                        filter {
                            includeGroup("dev.bmcreations")
                            includeGroup("dev.bmcreations.protovalidate")
                        }
                    }
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_SETTINGS
                repositories {
                    exclusiveContent {
                        forRepository { maven("$repo") }
                        filter { includeGroup("dev.bmcreations") }
                    }
                    mavenCentral()
                }
            }
            rootProject.name = "consumer"
            """.trimIndent()
        )
        dir.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2g
            kotlin.native.ignoreDisabledTargets=true
            """.trimIndent()
        )
        val ios = if (isMac) "iosSimulatorArm64()" else ""
        dir.resolve("build.gradle.kts").writeText(
            """
            import dev.bmcreations.protovalidate.gradle.MessageTarget
            import dev.bmcreations.protovalidate.gradle.ProtoVariant

            plugins {
                kotlin("multiplatform") version "2.3.20"
                id("com.squareup.wire") version "7.1.2"
                id("dev.bmcreations.protovalidate") version "$version"
            }

            kotlin {
                jvm()
                js(IR) { nodejs() }
                $ios
                sourceSets {
                    jvmTest.dependencies { implementation(kotlin("test-junit")) }
                }
            }

            wire {
                protoPath { srcDir(protovalidate.validateProtoDir) }
                kotlin {}
            }

            protovalidate {
                $protovalidate
            }
            """.trimIndent()
        )
        return dir
    }

    private fun writeProtos(project: File) {
        val proto = project.resolve("src/commonMain/proto/demo/user.proto")
        proto.parentFile.mkdirs()
        proto.writeText(
            """
            syntax = "proto3";
            package demo;

            import "validate/validate.proto";

            message Address {
              string city = 1 [(validate.rules).string.min_len = 1];
            }

            message User {
              message Nickname {
                string value = 1 [(validate.rules).string.max_len = 8];
              }

              string email = 1 [(validate.rules).string.email = true];
              Address address = 2 [(validate.rules).message.required = true];
              repeated string tags = 3 [(validate.rules).repeated.min_items = 1];
              Nickname nickname = 4;

              oneof contact {
                option (validate.required) = true;
                string phone = 5 [(validate.rules).string.min_len = 3];
                string handle = 6 [(validate.rules).string.prefix = "@"];
              }
            }
            """.trimIndent()
        )
    }

    private fun writeJvmTest(project: File) {
        val test = project.resolve("src/jvmTest/kotlin/demo/UserValidateTest.kt")
        test.parentFile.mkdirs()
        test.writeText(
            """
            package demo

            import dev.bmcreations.protovalidate.ValidationResult
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertIs

            class UserValidateTest {
                @Test
                fun validUser() {
                    val user = User(
                        email = "a@example.com",
                        address = Address(city = "Oslo"),
                        tags = listOf("t"),
                        nickname = User.Nickname(value_ = "bob"),
                        phone = "5551234",
                    )
                    assertEquals(ValidationResult.Valid, user.validate())
                }

                @Test
                fun invalidUser() {
                    val user = User(
                        email = "not-an-email",
                        tags = emptyList(),
                        nickname = User.Nickname(value_ = "much-too-long"),
                        handle = "no-at",
                    )
                    val result = assertIs<ValidationResult.Invalid>(user.validate())
                    assertEquals(
                        setOf("email", "address", "tags", "nickname.value", "handle"),
                        result.violations.map { it.field }.toSet(),
                    )
                }
            }
            """.trimIndent()
        )
    }

    private fun runner(project: File, args: List<String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project)
            .withTestKitDir(File(System.getProperty("protovalidate.testKitDir")))
            .withEnvironment(System.getenv() + ("GRADLE_RO_DEP_CACHE" to System.getProperty("protovalidate.roDepCache")))
            .withArguments(args + "--stacktrace")
            .forwardOutput()

    private fun run(project: File, args: List<String>): BuildResult = runner(project, args).build()

    private companion object {
        const val WIRE_PGV = """
            variant.set(ProtoVariant.PGV)
            target.set(MessageTarget.WIRE)
        """
    }
}

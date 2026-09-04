package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Kotlin the PGV generator emits for the fixtures in
 * `src/test/proto/golden`.
 *
 * The conformance suites already check that generated validators behave
 * correctly, but they need a full build plus a Go toolchain, and they only fail
 * once behaviour is wrong. These pin the emitted code itself, so a change in
 * what the generator writes arrives as a diff in review — which is how the
 * oneof `required` bug would have been visible.
 *
 * Regenerate after an intentional change:
 *
 *     ./gradlew :protoc-plugin:test -PupdateGoldens
 */
class GoldenOutputTest {

    private val goldenDir = File(System.getProperty("golden.dir"))

    private fun generate(): Map<String, String> {
        val extractor = PgvRuleExtractor()
        val descriptorSet = File(System.getProperty("golden.descriptorSet"))
        val fileSet = descriptorSet.inputStream().use {
            FileDescriptorSet.parseFrom(it, extractor.createRegistry())
        }
        val fixtures = fileSet.fileList.filter { it.name.startsWith("golden/") }.map { it.name }.toSet()
        assertTrue("no fixture protos in $descriptorSet", fixtures.isNotEmpty())

        return PluginPipeline.generate(fileSet.fileList, fixtures, extractor)
            .associate { it.name to it.content }
    }

    @Test
    fun `emitted output matches the goldens`() {
        val generated = generate()

        if (System.getProperty("golden.update") == "true") {
            goldenDir.deleteRecursively()
            generated.forEach { (name, content) ->
                File(goldenDir, name).apply { parentFile.mkdirs() }.writeText(content)
            }
            return
        }

        val expected = goldenDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(goldenDir).path to it.readText() }

        assertEquals(
            "generated files differ from the goldens; rerun with -PupdateGoldens if intended",
            expected.keys.sorted(), generated.keys.sorted()
        )
        generated.forEach { (name, content) ->
            assertEquals("golden mismatch in $name", expected[name], content)
        }
    }

    /**
     * The invariant behind the 0.1.1 fix, asserted by name so that regenerating
     * the goldens without reading them cannot quietly bless a regression.
     *
     * PGV emits an arm's `required` inside that arm's case, so the check must
     * test presence — never that the arm is the active case, which would make a
     * oneof with more than one required arm impossible to satisfy.
     */
    @Test
    fun `a PGV oneof arm's required check does not assert the active case`() {
        val validator = generate().entries
            .single { it.key.endsWith("MultiRequiredOneofValidator.kt") }.value

        val requiredChecks = validator.lines().filter { "checkRequired(" in it }
        assertEquals(2, requiredChecks.size)
        requiredChecks.forEach { line ->
            assertTrue(
                "a required check must test presence, not the active case: $line",
                "checkRequired(has" in line
            )
        }
    }
}

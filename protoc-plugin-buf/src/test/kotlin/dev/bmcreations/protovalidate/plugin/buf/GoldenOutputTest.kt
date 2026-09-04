package dev.bmcreations.protovalidate.plugin.buf

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.ExtensionRegistry
import dev.bmcreations.protovalidate.plugin.PluginPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Kotlin the buf generator emits for the fixtures in
 * `src/test/proto/golden`.
 *
 * The fixtures are the same message shapes as the PGV module's, in buf syntax,
 * so the two golden directories differ exactly where the dialects differ. That
 * split lives in `RuleExtractor.oneofRequiredAssertsActiveCase` and was
 * otherwise only exercised end-to-end by the conformance suites.
 *
 * Regenerate after an intentional change:
 *
 *     ./gradlew :protoc-plugin-buf:test -PupdateGoldens
 */
class GoldenOutputTest {

    private val goldenDir = File(System.getProperty("golden.dir"))

    private fun generate(): Map<String, String> {
        val extractor = BufRuleExtractor()
        val registry = extractor.createRegistry()
        val descriptorSet = File(System.getProperty("golden.descriptorSet"))
        val fileSet = descriptorSet.inputStream().use {
            FileDescriptorSet.parseFrom(it, registry)
        }

        // Same two-pass setup as main(), so the test drives the production path.
        val dynamicRegistry = ExtensionRegistry.newInstance()
        extractor.dynamicRuleDescriptors =
            registerCustomExtensions(fileSet.fileList, registry, dynamicRegistry)
        extractor.dynamicExtensionRegistry = dynamicRegistry

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
     * The other half of the 0.1.1 fix, asserted by name so that regenerating the
     * goldens without reading them cannot quietly bless a regression.
     *
     * buf `required` carries `has` semantics, so an arm that is not the active
     * case fails its own check. Upstream pins this with
     * OneofRequiredWithRequiredField; the PGV module asserts the opposite.
     */
    @Test
    fun `a buf oneof arm's required check asserts the active case`() {
        val validator = generate().entries
            .single { it.key.endsWith("MultiRequiredOneofValidator.kt") }.value

        val requiredChecks = validator.lines().filter { "checkRequired(" in it }
        assertEquals(2, requiredChecks.size)
        requiredChecks.forEach { line ->
            assertTrue(
                "a required check must assert the active case: $line",
                "checkRequired(scopeCase == " in line
            )
        }
    }
}

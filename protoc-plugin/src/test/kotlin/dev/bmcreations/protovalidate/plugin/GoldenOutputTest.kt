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
 * `golden/` holds the protobuf-java output and `golden-wire/` the `target=wire`
 * output for the same fixtures. Wire output for `golden/boxed/` is generated
 * with `wire_box_oneofs_min_size=2` and the rest with Wire's defaults, matching
 * the Wire options `:pgv-conformance-wire` compiles them with. Regenerate both
 * after an intentional change:
 *
 *     ./gradlew :protoc-plugin:test -PupdateGoldens
 */
class GoldenOutputTest {

    private val goldenDir = File(System.getProperty("golden.dir"))
    private val wireGoldenDir = File(System.getProperty("golden.wireDir"))

    private fun generate(
        target: GeneratorTarget = GeneratorTarget.JAVA,
        wireOptions: WireOptions = WireOptions(),
        include: (String) -> Boolean = { true },
    ): Map<String, String> {
        val extractor = PgvRuleExtractor()
        val descriptorSet = File(System.getProperty("golden.descriptorSet"))
        val fileSet = descriptorSet.inputStream().use {
            FileDescriptorSet.parseFrom(it, extractor.createRegistry())
        }
        val fixtures = fileSet.fileList.map { it.name }
            .filter { it.startsWith("golden/") && include(it) }.toSet()
        assertTrue("no fixture protos in $descriptorSet", fixtures.isNotEmpty())

        return PluginPipeline.generate(fileSet.fileList, fixtures, extractor, target, wireOptions)
            .associate { it.name to it.content }
    }

    @Test
    fun `emitted output matches the goldens`() {
        assertMatchesGoldens(generate(), goldenDir)
    }

    @Test
    fun `wire output matches the wire goldens`() {
        val boxed = { name: String -> name.startsWith(BOXED_FIXTURES) }
        val flat = generate(GeneratorTarget.WIRE) { !boxed(it) }
        val boxedOutput = generate(GeneratorTarget.WIRE, WireOptions(boxOneOfsMinSize = 2), boxed)
        assertTrue("no boxed-oneof fixtures", boxedOutput.isNotEmpty())
        assertMatchesGoldens(flat + boxedOutput, wireGoldenDir)
    }

    /**
     * The boxed golden must read its oneofs through the box, so regenerating
     * it with the wrong options cannot pass unnoticed.
     */
    @Test
    fun `boxed oneofs are read through the box only at the min size`() {
        val validator = generate(GeneratorTarget.WIRE, WireOptions(boxOneOfsMinSize = 2)) {
            it.startsWith(BOXED_FIXTURES)
        }.entries.single { it.key.endsWith("/BoxedOneofValidator.kt") }.value

        assertTrue("choice must be read through its box", "choice?.getOrNull(BoxedOneof.CHOICE_S)" in validator)
        assertTrue("a required boxed oneof is a null box", "choice == null" in validator)
        assertTrue("a one-member oneof stays flat", "this.only" in validator)
        assertTrue("proto3 optional is never boxed", "this.opt" in validator)
    }

    private companion object {
        const val BOXED_FIXTURES = "golden/boxed/"
    }

    private fun assertMatchesGoldens(generated: Map<String, String>, goldenDir: File) {
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

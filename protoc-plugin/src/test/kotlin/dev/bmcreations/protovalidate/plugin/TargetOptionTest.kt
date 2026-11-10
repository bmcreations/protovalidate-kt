package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetOptionTest {
    @Test
    fun defaultsToJava() {
        assertEquals(GeneratorTarget.JAVA, GeneratorTarget.fromParameter(""))
        assertEquals(GeneratorTarget.JAVA, GeneratorTarget.fromParameter("other=1"))
    }

    @Test
    fun parsesWire() {
        assertEquals(GeneratorTarget.WIRE, GeneratorTarget.fromParameter("target=wire"))
        assertEquals(GeneratorTarget.WIRE, GeneratorTarget.fromParameter("other=1, target=wire"))
    }

    @Test
    fun unknownTargetIsAResponseError() {
        val response = respond(
            CodeGeneratorRequest.newBuilder().setParameter("target=swift").build(),
            PgvRuleExtractor(),
        )
        assertTrue(response.hasError())
        assertTrue(response.error, response.error.contains("unknown target `swift`"))
        assertEquals(0, response.fileCount)
    }

    @Test
    fun repeatedTargetIsAResponseError() {
        val response = respond(
            CodeGeneratorRequest.newBuilder().setParameter("target=java,target=wire").build(),
            PgvRuleExtractor(),
        )
        assertTrue(response.error, response.error.contains("more than once"))
    }

    @Test
    fun wireTargetWithNoFilesSucceeds() {
        val response = respond(
            CodeGeneratorRequest.newBuilder().setParameter("target=wire").build(),
            PgvRuleExtractor(),
        )
        assertFalse(response.error, response.hasError())
    }

    private fun errorFor(parameter: String): String {
        val response = respond(CodeGeneratorRequest.newBuilder().setParameter(parameter).build(), PgvRuleExtractor())
        assertTrue("expected an error for `$parameter`", response.hasError())
        assertEquals(0, response.fileCount)
        return response.error
    }

    @Test
    fun boxOneOfsMinSizeDefaultsToWires() {
        assertEquals(5_000, WireOptions.fromParameter("target=wire", GeneratorTarget.WIRE).boxOneOfsMinSize)
    }

    @Test
    fun parsesBoxOneOfsMinSize() {
        assertEquals(
            3,
            WireOptions.fromParameter("target=wire,wire_box_oneofs_min_size=3", GeneratorTarget.WIRE).boxOneOfsMinSize,
        )
        val response = respond(
            CodeGeneratorRequest.newBuilder().setParameter("target=wire,wire_box_oneofs_min_size=1").build(),
            PgvRuleExtractor(),
        )
        assertFalse(response.error, response.hasError())
    }

    @Test
    fun boxOneOfsMinSizeRequiresWire() {
        assertEquals("wire_box_oneofs_min_size requires target=wire", errorFor("wire_box_oneofs_min_size=2"))
        assertEquals("wire_box_oneofs_min_size requires target=wire", errorFor("target=java,wire_box_oneofs_min_size=2"))
    }

    @Test
    fun boxOneOfsMinSizeMustBeAPositiveInteger() {
        for (value in listOf("0", "-1", "abc", "", "2.5", "99999999999")) {
            assertEquals(
                "wire_box_oneofs_min_size must be a positive integer, got `$value`",
                errorFor("target=wire,wire_box_oneofs_min_size=$value"),
            )
        }
    }

    @Test
    fun bareBoxOneOfsMinSizeIsNotIgnored() {
        assertEquals(
            "wire_box_oneofs_min_size must be a positive integer, got ``",
            errorFor("target=wire,wire_box_oneofs_min_size"),
        )
        assertEquals("wire_box_oneofs_min_size requires target=wire", errorFor("wire_box_oneofs_min_size"))
    }

    @Test
    fun repeatedBoxOneOfsMinSizeIsAResponseError() {
        assertEquals(
            "`wire_box_oneofs_min_size` given more than once: 1, 2",
            errorFor("target=wire,wire_box_oneofs_min_size=1,wire_box_oneofs_min_size=2"),
        )
    }
}

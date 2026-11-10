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
}

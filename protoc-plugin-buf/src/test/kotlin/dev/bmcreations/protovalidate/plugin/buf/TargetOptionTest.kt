package dev.bmcreations.protovalidate.plugin.buf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetOptionTest {
    @Test
    fun javaIsAccepted() {
        assertNull(targetError(""))
        assertNull(targetError("target=java"))
    }

    @Test
    fun wireIsRejected() {
        val error = targetError("target=wire")
        assertTrue(error, error!!.contains("target=wire is not supported by protoc-gen-validate-kt-buf"))
    }

    @Test
    fun unknownIsRejected() {
        assertTrue(targetError("target=bogus")!!.contains("unknown target"))
    }

    @Test
    fun wireOptionsAreRejected() {
        val expected = "wire_box_oneofs_min_size is not supported by protoc-gen-validate-kt-buf: it generates " +
            "protobuf-java validators only. Use protoc-gen-validate-kt with target=wire for Wire."
        assertEquals(expected, targetError("wire_box_oneofs_min_size=2"))
        assertEquals(expected, targetError("target=java,wire_box_oneofs_min_size=2"))
    }
}

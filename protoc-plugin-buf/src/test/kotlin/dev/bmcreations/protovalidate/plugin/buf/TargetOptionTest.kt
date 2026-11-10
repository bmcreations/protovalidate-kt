package dev.bmcreations.protovalidate.plugin.buf

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
}

package dev.bmcreations.protovalidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationResultOrThrowTest {

    @Test
    fun `orThrow on Valid does not throw`() {
        ValidationResult.Valid.orThrow()
    }

    @Test(expected = ProtoValidationException::class)
    fun `orThrow on Invalid throws ProtoValidationException`() {
        val violation = FieldViolation(field = "name", rule = "string.min_len", message = "too short")
        ValidationResult.Invalid(listOf(violation)).orThrow()
    }

    @Test
    fun `ProtoValidationException carries violations list`() {
        val violations = listOf(
            FieldViolation(field = "name", rule = "string.min_len", message = "too short"),
            FieldViolation(field = "symbol", rule = "string.max_len", message = "too long"),
        )
        val exception = ProtoValidationException(violations)
        assertEquals(violations, exception.violations)
        assertEquals(2, exception.violations.size)
    }

    @Test
    fun `ProtoValidationException message contains field and violation info`() {
        val violation = FieldViolation(field = "phone", rule = "string.pattern", message = "invalid format")
        val exception = ProtoValidationException(listOf(violation))
        assertTrue(exception.message!!.contains("phone"))
        assertTrue(exception.message!!.contains("invalid format"))
    }
}

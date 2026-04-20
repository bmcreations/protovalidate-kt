package dev.bmcreations.protovalidate

object EnumValidators {

    fun checkConst(value: Int, expected: Int, field: String): FieldViolation? {
        if (value != expected) {
            return FieldViolation(field, "enum.const", "must equal $expected")
        }
        return null
    }

    fun checkIn(value: Int, allowed: List<Int>, field: String): FieldViolation? {
        if (value !in allowed) {
            return FieldViolation(field, "enum.in", "must be in $allowed")
        }
        return null
    }

    fun checkNotIn(value: Int, disallowed: List<Int>, field: String): FieldViolation? {
        if (value in disallowed) {
            return FieldViolation(field, "enum.not_in", "must not be in $disallowed")
        }
        return null
    }

    fun checkDefinedOnly(value: Int, definedValues: List<Int>, field: String): FieldViolation? {
        if (value !in definedValues) {
            return FieldViolation(field, "enum.defined_only", "must be a defined enum value")
        }
        return null
    }
}

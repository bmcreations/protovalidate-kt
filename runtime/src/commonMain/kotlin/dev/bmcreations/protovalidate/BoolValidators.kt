package dev.bmcreations.protovalidate

object BoolValidators {

    fun checkConst(value: Boolean, expected: Boolean, field: String): FieldViolation? {
        if (value != expected) {
            return FieldViolation(field, "bool.const", "must equal $expected")
        }
        return null
    }
}

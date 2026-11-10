package dev.bmcreations.protovalidate

object MessageValidators {

    fun checkRequired(hasField: Boolean, field: String): FieldViolation? {
        if (!hasField) {
            return FieldViolation(field, "required", "value is required")
        }
        return null
    }
}

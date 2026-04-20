package dev.bmcreations.protovalidate

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val violations: List<FieldViolation>) : ValidationResult()
}

val ValidationResult.isValid: Boolean get() = this is ValidationResult.Valid

fun ValidationResult.violationsOrEmpty(): List<FieldViolation> =
    (this as? ValidationResult.Invalid)?.violations.orEmpty()

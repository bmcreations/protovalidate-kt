package dev.bmcreations.protovalidate

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val violations: List<FieldViolation>) : ValidationResult()
}

val ValidationResult.isValid: Boolean get() = this is ValidationResult.Valid

fun ValidationResult.violationsOrEmpty(): List<FieldViolation> =
    (this as? ValidationResult.Invalid)?.violations.orEmpty()

class ProtoValidationException(
    val violations: List<FieldViolation>
) : IllegalArgumentException(
    "Proto validation failed: ${violations.joinToString { "${it.field}: ${it.message}" }}"
)

fun ValidationResult.orThrow() {
    if (this is ValidationResult.Invalid) throw ProtoValidationException(violations)
}

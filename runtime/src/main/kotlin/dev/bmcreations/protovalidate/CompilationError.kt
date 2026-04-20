package dev.bmcreations.protovalidate

/**
 * Thrown by generated validators when a type mismatch is detected at codegen time.
 * This indicates the proto constraint type doesn't match the field type.
 */
class CompilationError(message: String) : RuntimeException(message)

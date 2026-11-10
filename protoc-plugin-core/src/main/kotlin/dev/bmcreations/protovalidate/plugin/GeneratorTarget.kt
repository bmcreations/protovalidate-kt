package dev.bmcreations.protovalidate.plugin

/** The message runtime generated validators compile against. */
enum class GeneratorTarget(val optionValue: String) {
    /** protobuf-java (and protobuf-kotlin) message classes. */
    JAVA("java"),

    /** Square Wire's Kotlin message classes, compiled for any Kotlin target. */
    WIRE("wire");

    companion object {
        /**
         * Reads `target=<java|wire>` from a protoc plugin parameter string
         * (comma-separated `key=value` pairs). Other keys are left for the
         * caller. A missing `target` means [JAVA].
         */
        fun fromParameter(parameter: String): GeneratorTarget {
            val values = parameter.split(',')
                .map { it.trim() }
                .filter { it.startsWith("target=") }
                .map { it.removePrefix("target=") }
            if (values.size > 1) {
                throw PluginOptionException("`target` given more than once: ${values.joinToString()}")
            }
            val value = values.singleOrNull() ?: return JAVA
            return entries.firstOrNull { it.optionValue == value }
                ?: throw PluginOptionException(
                    "unknown target `$value`; expected one of ${entries.joinToString { it.optionValue }}"
                )
        }
    }
}

/**
 * A request the generator can't serve. Entry points report its message through
 * `CodeGeneratorResponse.error`, which protoc prints as a plugin failure.
 */
class PluginOptionException(message: String) : RuntimeException(message)

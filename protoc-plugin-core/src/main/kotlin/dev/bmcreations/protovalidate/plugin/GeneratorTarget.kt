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
            val value = singleValue(parameter, "target") ?: return JAVA
            return entries.firstOrNull { it.optionValue == value }
                ?: throw PluginOptionException(
                    "unknown target `$value`; expected one of ${entries.joinToString { it.optionValue }}"
                )
        }
    }
}

/**
 * Options that change how Wire's Kotlin output is shaped, mirroring the Wire
 * Gradle plugin's `kotlin {}` settings of the same name. They must match the
 * Wire configuration that generated the message classes.
 *
 * @property boxOneOfsMinSize Wire's `boxOneOfsMinSize`: a oneof with at least
 *   this many members is a single `OneOf<Key, Value>?` property instead of one
 *   nullable property per member.
 */
data class WireOptions(val boxOneOfsMinSize: Int = DEFAULT_BOX_ONEOFS_MIN_SIZE) {
    companion object {
        /** Wire 7's default for `boxOneOfsMinSize`. */
        const val DEFAULT_BOX_ONEOFS_MIN_SIZE = 5_000

        /** Plugin parameter carrying [boxOneOfsMinSize]. */
        const val BOX_ONEOFS_MIN_SIZE_KEY = "wire_box_oneofs_min_size"

        /** The `wire_*` keys, which only `target=wire` accepts. */
        val KEYS = listOf(BOX_ONEOFS_MIN_SIZE_KEY)

        /** The [KEYS] present in a protoc plugin parameter string. */
        fun keysIn(parameter: String): List<String> = KEYS.filter { singleValue(parameter, it) != null }

        /**
         * Reads the `wire_*` keys from a protoc plugin parameter string. Fails
         * when one is given for a [target] other than [GeneratorTarget.WIRE].
         */
        fun fromParameter(parameter: String, target: GeneratorTarget): WireOptions {
            val given = keysIn(parameter)
            if (given.isNotEmpty() && target != GeneratorTarget.WIRE) {
                throw PluginOptionException("${given.first()} requires target=wire")
            }
            val boxValue = singleValue(parameter, BOX_ONEOFS_MIN_SIZE_KEY)
                ?: return WireOptions()
            val boxOneOfsMinSize = boxValue.toIntOrNull()?.takeIf { it > 0 }
                ?: throw PluginOptionException(
                    "$BOX_ONEOFS_MIN_SIZE_KEY must be a positive integer, got `$boxValue`"
                )
            return WireOptions(boxOneOfsMinSize)
        }
    }
}

/** The value of [key] in a comma-separated `key=value` parameter, or null; fails if [key] repeats. */
internal fun singleValue(parameter: String, key: String): String? {
    val values = parameter.split(',')
        .map { it.trim() }
        .filter { it == key || it.startsWith("$key=") }
        // A bare key is an empty value, so it fails validation instead of being ignored.
        .map { it.removePrefix(key).removePrefix("=") }
    if (values.size > 1) {
        throw PluginOptionException("`$key` given more than once: ${values.joinToString()}")
    }
    return values.singleOrNull()
}

/**
 * A request the generator can't serve. Entry points report its message through
 * `CodeGeneratorResponse.error`, which protoc prints as a plugin failure.
 */
class PluginOptionException(message: String) : RuntimeException(message)

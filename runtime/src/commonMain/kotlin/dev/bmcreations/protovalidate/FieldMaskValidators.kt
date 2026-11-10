package dev.bmcreations.protovalidate

/**
 * Validators for google.protobuf.FieldMask constraints.
 *
 * FieldMask comparisons use the paths list. For `const`, paths are compared sorted.
 * For `in` / `not_in`, each path in the field value must be (or must not be) equal to
 * an allowed/denied path or a sub-path of it (i.e., starts with "<allowed>." prefix).
 */
object FieldMaskValidators {

    /**
     * Checks that the FieldMask's paths, when sorted, equal the expected sorted paths.
     */
    fun checkConst(paths: List<String>, expectedSortedPaths: List<String>, field: String): FieldViolation? {
        val actualSorted = paths.sorted()
        if (actualSorted != expectedSortedPaths) {
            val formatted = expectedSortedPaths.joinToString(prefix = "[", postfix = "]") { it }
            return FieldViolation(field, "field_mask.const", "must equal paths $formatted")
        }
        return null
    }

    /**
     * Checks that every path in the FieldMask is in the allowed list or is a sub-path
     * of an allowed path (i.e., starts with "<allowed>.").
     */
    fun checkIn(paths: List<String>, allowed: List<String>, field: String): FieldViolation? {
        val violation = paths.any { path ->
            !allowed.any { f -> path == f || path.startsWith("$f.") }
        }
        if (violation) {
            val formatted = allowed.joinToString(prefix = "[", postfix = "]") { it }
            return FieldViolation(field, "field_mask.in", "must only contain paths in $formatted")
        }
        return null
    }

    /**
     * Checks that no path in the FieldMask is in the denied list or is a sub-path of a denied path.
     */
    fun checkNotIn(paths: List<String>, denied: List<String>, field: String): FieldViolation? {
        val violation = paths.any { path ->
            denied.any { f -> path == f || path.startsWith("$f.") }
        }
        if (violation) {
            val formatted = denied.joinToString(prefix = "[", postfix = "]") { it }
            return FieldViolation(field, "field_mask.not_in", "must not contain any paths in $formatted")
        }
        return null
    }
}

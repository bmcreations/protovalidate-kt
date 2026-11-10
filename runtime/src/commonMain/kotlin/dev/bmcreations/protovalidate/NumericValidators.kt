package dev.bmcreations.protovalidate

object NumericValidators {

    private fun <T> isNaN(value: T): Boolean =
        value is Double && value.isNaN() || value is Float && value.isNaN()

    /**
     * Formats a numeric value for display in error messages.
     * For Float and Double, whole-number values are displayed without the decimal suffix
     * (e.g., `0` instead of `0.0`, `123` instead of `123.0`, but `456.789` stays as-is).
     */
    private fun <T> formatNumeric(value: T): String {
        return when (value) {
            is Float -> if (value == kotlin.math.floor(value.toDouble()).toFloat() && value.isFinite()) value.toLong().toString() else value.toString()
            is Double -> if (value == kotlin.math.floor(value) && value.isFinite()) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
    }

    private fun <T> formatList(list: List<T>): String =
        list.joinToString(", ", "[", "]") { formatNumeric(it) }

    fun checkFinite(value: Float, field: String, rule: String = "float.finite"): FieldViolation? {
        if (!value.isFinite()) {
            return FieldViolation(field, rule, "must be finite")
        }
        return null
    }

    fun checkFinite(value: Double, field: String, rule: String = "double.finite"): FieldViolation? {
        if (!value.isFinite()) {
            return FieldViolation(field, rule, "must be finite")
        }
        return null
    }

    fun <T : Comparable<T>> checkGt(value: T, limit: T, field: String, rule: String = "gt"): FieldViolation? {
        if (isNaN(value)) {
            return FieldViolation(field, rule, "must not be NaN")
        }
        if (value <= limit) {
            return FieldViolation(field, rule, "must be greater than ${formatNumeric(limit)}")
        }
        return null
    }

    fun <T : Comparable<T>> checkGte(value: T, limit: T, field: String, rule: String = "gte"): FieldViolation? {
        if (isNaN(value)) {
            return FieldViolation(field, rule, "must not be NaN")
        }
        if (value < limit) {
            return FieldViolation(field, rule, "must be greater than or equal to ${formatNumeric(limit)}")
        }
        return null
    }

    fun <T : Comparable<T>> checkLt(value: T, limit: T, field: String, rule: String = "lt"): FieldViolation? {
        if (isNaN(value)) {
            return FieldViolation(field, rule, "must not be NaN")
        }
        if (value >= limit) {
            return FieldViolation(field, rule, "must be less than ${formatNumeric(limit)}")
        }
        return null
    }

    fun <T : Comparable<T>> checkLte(value: T, limit: T, field: String, rule: String = "lte"): FieldViolation? {
        if (isNaN(value)) {
            return FieldViolation(field, rule, "must not be NaN")
        }
        if (value > limit) {
            return FieldViolation(field, rule, "must be less than or equal to ${formatNumeric(limit)}")
        }
        return null
    }

    fun <T : Comparable<T>> checkConst(value: T, expected: T, field: String, rule: String = "const"): FieldViolation? {
        if (value != expected) {
            return FieldViolation(field, rule, "must equal ${formatNumeric(expected)}")
        }
        return null
    }

    fun <T> checkIn(value: T, allowed: List<T>, field: String, rule: String = "in"): FieldViolation? {
        if (value !in allowed) {
            return FieldViolation(field, rule, "must be in list ${formatList(allowed)}")
        }
        return null
    }

    fun <T> checkNotIn(value: T, disallowed: List<T>, field: String, rule: String = "not_in"): FieldViolation? {
        if (value in disallowed) {
            return FieldViolation(field, rule, "must not be in list ${formatList(disallowed)}")
        }
        return null
    }

    /**
     * Combined range check for when both a lower (gt/gte) and upper (lt/lte) bound are present.
     *
     * Automatically determines inclusive vs exclusive range:
     * - Inclusive: lower < upper (or lower == upper with both inclusive) → value must be within
     * - Exclusive: lower >= upper (or lower > upper with both inclusive) → value must be outside
     *
     * Returns a single violation with combined rule_id (e.g., "int32.gt_lt" or "int32.gt_lt_exclusive").
     */
    fun <T : Comparable<T>> checkRange(
        value: T, lower: T, upper: T,
        lowerInclusive: Boolean, upperInclusive: Boolean,
        field: String, rulePrefix: String
    ): FieldViolation? {
        val lowerName = if (lowerInclusive) "gte" else "gt"
        val upperName = if (upperInclusive) "lte" else "lt"

        // Exclusive when the range is impossible under inclusive semantics
        val isExclusive = if (lowerInclusive && upperInclusive) {
            lower > upper
        } else {
            lower >= upper
        }

        if (isNaN(value)) {
            val suffix = if (isExclusive) "_exclusive" else ""
            return FieldViolation(field, "$rulePrefix.${lowerName}_${upperName}$suffix", "must not be NaN")
        }

        if (isExclusive) {
            val lowerOk = if (lowerInclusive) value >= lower else value > lower
            val upperOk = if (upperInclusive) value <= upper else value < upper
            if (!lowerOk && !upperOk) {
                return FieldViolation(
                    field,
                    "$rulePrefix.${lowerName}_${upperName}_exclusive",
                    "must be $lowerName ${formatNumeric(lower)} or $upperName ${formatNumeric(upper)}"
                )
            }
        } else {
            val lowerOk = if (lowerInclusive) value >= lower else value > lower
            val upperOk = if (upperInclusive) value <= upper else value < upper
            if (!lowerOk || !upperOk) {
                return FieldViolation(
                    field,
                    "$rulePrefix.${lowerName}_${upperName}",
                    "must be $lowerName ${formatNumeric(lower)} and $upperName ${formatNumeric(upper)}"
                )
            }
        }
        return null
    }
}

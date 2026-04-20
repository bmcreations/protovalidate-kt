package dev.bmcreations.protovalidate

object DurationValidators {

    internal fun compareDuration(aSeconds: Long, aNanos: Int, bSeconds: Long, bNanos: Int): Int {
        val secCmp = aSeconds.compareTo(bSeconds)
        return if (secCmp != 0) secCmp else aNanos.compareTo(bNanos)
    }

    internal fun formatDuration(seconds: Long, nanos: Int): String {
        return if (nanos == 0) "${seconds}s" else "${seconds}.${"%09d".format(nanos).trimEnd('0')}s"
    }

    fun checkRequired(hasField: Boolean, field: String): FieldViolation? {
        if (!hasField) {
            return FieldViolation(field, "required", "value is required")
        }
        return null
    }

    fun checkConst(seconds: Long, nanos: Int, constSeconds: Long, constNanos: Int, field: String): FieldViolation? {
        if (compareDuration(seconds, nanos, constSeconds, constNanos) != 0) {
            return FieldViolation(field, "duration.const", "must equal ${formatDuration(constSeconds, constNanos)}")
        }
        return null
    }

    fun checkLt(seconds: Long, nanos: Int, ltSeconds: Long, ltNanos: Int, field: String): FieldViolation? {
        if (compareDuration(seconds, nanos, ltSeconds, ltNanos) >= 0) {
            return FieldViolation(field, "duration.lt", "must be less than ${formatDuration(ltSeconds, ltNanos)}")
        }
        return null
    }

    fun checkLte(seconds: Long, nanos: Int, lteSeconds: Long, lteNanos: Int, field: String): FieldViolation? {
        if (compareDuration(seconds, nanos, lteSeconds, lteNanos) > 0) {
            return FieldViolation(field, "duration.lte", "must be less than or equal to ${formatDuration(lteSeconds, lteNanos)}")
        }
        return null
    }

    fun checkGt(seconds: Long, nanos: Int, gtSeconds: Long, gtNanos: Int, field: String): FieldViolation? {
        if (compareDuration(seconds, nanos, gtSeconds, gtNanos) <= 0) {
            return FieldViolation(field, "duration.gt", "must be greater than ${formatDuration(gtSeconds, gtNanos)}")
        }
        return null
    }

    fun checkGte(seconds: Long, nanos: Int, gteSeconds: Long, gteNanos: Int, field: String): FieldViolation? {
        if (compareDuration(seconds, nanos, gteSeconds, gteNanos) < 0) {
            return FieldViolation(field, "duration.gte", "must be greater than or equal to ${formatDuration(gteSeconds, gteNanos)}")
        }
        return null
    }

    fun checkIn(seconds: Long, nanos: Int, allowed: List<Pair<Long, Int>>, field: String): FieldViolation? {
        if (allowed.none { (s, n) -> compareDuration(seconds, nanos, s, n) == 0 }) {
            return FieldViolation(field, "duration.in", "must be in the allowed list")
        }
        return null
    }

    fun checkNotIn(seconds: Long, nanos: Int, disallowed: List<Pair<Long, Int>>, field: String): FieldViolation? {
        if (disallowed.any { (s, n) -> compareDuration(seconds, nanos, s, n) == 0 }) {
            return FieldViolation(field, "duration.not_in", "must not be in the disallowed list")
        }
        return null
    }

    fun checkRange(
        seconds: Long, nanos: Int,
        lowerSeconds: Long, lowerNanos: Int,
        upperSeconds: Long, upperNanos: Int,
        lowerInclusive: Boolean, upperInclusive: Boolean,
        field: String
    ): FieldViolation? {
        val lowerName = if (lowerInclusive) "gte" else "gt"
        val upperName = if (upperInclusive) "lte" else "lt"
        val cmpLower = compareDuration(lowerSeconds, lowerNanos, upperSeconds, upperNanos)

        val isExclusive = if (lowerInclusive && upperInclusive) cmpLower > 0 else cmpLower >= 0

        val lowerOk = {
            val cmp = compareDuration(seconds, nanos, lowerSeconds, lowerNanos)
            if (lowerInclusive) cmp >= 0 else cmp > 0
        }
        val upperOk = {
            val cmp = compareDuration(seconds, nanos, upperSeconds, upperNanos)
            if (upperInclusive) cmp <= 0 else cmp < 0
        }

        if (isExclusive) {
            if (!lowerOk() && !upperOk()) {
                return FieldViolation(
                    field,
                    "duration.${lowerName}_${upperName}_exclusive",
                    "must be $lowerName ${formatDuration(lowerSeconds, lowerNanos)} or $upperName ${formatDuration(upperSeconds, upperNanos)}"
                )
            }
        } else {
            if (!lowerOk() || !upperOk()) {
                return FieldViolation(
                    field,
                    "duration.${lowerName}_${upperName}",
                    "must be $lowerName ${formatDuration(lowerSeconds, lowerNanos)} and $upperName ${formatDuration(upperSeconds, upperNanos)}"
                )
            }
        }
        return null
    }
}

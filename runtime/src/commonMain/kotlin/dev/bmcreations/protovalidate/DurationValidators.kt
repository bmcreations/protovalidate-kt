package dev.bmcreations.protovalidate

object DurationValidators {

    private const val NANOS_PER_SECOND = 1_000_000_000L

    /**
     * Compares two (seconds, nanos) pairs by the instant or length they denote.
     *
     * Protobuf normalises with truncated seconds and same-sign nanos (-1.5s is
     * seconds -1, nanos -500000000); Wire floors the seconds and keeps nanos
     * non-negative (seconds -2, nanos 500000000). Both are reduced to the floored
     * form before comparing, so either may be passed on either side.
     */
    internal fun compareDuration(aSeconds: Long, aNanos: Int, bSeconds: Long, bNanos: Int): Int {
        val aSec = aSeconds + aNanos.toLong().floorDiv(NANOS_PER_SECOND)
        val bSec = bSeconds + bNanos.toLong().floorDiv(NANOS_PER_SECOND)
        val secCmp = aSec.compareTo(bSec)
        if (secCmp != 0) return secCmp
        return aNanos.toLong().mod(NANOS_PER_SECOND).compareTo(bNanos.toLong().mod(NANOS_PER_SECOND))
    }

    /**
     * Formats a duration as protobuf JSON does (`1.5s`, `-0.25s`), whatever sign
     * convention [seconds] and [nanos] use. The sign comes from the whole value.
     */
    internal fun formatDuration(seconds: Long, nanos: Int): String {
        // Move to truncated seconds with same-sign nanos, |nanos| < 1e9.
        var sec = seconds + nanos / NANOS_PER_SECOND
        var nan = nanos % NANOS_PER_SECOND
        if (sec > 0 && nan < 0) {
            sec -= 1
            nan += NANOS_PER_SECOND
        } else if (sec < 0 && nan > 0) {
            sec += 1
            nan -= NANOS_PER_SECOND
        }
        if (nan == 0L) return "${sec}s"
        val negative = sec < 0 || nan < 0
        val absSec = sec.toString().removePrefix("-")
        val frac = (if (nan < 0) -nan else nan).toString().padStart(9, '0').trimEnd('0')
        return "${if (negative) "-" else ""}$absSec.${frac}s"
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

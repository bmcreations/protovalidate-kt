package dev.bmcreations.protovalidate

import java.time.Instant
import java.time.format.DateTimeFormatter

object TimestampValidators {

    private fun formatRfc3339(seconds: Long, nanos: Int): String {
        val instant = Instant.ofEpochSecond(seconds, nanos.toLong())
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    fun checkRequired(hasField: Boolean, field: String): FieldViolation? {
        if (!hasField) {
            return FieldViolation(field, "required", "value is required")
        }
        return null
    }

    fun checkConst(seconds: Long, nanos: Int, constSeconds: Long, constNanos: Int, field: String): FieldViolation? {
        if (DurationValidators.compareDuration(seconds, nanos, constSeconds, constNanos) != 0) {
            return FieldViolation(field, "timestamp.const", "must equal ${formatRfc3339(constSeconds, constNanos)}")
        }
        return null
    }

    fun checkLt(seconds: Long, nanos: Int, ltSeconds: Long, ltNanos: Int, field: String): FieldViolation? {
        if (DurationValidators.compareDuration(seconds, nanos, ltSeconds, ltNanos) >= 0) {
            return FieldViolation(field, "timestamp.lt", "must be less than ${formatRfc3339(ltSeconds, ltNanos)}")
        }
        return null
    }

    fun checkLte(seconds: Long, nanos: Int, lteSeconds: Long, lteNanos: Int, field: String): FieldViolation? {
        if (DurationValidators.compareDuration(seconds, nanos, lteSeconds, lteNanos) > 0) {
            return FieldViolation(field, "timestamp.lte", "must be less than or equal to ${formatRfc3339(lteSeconds, lteNanos)}")
        }
        return null
    }

    fun checkGt(seconds: Long, nanos: Int, gtSeconds: Long, gtNanos: Int, field: String): FieldViolation? {
        if (DurationValidators.compareDuration(seconds, nanos, gtSeconds, gtNanos) <= 0) {
            return FieldViolation(field, "timestamp.gt", "must be greater than ${formatRfc3339(gtSeconds, gtNanos)}")
        }
        return null
    }

    fun checkGte(seconds: Long, nanos: Int, gteSeconds: Long, gteNanos: Int, field: String): FieldViolation? {
        if (DurationValidators.compareDuration(seconds, nanos, gteSeconds, gteNanos) < 0) {
            return FieldViolation(field, "timestamp.gte", "must be greater than or equal to ${formatRfc3339(gteSeconds, gteNanos)}")
        }
        return null
    }

    fun checkLtNow(seconds: Long, nanos: Int, field: String): FieldViolation? {
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000L
        val nowNanos = ((nowMillis % 1000L) * 1_000_000L).toInt()
        if (DurationValidators.compareDuration(seconds, nanos, nowSeconds, nowNanos) >= 0) {
            return FieldViolation(field, "timestamp.lt_now", "must be in the past")
        }
        return null
    }

    fun checkGtNow(seconds: Long, nanos: Int, field: String): FieldViolation? {
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000L
        val nowNanos = ((nowMillis % 1000L) * 1_000_000L).toInt()
        if (DurationValidators.compareDuration(seconds, nanos, nowSeconds, nowNanos) <= 0) {
            return FieldViolation(field, "timestamp.gt_now", "must be in the future")
        }
        return null
    }

    fun checkWithin(seconds: Long, nanos: Int, withinSeconds: Long, withinNanos: Int, field: String): FieldViolation? {
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000L
        val nowNanos = ((nowMillis % 1000L) * 1_000_000L).toInt()
        val diffTotalNanos: Long = run {
            val dSec = seconds - nowSeconds
            val dNano = nanos.toLong() - nowNanos.toLong()
            val total = dSec * 1_000_000_000L + dNano
            if (total < 0) -total else total
        }
        val withinTotalNanos = withinSeconds * 1_000_000_000L + withinNanos.toLong()
        if (diffTotalNanos > withinTotalNanos) {
            return FieldViolation(field, "timestamp.within", "must be within ${DurationValidators.formatDuration(withinSeconds, withinNanos)} of now")
        }
        return null
    }

    fun checkIn(seconds: Long, nanos: Int, allowed: List<Pair<Long, Int>>, field: String): FieldViolation? {
        if (allowed.none { (s, n) -> DurationValidators.compareDuration(seconds, nanos, s, n) == 0 }) {
            return FieldViolation(field, "timestamp.in", "must be in the allowed list")
        }
        return null
    }

    fun checkNotIn(seconds: Long, nanos: Int, disallowed: List<Pair<Long, Int>>, field: String): FieldViolation? {
        if (disallowed.any { (s, n) -> DurationValidators.compareDuration(seconds, nanos, s, n) == 0 }) {
            return FieldViolation(field, "timestamp.not_in", "must not be in the disallowed list")
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
        val cmpLower = DurationValidators.compareDuration(lowerSeconds, lowerNanos, upperSeconds, upperNanos)

        val isExclusive = if (lowerInclusive && upperInclusive) cmpLower > 0 else cmpLower >= 0

        val lowerOk = {
            val cmp = DurationValidators.compareDuration(seconds, nanos, lowerSeconds, lowerNanos)
            if (lowerInclusive) cmp >= 0 else cmp > 0
        }
        val upperOk = {
            val cmp = DurationValidators.compareDuration(seconds, nanos, upperSeconds, upperNanos)
            if (upperInclusive) cmp <= 0 else cmp < 0
        }

        if (isExclusive) {
            if (!lowerOk() && !upperOk()) {
                return FieldViolation(field, "timestamp.${lowerName}_${upperName}_exclusive", "must be outside the range")
            }
        } else {
            if (!lowerOk() || !upperOk()) {
                return FieldViolation(field, "timestamp.${lowerName}_${upperName}", "must be within the range")
            }
        }
        return null
    }
}

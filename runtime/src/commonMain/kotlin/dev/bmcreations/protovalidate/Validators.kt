package dev.bmcreations.protovalidate

/**
 * Facade that delegates to domain-specific validator objects.
 * Generated code calls `Validators.check*()` — this preserves that API
 * while keeping the implementation split into focused files.
 */
object Validators {

    // ── String rules ──

    fun checkStringPattern(value: String, pattern: String, field: String): FieldViolation? =
        StringValidators.checkPattern(value, pattern, field)

    fun checkStringMinLen(value: String, min: Long, field: String): FieldViolation? =
        StringValidators.checkMinLen(value, min, field)

    fun checkStringMaxLen(value: String, max: Long, field: String): FieldViolation? =
        StringValidators.checkMaxLen(value, max, field)

    fun checkStringLen(value: String, len: Long, field: String): FieldViolation? =
        StringValidators.checkLen(value, len, field)

    fun checkStringMinBytes(value: String, min: Long, field: String): FieldViolation? =
        StringValidators.checkMinBytes(value, min, field)

    fun checkStringMaxBytes(value: String, max: Long, field: String): FieldViolation? =
        StringValidators.checkMaxBytes(value, max, field)

    fun checkStringLenBytes(value: String, len: Long, field: String): FieldViolation? =
        StringValidators.checkLenBytes(value, len, field)

    fun checkStringIn(value: String, allowed: List<String>, field: String): FieldViolation? =
        StringValidators.checkIn(value, allowed, field)

    fun checkStringNotIn(value: String, disallowed: List<String>, field: String): FieldViolation? =
        StringValidators.checkNotIn(value, disallowed, field)

    fun checkStringConst(value: String, expected: String, field: String): FieldViolation? =
        StringValidators.checkConst(value, expected, field)

    fun checkStringPrefix(value: String, prefix: String, field: String): FieldViolation? =
        StringValidators.checkPrefix(value, prefix, field)

    fun checkStringSuffix(value: String, suffix: String, field: String): FieldViolation? =
        StringValidators.checkSuffix(value, suffix, field)

    fun checkStringContains(value: String, substring: String, field: String): FieldViolation? =
        StringValidators.checkContains(value, substring, field)

    fun checkStringNotContains(value: String, substring: String, field: String): FieldViolation? =
        StringValidators.checkNotContains(value, substring, field)

    fun checkStringEmail(value: String, field: String): FieldViolation? =
        StringValidators.checkEmail(value, field)

    fun checkStringEmailPgv(value: String, field: String): FieldViolation? =
        StringValidators.checkEmailPgv(value, field)

    fun checkStringUri(value: String, field: String): FieldViolation? =
        StringValidators.checkUri(value, field)

    fun checkStringUuid(value: String, field: String): FieldViolation? =
        StringValidators.checkUuid(value, field)

    fun checkStringHostname(value: String, field: String): FieldViolation? =
        StringValidators.checkHostname(value, field)

    fun checkStringIp(value: String, field: String): FieldViolation? =
        StringValidators.checkIp(value, field)

    fun checkStringIpv4(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv4(value, field)

    fun checkStringIpv6(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv6(value, field)

    fun checkStringUriRef(value: String, field: String): FieldViolation? =
        StringValidators.checkUriRef(value, field)

    fun checkStringAddress(value: String, field: String): FieldViolation? =
        StringValidators.checkAddress(value, field)

    fun checkStringHttpHeaderName(value: String, strict: Boolean, field: String): FieldViolation? =
        StringValidators.checkHttpHeaderName(value, strict, field)

    fun checkStringHttpHeaderValue(value: String, strict: Boolean, field: String): FieldViolation? =
        StringValidators.checkHttpHeaderValue(value, strict, field)

    fun checkStringHostAndPort(value: String, field: String): FieldViolation? =
        StringValidators.checkHostAndPort(value, field)

    fun checkStringUlid(value: String, field: String): FieldViolation? =
        StringValidators.checkUlid(value, field)

    fun checkStringTuuid(value: String, field: String): FieldViolation? =
        StringValidators.checkTuuid(value, field)

    fun checkStringIpWithPrefixlen(value: String, field: String): FieldViolation? =
        StringValidators.checkIpWithPrefixlen(value, field)

    fun checkStringIpv4WithPrefixlen(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv4WithPrefixlen(value, field)

    fun checkStringIpv6WithPrefixlen(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv6WithPrefixlen(value, field)

    fun checkStringIpPrefix(value: String, field: String): FieldViolation? =
        StringValidators.checkIpPrefix(value, field)

    fun checkStringIpv4Prefix(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv4Prefix(value, field)

    fun checkStringIpv6Prefix(value: String, field: String): FieldViolation? =
        StringValidators.checkIpv6Prefix(value, field)

    fun checkStringProtobufFqn(value: String, field: String): FieldViolation? =
        StringValidators.checkProtobufFqn(value, field)

    fun checkStringProtobufDotFqn(value: String, field: String): FieldViolation? =
        StringValidators.checkProtobufDotFqn(value, field)

    // ── Bytes rules ──

    fun checkBytesLen(value: ByteArray, len: Long, field: String): FieldViolation? =
        BytesValidators.checkLen(value, len, field)

    fun checkBytesMinLen(value: ByteArray, min: Long, field: String): FieldViolation? =
        BytesValidators.checkMinLen(value, min, field)

    fun checkBytesMaxLen(value: ByteArray, max: Long, field: String): FieldViolation? =
        BytesValidators.checkMaxLen(value, max, field)

    fun checkBytesConst(value: ByteArray, expected: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkConst(value, expected, field)

    fun checkBytesPattern(value: ByteArray, pattern: String, field: String): FieldViolation? =
        BytesValidators.checkPattern(value, pattern, field)

    fun checkBytesPrefix(value: ByteArray, prefix: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkPrefix(value, prefix, field)

    fun checkBytesSuffix(value: ByteArray, suffix: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkSuffix(value, suffix, field)

    fun checkBytesContains(value: ByteArray, contained: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkContains(value, contained, field)

    fun checkBytesIn(value: ByteArray, allowed: List<ByteArray>, field: String): FieldViolation? =
        BytesValidators.checkIn(value, allowed, field)

    fun checkBytesNotIn(value: ByteArray, disallowed: List<ByteArray>, field: String): FieldViolation? =
        BytesValidators.checkNotIn(value, disallowed, field)

    fun checkBytesIp(value: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkIp(value, field)

    fun checkBytesIpv4(value: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkIpv4(value, field)

    fun checkBytesIpv6(value: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkIpv6(value, field)

    fun checkBytesUuid(value: ByteArray, field: String): FieldViolation? =
        BytesValidators.checkUuid(value, field)

    // ── Comparable rules (numeric, etc.) ──

    fun checkFinite(value: Float, field: String, rule: String = "float.finite"): FieldViolation? =
        NumericValidators.checkFinite(value, field, rule)

    fun checkFinite(value: Double, field: String, rule: String = "double.finite"): FieldViolation? =
        NumericValidators.checkFinite(value, field, rule)

    fun <T : Comparable<T>> checkGt(value: T, limit: T, field: String, rule: String = "gt"): FieldViolation? =
        NumericValidators.checkGt(value, limit, field, rule)

    fun <T : Comparable<T>> checkGte(value: T, limit: T, field: String, rule: String = "gte"): FieldViolation? =
        NumericValidators.checkGte(value, limit, field, rule)

    fun <T : Comparable<T>> checkLt(value: T, limit: T, field: String, rule: String = "lt"): FieldViolation? =
        NumericValidators.checkLt(value, limit, field, rule)

    fun <T : Comparable<T>> checkLte(value: T, limit: T, field: String, rule: String = "lte"): FieldViolation? =
        NumericValidators.checkLte(value, limit, field, rule)

    fun <T : Comparable<T>> checkConst(value: T, expected: T, field: String, rule: String = "const"): FieldViolation? =
        NumericValidators.checkConst(value, expected, field, rule)

    // ── In / NotIn ──

    fun <T> checkIn(value: T, allowed: List<T>, field: String, rule: String = "in"): FieldViolation? =
        NumericValidators.checkIn(value, allowed, field, rule)

    fun <T> checkNotIn(value: T, disallowed: List<T>, field: String, rule: String = "not_in"): FieldViolation? =
        NumericValidators.checkNotIn(value, disallowed, field, rule)

    fun <T : Comparable<T>> checkRange(
        value: T, lower: T, upper: T,
        lowerInclusive: Boolean, upperInclusive: Boolean,
        field: String, rulePrefix: String
    ): FieldViolation? =
        NumericValidators.checkRange(value, lower, upper, lowerInclusive, upperInclusive, field, rulePrefix)

    // ── Message rules ──

    fun checkRequired(hasField: Boolean, field: String): FieldViolation? =
        MessageValidators.checkRequired(hasField, field)

    // ── Repeated rules ──

    fun checkMinItems(count: Int, min: Long, field: String): FieldViolation? =
        CollectionValidators.checkMinItems(count, min, field)

    fun checkMaxItems(count: Int, max: Long, field: String): FieldViolation? =
        CollectionValidators.checkMaxItems(count, max, field)

    fun <T> checkUnique(list: List<T>, field: String): FieldViolation? =
        CollectionValidators.checkUnique(list, field)

    // ── Map rules ──

    fun checkMinPairs(count: Int, min: Long, field: String): FieldViolation? =
        CollectionValidators.checkMinPairs(count, min, field)

    fun checkMaxPairs(count: Int, max: Long, field: String): FieldViolation? =
        CollectionValidators.checkMaxPairs(count, max, field)

    // ── Enum rules ──

    fun checkEnumConst(value: Int, expected: Int, field: String): FieldViolation? =
        EnumValidators.checkConst(value, expected, field)

    fun checkEnumIn(value: Int, allowed: List<Int>, field: String): FieldViolation? =
        EnumValidators.checkIn(value, allowed, field)

    fun checkEnumNotIn(value: Int, disallowed: List<Int>, field: String): FieldViolation? =
        EnumValidators.checkNotIn(value, disallowed, field)

    fun checkEnumDefinedOnly(value: Int, definedValues: List<Int>, field: String): FieldViolation? =
        EnumValidators.checkDefinedOnly(value, definedValues, field)

    // ── Any rules ──

    fun checkAnyIn(value: String, allowed: List<String>, field: String): FieldViolation? {
        if (value !in allowed) {
            return FieldViolation(field, "any.in", "type URL must be in the allow list")
        }
        return null
    }

    fun checkAnyNotIn(value: String, disallowed: List<String>, field: String): FieldViolation? {
        if (value in disallowed) {
            return FieldViolation(field, "any.not_in", "type URL must not be in the block list")
        }
        return null
    }

    // ── FieldMask rules ──

    fun checkFieldMaskConst(paths: List<String>, expectedSortedPaths: List<String>, field: String): FieldViolation? =
        FieldMaskValidators.checkConst(paths, expectedSortedPaths, field)

    fun checkFieldMaskIn(paths: List<String>, allowed: List<String>, field: String): FieldViolation? =
        FieldMaskValidators.checkIn(paths, allowed, field)

    fun checkFieldMaskNotIn(paths: List<String>, denied: List<String>, field: String): FieldViolation? =
        FieldMaskValidators.checkNotIn(paths, denied, field)

    // ── Bool rules ──

    fun checkBoolConst(value: Boolean, expected: Boolean, field: String): FieldViolation? =
        BoolValidators.checkConst(value, expected, field)

    // ── Duration rules ──

    fun checkDurationRequired(hasField: Boolean, field: String): FieldViolation? =
        DurationValidators.checkRequired(hasField, field)

    fun checkDurationConst(seconds: Long, nanos: Int, constSeconds: Long, constNanos: Int, field: String): FieldViolation? =
        DurationValidators.checkConst(seconds, nanos, constSeconds, constNanos, field)

    fun checkDurationLt(seconds: Long, nanos: Int, ltSeconds: Long, ltNanos: Int, field: String): FieldViolation? =
        DurationValidators.checkLt(seconds, nanos, ltSeconds, ltNanos, field)

    fun checkDurationLte(seconds: Long, nanos: Int, lteSeconds: Long, lteNanos: Int, field: String): FieldViolation? =
        DurationValidators.checkLte(seconds, nanos, lteSeconds, lteNanos, field)

    fun checkDurationGt(seconds: Long, nanos: Int, gtSeconds: Long, gtNanos: Int, field: String): FieldViolation? =
        DurationValidators.checkGt(seconds, nanos, gtSeconds, gtNanos, field)

    fun checkDurationGte(seconds: Long, nanos: Int, gteSeconds: Long, gteNanos: Int, field: String): FieldViolation? =
        DurationValidators.checkGte(seconds, nanos, gteSeconds, gteNanos, field)

    fun checkDurationIn(seconds: Long, nanos: Int, allowed: List<Pair<Long, Int>>, field: String): FieldViolation? =
        DurationValidators.checkIn(seconds, nanos, allowed, field)

    fun checkDurationNotIn(seconds: Long, nanos: Int, disallowed: List<Pair<Long, Int>>, field: String): FieldViolation? =
        DurationValidators.checkNotIn(seconds, nanos, disallowed, field)

    fun checkDurationRange(
        seconds: Long, nanos: Int,
        lowerSeconds: Long, lowerNanos: Int,
        upperSeconds: Long, upperNanos: Int,
        lowerInclusive: Boolean, upperInclusive: Boolean,
        field: String
    ): FieldViolation? =
        DurationValidators.checkRange(seconds, nanos, lowerSeconds, lowerNanos, upperSeconds, upperNanos, lowerInclusive, upperInclusive, field)

    // ── Timestamp rules ──

    fun checkTimestampRequired(hasField: Boolean, field: String): FieldViolation? =
        TimestampValidators.checkRequired(hasField, field)

    fun checkTimestampConst(seconds: Long, nanos: Int, constSeconds: Long, constNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkConst(seconds, nanos, constSeconds, constNanos, field)

    fun checkTimestampLt(seconds: Long, nanos: Int, ltSeconds: Long, ltNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkLt(seconds, nanos, ltSeconds, ltNanos, field)

    fun checkTimestampLte(seconds: Long, nanos: Int, lteSeconds: Long, lteNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkLte(seconds, nanos, lteSeconds, lteNanos, field)

    fun checkTimestampGt(seconds: Long, nanos: Int, gtSeconds: Long, gtNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkGt(seconds, nanos, gtSeconds, gtNanos, field)

    fun checkTimestampGte(seconds: Long, nanos: Int, gteSeconds: Long, gteNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkGte(seconds, nanos, gteSeconds, gteNanos, field)

    fun checkTimestampLtNow(seconds: Long, nanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkLtNow(seconds, nanos, field)

    fun checkTimestampGtNow(seconds: Long, nanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkGtNow(seconds, nanos, field)

    fun checkTimestampWithin(seconds: Long, nanos: Int, withinSeconds: Long, withinNanos: Int, field: String): FieldViolation? =
        TimestampValidators.checkWithin(seconds, nanos, withinSeconds, withinNanos, field)

    fun checkTimestampIn(seconds: Long, nanos: Int, allowed: List<Pair<Long, Int>>, field: String): FieldViolation? =
        TimestampValidators.checkIn(seconds, nanos, allowed, field)

    fun checkTimestampNotIn(seconds: Long, nanos: Int, disallowed: List<Pair<Long, Int>>, field: String): FieldViolation? =
        TimestampValidators.checkNotIn(seconds, nanos, disallowed, field)

    fun checkTimestampRange(
        seconds: Long, nanos: Int,
        lowerSeconds: Long, lowerNanos: Int,
        upperSeconds: Long, upperNanos: Int,
        lowerInclusive: Boolean, upperInclusive: Boolean,
        field: String
    ): FieldViolation? =
        TimestampValidators.checkRange(seconds, nanos, lowerSeconds, lowerNanos, upperSeconds, upperNanos, lowerInclusive, upperInclusive, field)
}

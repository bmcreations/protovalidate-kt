package dev.bmcreations.protovalidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidatorsTest {

    // ── String Pattern ──

    @Test
    fun `string pattern - valid`() {
        assertNull(Validators.checkStringPattern("+15551234567", "^\\+[1-9]\\d{1,14}$", "phone"))
    }

    @Test
    fun `string pattern - invalid`() {
        assertNotNull(Validators.checkStringPattern("notaphone", "^\\+[1-9]\\d{1,14}$", "phone"))
    }

    // ── String MinLen / MaxLen ──

    @Test
    fun `string min len - valid`() {
        assertNull(Validators.checkStringMinLen("abc", 3, "value"))
    }

    @Test
    fun `string min len - too short`() {
        assertNotNull(Validators.checkStringMinLen("ab", 3, "value"))
    }

    @Test
    fun `string max len - valid`() {
        assertNull(Validators.checkStringMaxLen("abc", 3, "value"))
    }

    @Test
    fun `string max len - too long`() {
        assertNotNull(Validators.checkStringMaxLen("abcd", 3, "value"))
    }

    @Test
    fun `string len - counts unicode code points`() {
        // U+1F600 is one code point but 2 chars in Java
        val emoji = "\uD83D\uDE00"
        assertNull(Validators.checkStringMinLen(emoji, 1, "value"))
        assertNotNull(Validators.checkStringMinLen(emoji, 2, "value"))
    }

    // ── String In / NotIn ──

    @Test
    fun `string in - valid`() {
        assertNull(Validators.checkStringIn("a", listOf("a", "b", "c"), "value"))
    }

    @Test
    fun `string in - invalid`() {
        assertNotNull(Validators.checkStringIn("d", listOf("a", "b", "c"), "value"))
    }

    @Test
    fun `string not in - valid`() {
        assertNull(Validators.checkStringNotIn("d", listOf("a", "b", "c"), "value"))
    }

    @Test
    fun `string not in - invalid`() {
        assertNotNull(Validators.checkStringNotIn("a", listOf("a", "b", "c"), "value"))
    }

    // ── String Const ──

    @Test
    fun `string const - valid`() {
        assertNull(Validators.checkStringConst("hello", "hello", "value"))
    }

    @Test
    fun `string const - invalid`() {
        assertNotNull(Validators.checkStringConst("world", "hello", "value"))
    }

    // ── String Prefix / Suffix / Contains ──

    @Test
    fun `string prefix - valid`() {
        assertNull(Validators.checkStringPrefix("hello world", "hello", "value"))
    }

    @Test
    fun `string prefix - invalid`() {
        assertNotNull(Validators.checkStringPrefix("world", "hello", "value"))
    }

    @Test
    fun `string suffix - valid`() {
        assertNull(Validators.checkStringSuffix("hello world", "world", "value"))
    }

    @Test
    fun `string contains - valid`() {
        assertNull(Validators.checkStringContains("hello world", "lo wo", "value"))
    }

    @Test
    fun `string not contains - valid`() {
        assertNull(Validators.checkStringNotContains("hello", "xyz", "value"))
    }

    @Test
    fun `string not contains - invalid`() {
        assertNotNull(Validators.checkStringNotContains("hello", "ell", "value"))
    }

    // ── Bytes ──

    @Test
    fun `bytes len - valid`() {
        assertNull(Validators.checkBytesLen(ByteArray(32), 32, "value"))
    }

    @Test
    fun `bytes len - invalid`() {
        assertNotNull(Validators.checkBytesLen(ByteArray(16), 32, "value"))
    }

    @Test
    fun `bytes min len - valid`() {
        assertNull(Validators.checkBytesMinLen(ByteArray(32), 32, "value"))
    }

    @Test
    fun `bytes min len - too short`() {
        assertNotNull(Validators.checkBytesMinLen(ByteArray(16), 32, "value"))
    }

    @Test
    fun `bytes max len - valid`() {
        assertNull(Validators.checkBytesMaxLen(ByteArray(32), 32, "value"))
    }

    @Test
    fun `bytes max len - too long`() {
        assertNotNull(Validators.checkBytesMaxLen(ByteArray(64), 32, "value"))
    }

    // ── Comparable (numeric) ──

    @Test
    fun `gt - valid`() {
        assertNull(Validators.checkGt(10, 5, "value"))
    }

    @Test
    fun `gt - invalid equal`() {
        assertNotNull(Validators.checkGt(5, 5, "value"))
    }

    @Test
    fun `gte - valid equal`() {
        assertNull(Validators.checkGte(5, 5, "value"))
    }

    @Test
    fun `lt - valid`() {
        assertNull(Validators.checkLt(3, 5, "value"))
    }

    @Test
    fun `lt - invalid equal`() {
        assertNotNull(Validators.checkLt(5, 5, "value"))
    }

    @Test
    fun `lte - valid equal`() {
        assertNull(Validators.checkLte(5, 5, "value"))
    }

    @Test
    fun `const - valid`() {
        assertNull(Validators.checkConst(42, 42, "value"))
    }

    @Test
    fun `const - invalid`() {
        assertNotNull(Validators.checkConst(43, 42, "value"))
    }

    // ── In / NotIn (generic) ──

    @Test
    fun `in - valid`() {
        assertNull(Validators.checkIn(1, listOf(1, 2, 3), "value"))
    }

    @Test
    fun `in - invalid`() {
        assertNotNull(Validators.checkIn(4, listOf(1, 2, 3), "value"))
    }

    @Test
    fun `not in - valid`() {
        assertNull(Validators.checkNotIn(4, listOf(1, 2, 3), "value"))
    }

    @Test
    fun `not in - invalid`() {
        assertNotNull(Validators.checkNotIn(1, listOf(1, 2, 3), "value"))
    }

    // ── Required ──

    @Test
    fun `required - present`() {
        assertNull(Validators.checkRequired(true, "field"))
    }

    @Test
    fun `required - absent`() {
        assertNotNull(Validators.checkRequired(false, "field"))
    }

    // ── Repeated ──

    @Test
    fun `min items - valid`() {
        assertNull(Validators.checkMinItems(3, 2, "list"))
    }

    @Test
    fun `min items - too few`() {
        assertNotNull(Validators.checkMinItems(1, 2, "list"))
    }

    @Test
    fun `max items - valid`() {
        assertNull(Validators.checkMaxItems(3, 5, "list"))
    }

    @Test
    fun `max items - too many`() {
        assertNotNull(Validators.checkMaxItems(6, 5, "list"))
    }

    @Test
    fun `unique - valid`() {
        assertNull(Validators.checkUnique(listOf(1, 2, 3), "list"))
    }

    @Test
    fun `unique - invalid`() {
        assertNotNull(Validators.checkUnique(listOf(1, 2, 2), "list"))
    }

    // ── Map ──

    @Test
    fun `min pairs - valid`() {
        assertNull(Validators.checkMinPairs(3, 2, "map"))
    }

    @Test
    fun `min pairs - too few`() {
        assertNotNull(Validators.checkMinPairs(1, 2, "map"))
    }

    // ── Enum ──

    @Test
    fun `enum in - valid`() {
        assertNull(Validators.checkEnumIn(1, listOf(0, 1, 2), "status"))
    }

    @Test
    fun `enum in - invalid`() {
        assertNotNull(Validators.checkEnumIn(5, listOf(0, 1, 2), "status"))
    }

    @Test
    fun `enum defined only - valid`() {
        assertNull(Validators.checkEnumDefinedOnly(1, listOf(0, 1, 2), "status"))
    }

    @Test
    fun `enum defined only - invalid`() {
        assertNotNull(Validators.checkEnumDefinedOnly(99, listOf(0, 1, 2), "status"))
    }

    // ── Bool ──

    @Test
    fun `bool const - valid`() {
        assertNull(Validators.checkBoolConst(true, true, "flag"))
    }

    @Test
    fun `bool const - invalid`() {
        assertNotNull(Validators.checkBoolConst(false, true, "flag"))
    }

    // ── Bytes Const ──

    @Test
    fun `bytes const - valid matching`() {
        val bytes = byteArrayOf(1, 2, 3)
        assertNull(Validators.checkBytesConst(bytes, byteArrayOf(1, 2, 3), "value"))
    }

    @Test
    fun `bytes const - invalid not matching`() {
        assertNotNull(Validators.checkBytesConst(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), "value"))
    }

    // ── Bytes Pattern ──

    @Test
    fun `bytes pattern - valid regex match on UTF-8 string`() {
        assertNull(Validators.checkBytesPattern("hello".toByteArray(), "^hell", "value"))
    }

    @Test
    fun `bytes pattern - invalid`() {
        assertNotNull(Validators.checkBytesPattern("goodbye".toByteArray(), "^hell", "value"))
    }

    // ── Bytes Prefix ──

    @Test
    fun `bytes prefix - valid starts with prefix`() {
        assertNull(Validators.checkBytesPrefix(byteArrayOf(1, 2, 3, 4), byteArrayOf(1, 2), "value"))
    }

    @Test
    fun `bytes prefix - invalid`() {
        assertNotNull(Validators.checkBytesPrefix(byteArrayOf(1, 2, 3, 4), byteArrayOf(9, 9), "value"))
    }

    // ── Bytes Suffix ──

    @Test
    fun `bytes suffix - valid ends with suffix`() {
        assertNull(Validators.checkBytesSuffix(byteArrayOf(1, 2, 3, 4), byteArrayOf(3, 4), "value"))
    }

    @Test
    fun `bytes suffix - invalid`() {
        assertNotNull(Validators.checkBytesSuffix(byteArrayOf(1, 2, 3, 4), byteArrayOf(9, 9), "value"))
    }

    // ── Bytes Contains ──

    @Test
    fun `bytes contains - valid contains subsequence`() {
        assertNull(Validators.checkBytesContains(byteArrayOf(1, 2, 3, 4, 5), byteArrayOf(2, 3, 4), "value"))
    }

    @Test
    fun `bytes contains - invalid`() {
        assertNotNull(Validators.checkBytesContains(byteArrayOf(1, 2, 3, 4, 5), byteArrayOf(7, 8), "value"))
    }

    // ── Bytes In / NotIn ──

    @Test
    fun `bytes in - valid in list`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        assertNull(Validators.checkBytesIn(byteArrayOf(1, 2), listOf(a, b), "value"))
    }

    @Test
    fun `bytes in - invalid not in list`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        assertNotNull(Validators.checkBytesIn(byteArrayOf(9, 9), listOf(a, b), "value"))
    }

    @Test
    fun `bytes not in - valid not in list`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        assertNull(Validators.checkBytesNotIn(byteArrayOf(9, 9), listOf(a, b), "value"))
    }

    @Test
    fun `bytes not in - invalid in list`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        assertNotNull(Validators.checkBytesNotIn(byteArrayOf(1, 2), listOf(a, b), "value"))
    }

    // ── Bytes IP ──

    @Test
    fun `bytes ip - valid 4 bytes`() {
        assertNull(Validators.checkBytesIp(ByteArray(4), "value"))
    }

    @Test
    fun `bytes ip - valid 16 bytes`() {
        assertNull(Validators.checkBytesIp(ByteArray(16), "value"))
    }

    @Test
    fun `bytes ip - invalid other lengths`() {
        assertNotNull(Validators.checkBytesIp(ByteArray(6), "value"))
    }

    // ── Bytes IPv4 ──

    @Test
    fun `bytes ipv4 - valid 4 bytes`() {
        assertNull(Validators.checkBytesIpv4(ByteArray(4), "value"))
    }

    @Test
    fun `bytes ipv4 - invalid other lengths`() {
        assertNotNull(Validators.checkBytesIpv4(ByteArray(16), "value"))
    }

    // ── Bytes IPv6 ──

    @Test
    fun `bytes ipv6 - valid 16 bytes`() {
        assertNull(Validators.checkBytesIpv6(ByteArray(16), "value"))
    }

    @Test
    fun `bytes ipv6 - invalid other lengths`() {
        assertNotNull(Validators.checkBytesIpv6(ByteArray(4), "value"))
    }

    // ── String UriRef ──

    @Test
    fun `string uri ref - valid absolute URI`() {
        assertNull(Validators.checkStringUriRef("https://example.com/path", "value"))
    }

    @Test
    fun `string uri ref - valid relative URI path`() {
        assertNull(Validators.checkStringUriRef("/relative/path", "value"))
    }

    @Test
    fun `string uri ref - empty string is valid path-empty`() {
        assertNull(Validators.checkStringUriRef("", "value"))
    }

    // ── String Address ──

    @Test
    fun `string address - valid hostname`() {
        assertNull(Validators.checkStringAddress("example.com", "value"))
    }

    @Test
    fun `string address - valid IPv4`() {
        assertNull(Validators.checkStringAddress("192.168.1.1", "value"))
    }

    @Test
    fun `string address - valid IPv6`() {
        assertNull(Validators.checkStringAddress("::1", "value"))
    }

    @Test
    fun `string address - invalid`() {
        assertNotNull(Validators.checkStringAddress("not a valid address!@#", "value"))
    }

    // ── String HTTP Header Name ──

    @Test
    fun `string http header name - valid strict`() {
        assertNull(Validators.checkStringHttpHeaderName("Content-Type", strict = true, "value"))
    }

    @Test
    fun `string http header name - invalid strict has space`() {
        assertNotNull(Validators.checkStringHttpHeaderName("Content Type", strict = true, "value"))
    }

    @Test
    fun `string http header name - valid non-strict`() {
        assertNull(Validators.checkStringHttpHeaderName("X-Custom Header", strict = false, "value"))
    }

    @Test
    fun `string http header name - invalid non-strict has CR LF`() {
        assertNotNull(Validators.checkStringHttpHeaderName("Bad\r\nHeader", strict = false, "value"))
    }

    // ── String HTTP Header Value ──

    @Test
    fun `string http header value - valid strict`() {
        assertNull(Validators.checkStringHttpHeaderValue("application/json", strict = true, "value"))
    }

    @Test
    fun `string http header value - invalid strict`() {
        // Control character (BEL, 0x07) is not allowed in strict mode
        assertNotNull(Validators.checkStringHttpHeaderValue("bad\u0007value", strict = true, "value"))
    }

    @Test
    fun `string http header value - valid non-strict`() {
        assertNull(Validators.checkStringHttpHeaderValue("some value with spaces", strict = false, "value"))
    }

    @Test
    fun `string http header value - invalid non-strict has CR LF`() {
        assertNotNull(Validators.checkStringHttpHeaderValue("injected\r\nvalue", strict = false, "value"))
    }

    // ── Duration Const ──

    @Test
    fun `duration const - valid match`() {
        assertNull(Validators.checkDurationConst(10L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration const - invalid mismatch`() {
        assertNotNull(Validators.checkDurationConst(10L, 0, 20L, 0, "value"))
    }

    // ── Duration Lt ──

    @Test
    fun `duration lt - valid lesser`() {
        assertNull(Validators.checkDurationLt(5L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration lt - invalid equal`() {
        assertNotNull(Validators.checkDurationLt(10L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration lt - invalid greater`() {
        assertNotNull(Validators.checkDurationLt(15L, 0, 10L, 0, "value"))
    }

    // ── Duration Lte ──

    @Test
    fun `duration lte - valid lesser`() {
        assertNull(Validators.checkDurationLte(5L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration lte - valid equal`() {
        assertNull(Validators.checkDurationLte(10L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration lte - invalid greater`() {
        assertNotNull(Validators.checkDurationLte(15L, 0, 10L, 0, "value"))
    }

    // ── Duration Gt ──

    @Test
    fun `duration gt - valid greater`() {
        assertNull(Validators.checkDurationGt(15L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration gt - invalid equal`() {
        assertNotNull(Validators.checkDurationGt(10L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration gt - invalid lesser`() {
        assertNotNull(Validators.checkDurationGt(5L, 0, 10L, 0, "value"))
    }

    // ── Duration Gte ──

    @Test
    fun `duration gte - valid greater`() {
        assertNull(Validators.checkDurationGte(15L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration gte - valid equal`() {
        assertNull(Validators.checkDurationGte(10L, 0, 10L, 0, "value"))
    }

    @Test
    fun `duration gte - invalid lesser`() {
        assertNotNull(Validators.checkDurationGte(5L, 0, 10L, 0, "value"))
    }

    // ── Duration In / NotIn ──

    @Test
    fun `duration in - valid in list`() {
        assertNull(Validators.checkDurationIn(10L, 0, listOf(Pair(5L, 0), Pair(10L, 0)), "value"))
    }

    @Test
    fun `duration in - invalid not in list`() {
        assertNotNull(Validators.checkDurationIn(20L, 0, listOf(Pair(5L, 0), Pair(10L, 0)), "value"))
    }

    @Test
    fun `duration not in - valid not in list`() {
        assertNull(Validators.checkDurationNotIn(20L, 0, listOf(Pair(5L, 0), Pair(10L, 0)), "value"))
    }

    @Test
    fun `duration not in - invalid in list`() {
        assertNotNull(Validators.checkDurationNotIn(10L, 0, listOf(Pair(5L, 0), Pair(10L, 0)), "value"))
    }

    // ── Duration nanos comparison ──

    @Test
    fun `duration - 1s 500000000ns is greater than 1s 0ns`() {
        // 1s + 500_000_000ns > 1s + 0ns
        assertNull(Validators.checkDurationGt(1L, 500_000_000, 1L, 0, "value"))
    }

    @Test
    fun `duration - 1s 0ns is not greater than 1s 500000000ns`() {
        assertNotNull(Validators.checkDurationGt(1L, 0, 1L, 500_000_000, "value"))
    }

    // ── Timestamp Const ──

    @Test
    fun `timestamp const - valid match`() {
        assertNull(Validators.checkTimestampConst(1000L, 0, 1000L, 0, "value"))
    }

    @Test
    fun `timestamp const - invalid`() {
        assertNotNull(Validators.checkTimestampConst(1000L, 0, 2000L, 0, "value"))
    }

    // ── Timestamp Lt ──

    @Test
    fun `timestamp lt - valid`() {
        assertNull(Validators.checkTimestampLt(100L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp lt - invalid equal`() {
        assertNotNull(Validators.checkTimestampLt(200L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp lt - invalid greater`() {
        assertNotNull(Validators.checkTimestampLt(300L, 0, 200L, 0, "value"))
    }

    // ── Timestamp Lte ──

    @Test
    fun `timestamp lte - valid lesser`() {
        assertNull(Validators.checkTimestampLte(100L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp lte - valid equal`() {
        assertNull(Validators.checkTimestampLte(200L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp lte - invalid greater`() {
        assertNotNull(Validators.checkTimestampLte(300L, 0, 200L, 0, "value"))
    }

    // ── Timestamp Gt ──

    @Test
    fun `timestamp gt - valid`() {
        assertNull(Validators.checkTimestampGt(300L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp gt - invalid equal`() {
        assertNotNull(Validators.checkTimestampGt(200L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp gt - invalid lesser`() {
        assertNotNull(Validators.checkTimestampGt(100L, 0, 200L, 0, "value"))
    }

    // ── Timestamp Gte ──

    @Test
    fun `timestamp gte - valid greater`() {
        assertNull(Validators.checkTimestampGte(300L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp gte - valid equal`() {
        assertNull(Validators.checkTimestampGte(200L, 0, 200L, 0, "value"))
    }

    @Test
    fun `timestamp gte - invalid lesser`() {
        assertNotNull(Validators.checkTimestampGte(100L, 0, 200L, 0, "value"))
    }

    // ── Timestamp LtNow ──

    @Test
    fun `timestamp lt now - valid past timestamp`() {
        // Unix epoch (seconds=0) is clearly in the past
        assertNull(Validators.checkTimestampLtNow(0L, 0, "value"))
    }

    @Test
    fun `timestamp lt now - invalid future timestamp`() {
        val futureSeconds = System.currentTimeMillis() / 1000L + 100_000L
        assertNotNull(Validators.checkTimestampLtNow(futureSeconds, 0, "value"))
    }

    // ── Timestamp GtNow ──

    @Test
    fun `timestamp gt now - valid future timestamp`() {
        val futureSeconds = System.currentTimeMillis() / 1000L + 100_000L
        assertNull(Validators.checkTimestampGtNow(futureSeconds, 0, "value"))
    }

    @Test
    fun `timestamp gt now - invalid past timestamp`() {
        // Unix epoch (seconds=0) is clearly in the past
        assertNotNull(Validators.checkTimestampGtNow(0L, 0, "value"))
    }

    // ── Timestamp Within ──

    @Test
    fun `timestamp within - valid within range`() {
        // A timestamp 5 seconds in the past should be within a 60-second window
        val recentPast = System.currentTimeMillis() / 1000L - 5L
        assertNull(Validators.checkTimestampWithin(recentPast, 0, 60L, 0, "value"))
    }

    @Test
    fun `timestamp within - invalid outside range`() {
        // Unix epoch is far outside a 60-second window from now
        assertNotNull(Validators.checkTimestampWithin(0L, 0, 60L, 0, "value"))
    }

    // ── Timestamp In / NotIn ──

    @Test
    fun `timestamp in - valid`() {
        assertNull(Validators.checkTimestampIn(1000L, 0, listOf(Pair(500L, 0), Pair(1000L, 0)), "value"))
    }

    @Test
    fun `timestamp in - invalid`() {
        assertNotNull(Validators.checkTimestampIn(9999L, 0, listOf(Pair(500L, 0), Pair(1000L, 0)), "value"))
    }

    @Test
    fun `timestamp not in - valid`() {
        assertNull(Validators.checkTimestampNotIn(9999L, 0, listOf(Pair(500L, 0), Pair(1000L, 0)), "value"))
    }

    @Test
    fun `timestamp not in - invalid`() {
        assertNotNull(Validators.checkTimestampNotIn(1000L, 0, listOf(Pair(500L, 0), Pair(1000L, 0)), "value"))
    }

    // ── ValidationResult ──

    @Test
    fun `valid result is valid`() {
        val result = ValidationResult.Valid
        assertEquals(true, result.isValid)
        assertEquals(emptyList<FieldViolation>(), result.violationsOrEmpty())
    }

    @Test
    fun `invalid result has violations`() {
        val violation = FieldViolation("field", "rule", "message")
        val result = ValidationResult.Invalid(listOf(violation))
        assertEquals(false, result.isValid)
        assertEquals(listOf(violation), result.violationsOrEmpty())
    }
}

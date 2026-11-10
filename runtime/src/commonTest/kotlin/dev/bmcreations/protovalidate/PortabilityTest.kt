package dev.bmcreations.protovalidate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the code that replaced JVM-only APIs, on every target. */
class PortabilityTest {

    // Protobuf form: truncated seconds, nanos with the same sign.
    // Wire form: floored seconds, nanos in [0, 1e9).
    private val minusOneAndAHalfProto = -1L to -500_000_000
    private val minusOneAndAHalfWire = -2L to 500_000_000
    private val minusHalfProto = 0L to -500_000_000
    private val minusHalfWire = -1L to 500_000_000

    @Test
    fun `duration - both normalisations compare equal`() {
        val (ps, pn) = minusOneAndAHalfProto
        val (ws, wn) = minusOneAndAHalfWire
        assertNull(Validators.checkDurationConst(ps, pn, ws, wn, "d"))
        assertNull(Validators.checkDurationConst(ws, wn, ps, pn, "d"))
        assertNull(Validators.checkDurationIn(ws, wn, listOf(ps to pn), "d"))
        assertNotNull(Validators.checkDurationNotIn(ws, wn, listOf(ps to pn), "d"))
        assertNull(Validators.checkDurationGte(ws, wn, ps, pn, "d"))
        assertNull(Validators.checkDurationLte(ws, wn, ps, pn, "d"))
        assertNotNull(Validators.checkDurationLt(ws, wn, ps, pn, "d"))
        assertNotNull(Validators.checkDurationGt(ws, wn, ps, pn, "d"))
    }

    @Test
    fun `duration - ordering across normalisations`() {
        // -1.5s (wire) < -1.25s (proto) < -0.5s (wire) < 0
        assertNull(Validators.checkDurationLt(-2L, 500_000_000, -1L, -250_000_000, "d"))
        assertNull(Validators.checkDurationLt(-1L, -250_000_000, minusHalfWire.first, minusHalfWire.second, "d"))
        assertNull(Validators.checkDurationLt(minusHalfProto.first, minusHalfProto.second, 0L, 0, "d"))
        assertNull(Validators.checkDurationGt(0L, 1, minusHalfWire.first, minusHalfWire.second, "d"))
        assertNotNull(Validators.checkDurationGt(minusHalfProto.first, minusHalfProto.second, minusHalfWire.first, minusHalfWire.second, "d"))
    }

    @Test
    fun `duration - both normalisations format identically`() {
        fun msg(s: Long, n: Int) = Validators.checkDurationConst(100L, 0, s, n, "d")!!.message
        assertEquals("must equal -1.5s", msg(minusOneAndAHalfProto.first, minusOneAndAHalfProto.second))
        assertEquals("must equal -1.5s", msg(minusOneAndAHalfWire.first, minusOneAndAHalfWire.second))
        assertEquals("must equal -0.5s", msg(minusHalfProto.first, minusHalfProto.second))
        assertEquals("must equal -0.5s", msg(minusHalfWire.first, minusHalfWire.second))
        assertEquals("must equal -0.000000001s", msg(-1L, 999_999_999))
        assertEquals("must equal -0.000000001s", msg(0L, -1))
        // Non-negative values format as before.
        assertEquals("must equal 0s", msg(0L, 0))
        assertEquals("must equal -3s", msg(-3L, 0))
        assertEquals("must equal 1.5s", msg(1L, 500_000_000))
        assertEquals("must equal 0.000000001s", msg(0L, 1))
        assertEquals("must equal 1.01s", msg(1L, 10_000_000))
        assertEquals("must equal 315576000000.999999999s", msg(315_576_000_000L, 999_999_999))
    }

    @Test
    fun `duration - range message uses canonical form`() {
        val v = Validators.checkDurationRange(5L, 0, -2L, 500_000_000, 0L, -250_000_000, true, false, "d")!!
        assertEquals("must be gte -1.5s and lt -0.25s", v.message)
    }

    @Test
    fun `timestamp - both normalisations compare and format identically`() {
        // 1969-12-31T23:59:58.500Z
        val (ps, pn) = minusOneAndAHalfProto
        val (ws, wn) = minusOneAndAHalfWire
        assertNull(Validators.checkTimestampConst(ps, pn, ws, wn, "t"))
        assertNull(Validators.checkTimestampConst(ws, wn, ps, pn, "t"))
        assertNotNull(Validators.checkTimestampLt(ws, wn, ps, pn, "t"))
        assertNull(Validators.checkTimestampLte(ws, wn, ps, pn, "t"))
        assertEquals(
            "must equal 1969-12-31T23:59:58.500Z",
            Validators.checkTimestampConst(0L, 0, ps, pn, "t")!!.message,
        )
        assertEquals(
            "must equal 1969-12-31T23:59:58.500Z",
            Validators.checkTimestampConst(0L, 0, ws, wn, "t")!!.message,
        )
    }

    @Test
    fun `timestamp - ISO_INSTANT style output`() {
        fun msg(s: Long, n: Int) = Validators.checkTimestampConst(s + 1, n, s, n, "t")!!.message.removePrefix("must equal ")
        assertEquals("1970-01-01T00:00:00Z", msg(0L, 0))
        assertEquals("1970-01-01T00:00:00.000000001Z", msg(0L, 1))
        assertEquals("1970-01-01T00:00:00.001Z", msg(0L, 1_000_000))
        assertEquals("1970-01-01T00:00:00.000100Z", msg(0L, 100_000))
        assertEquals("0001-01-01T00:00:00Z", msg(-62_135_596_800L, 0))
        assertEquals("9999-12-31T23:59:59.999999999Z", msg(253_402_300_799L, 999_999_999))
    }

    @Test
    fun `timestamp - within accepts either normalisation`() {
        val now = kotlin.time.Clock.System.now().epochSeconds
        assertNull(Validators.checkTimestampWithin(now - 2, 500_000_000, 10L, 0, "t"))
        assertNull(Validators.checkTimestampWithin(now - 1, -500_000_000, 10L, 0, "t"))
    }

    // ── IP: IPv4 tail directly after "::" used to throw StringIndexOutOfBoundsException ──

    @Test
    fun `ipv6 - ipv4 tail after double colon`() {
        assertTrue(StringValidators.isStrictIpv6("::1.2.3.4"))
        assertTrue(StringValidators.isStrictIpv6("1::1.2.3.4"))
        assertTrue(StringValidators.isStrictIpv6("1:2:3:4:5::1.2.3.4"))
        assertFalse(StringValidators.isStrictIpv6("1:2:3:4:5:6::1.2.3.4"))
        assertFalse(StringValidators.isStrictIpv6(":::1.2.3.4"))
        assertTrue(StringValidators.isValidIpPrefix("::1.2.3.0/120", 6, strict = true))
        assertFalse(StringValidators.isValidIpPrefix("::1.2.3.4/120", 6, strict = true))
        assertNull(StringValidators.checkIpv6Prefix("::ffff:10.0.0.0/104", "ip"))
        assertNotNull(StringValidators.checkIpv6Prefix("::ffff:10.0.0.1/104", "ip"))
    }

    @Test
    fun `ip prefix - network address check`() {
        assertNull(StringValidators.checkIpv4Prefix("10.0.0.0/8", "ip"))
        assertNotNull(StringValidators.checkIpv4Prefix("10.0.0.1/8", "ip"))
        assertNull(StringValidators.checkIpv4Prefix("0.0.0.0/0", "ip"))
        assertNull(StringValidators.checkIpv4Prefix("255.255.255.255/32", "ip"))
        assertNull(StringValidators.checkIpv6Prefix("2001:db8::/32", "ip"))
        assertNotNull(StringValidators.checkIpv6Prefix("2001:db8::1/32", "ip"))
        assertNull(StringValidators.checkIpv6Prefix("::/0", "ip"))
        assertNull(StringValidators.checkIpv6Prefix("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128", "ip"))
        assertNotNull(StringValidators.checkIpv6Prefix("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/127", "ip"))
        assertNotNull(StringValidators.checkIpv4Prefix("localhost/8", "ip"))
        assertNotNull(StringValidators.checkIpv6Prefix("::1%eth0/128", "ip"))
    }

    // ── Bytes ──

    @Test
    fun `bytes const - hex message`() {
        val v = Validators.checkBytesConst(byteArrayOf(), byteArrayOf(0, 0x0f, 0x7f, -128, -1), "b")!!
        assertEquals("must be 000f7f80ff", v.message)
    }

    @Test
    fun `bytes pattern - rejects invalid UTF-8`() {
        val e = assertFailsWith<RuntimeException> {
            Validators.checkBytesPattern(byteArrayOf(0xC3.toByte(), 0x28), "^a", "b")
        }
        assertEquals("must be valid UTF-8 to apply regexp", e.message)
        // Encoded surrogate (CESU-8) is not valid UTF-8 either.
        assertFailsWith<RuntimeException> {
            Validators.checkBytesPattern(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), "^a", "b")
        }
        assertNull(Validators.checkBytesPattern("héllo".encodeToByteArray(), "^h.llo$", "b"))
    }

    // ── Strings ──

    @Test
    fun `string len - counts code points`() {
        val emoji = "😀" // one code point, two chars
        assertNull(Validators.checkStringLen(emoji, 1L, "s"))
        assertNull(Validators.checkStringLen("a${emoji}b", 3L, "s"))
        assertNull(Validators.checkStringLen("\uD83D", 1L, "s")) // unpaired surrogate counts as one
        assertNull(Validators.checkStringLenBytes("é", 2L, "s"))
    }

    @Test
    fun `uri - percent-encoded UTF-8 in host`() {
        assertTrue(StringValidators.isValidUri("https://%C3%A9xample.com/"))
        assertFalse(StringValidators.isValidUri("https://%C3%28xample.com/"))
        assertFalse(StringValidators.isValidUri("https://%ED%A0%80xample.com/"))
    }

    // ── Numeric message formatting ──

    @Test
    fun `numeric - whole and integer limits format the same on every target`() {
        assertEquals("must be greater than 5", Validators.checkGt(1, 5, "n")!!.message)
        assertEquals("must be greater than 5", Validators.checkGt(1.0, 5.0, "n")!!.message)
        assertEquals("must be greater than 5", Validators.checkGt(1.0f, 5.0f, "n")!!.message)
        assertEquals("must be greater than 1.5", Validators.checkGt(1.0, 1.5, "n")!!.message)
        assertEquals("must be greater than 1.5", Validators.checkGt(1.0f, 1.5f, "n")!!.message)
    }
}

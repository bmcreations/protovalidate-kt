package dev.bmcreations.protovalidate

import java.net.InetAddress
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Checks the common replacements for java.net and java.time against the JVM
 * implementations they replaced.
 */
class JvmOracleTest {

    // ── IP addresses: common parser vs InetAddress ──

    /** The InetAddress-based isNetworkAddress from runtime 0.1.3, kept as the oracle. */
    private fun oracleIsNetworkAddress(ip: String, prefixLen: Int, maxLen: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            val rawBytes = addr.address
            val bytes = if (maxLen == 128 && rawBytes.size == 4) {
                ByteArray(16).also { full ->
                    full[10] = 0xFF.toByte()
                    full[11] = 0xFF.toByte()
                    rawBytes.copyInto(full, 12)
                }
            } else {
                rawBytes
            }
            for (bit in prefixLen until maxLen) {
                val byteIdx = bit / 8
                val bitIdx = 7 - (bit % 8)
                if (byteIdx < bytes.size && (bytes[byteIdx].toInt() shr bitIdx) and 1 != 0) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** InetAddress bytes, widened to 16 for IPv6 the way the old code did. */
    private fun oracleBytes(ip: String, v6: Boolean): ByteArray {
        val raw = InetAddress.getByName(ip).address
        return if (v6 && raw.size == 4) {
            ByteArray(16).also { it[10] = -1; it[11] = -1; raw.copyInto(it, 12) }
        } else {
            raw
        }
    }

    /** The private common parser in StringValidators.kt, reached by reflection to keep it off the API. */
    private fun commonBytes(ip: String): ByteArray? {
        val m = Class.forName("dev.bmcreations.protovalidate.StringValidatorsKt")
            .getDeclaredMethod("ipAddressBytes", String::class.java)
        m.isAccessible = true
        return m.invoke(null, ip) as ByteArray?
    }

    private val ipInputs = listOf(
        // IPv4
        "0.0.0.0", "1.2.3.4", "10.0.0.0", "127.0.0.1", "192.168.1.0", "192.168.1.1",
        "255.255.255.255", "255.255.255.254", "128.0.0.0", "100.64.0.0",
        // IPv6
        "::", "::1", "1::", "::ffff:1.2.3.4", "::ffff:0:0", "::ffff:102:304", "::1.2.3.4",
        "1::1.2.3.4", "64:ff9b::192.0.2.33", "2001:db8::", "2001:db8::1", "2001:DB8:0:0:0:0:0:0",
        "fe80::", "fe80::1:2", "ff00::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8",
        "1:0:0:0:0:0:0:0", "0:0:0:0:0:0:0:0", "8000::", "abcd:ef01:2345:6789:abcd:ef01:2345:6789",
        "1:2:3:4:5:6:1.2.3.4", "0000:0000:0000:0000:0000:ffff:0102:0304", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        // Rejected before any parsing: zone ids, leading zeros, hostnames, junk
        "::1%eth0", "fe80::1%25en0", "01.2.3.4", "1.2.3.04", "1.2.3", "1.2.3.4.5", "256.1.1.1",
        "0x1.2.3.4", "localhost", "example.com", "", " 1.2.3.4", "1.2.3.4 ", "[::1]", ":::", "1:::2",
        "1::2::3", "12345::", "::ffff:1.2.3", "::ffff:01.2.3.4", "1:2:3:4:5:6:7:8:9", "g::", "::/64",
    )

    @Test
    fun `common IP parser matches InetAddress for every validated address`() {
        var compared = 0
        for (ip in ipInputs) {
            val v4 = StringValidators.isStrictIpv4(ip)
            val v6 = !v4 && StringValidators.isStrictIpv6(ip)
            if (!v4 && !v6) {
                // isNetworkAddress is only reached behind this gate, so the old code never
                // asked InetAddress about these (and would have resolved hostnames via DNS).
                assertNull(commonBytes(ip), "parser should reject unvalidated $ip")
                assertFalse(StringValidators.isValidIpPrefix("$ip/0", 0, strict = true), ip)
                continue
            }
            assertContentEquals(oracleBytes(ip, v6), commonBytes(ip), ip)
            val maxLen = if (v4) 32 else 128
            for (len in 0..maxLen) {
                val expected = oracleIsNetworkAddress(ip, len, maxLen)
                assertEquals(expected, StringValidators.isValidIpPrefix("$ip/$len", 0, strict = true), "$ip/$len")
                val versioned = if (v4) StringValidators.checkIpv4Prefix("$ip/$len", "f") else StringValidators.checkIpv6Prefix("$ip/$len", "f")
                assertEquals(expected, versioned == null, "$ip/$len")
            }
            compared++
        }
        assertEquals(35, compared) // 10 IPv4 + 25 IPv6 inputs pass the gate
    }

    @Test
    fun `signed octets pass the gate but are not network addresses`() {
        // isStrictIpv4 accepts a leading sign through toIntOrNull. InetAddress does not parse
        // these as literals (it tries them as hostnames and fails), so the old prefix check
        // returned false for every prefix length. The common parser must agree.
        for (ip in listOf("+10.0.0.0", "-0.0.0.0", "+1.2.3.0", "1.+2.3.4", "::ffff:+1.2.3.4", "::+1.2.3.4")) {
            val v4 = StringValidators.isStrictIpv4(ip)
            val maxLen = if (v4) 32 else 128
            assertNull(commonBytes(ip), ip)
            for (len in listOf(0, 8, maxLen)) {
                assertFalse(oracleIsNetworkAddress(ip, len, maxLen), "$ip/$len oracle")
                assertFalse(StringValidators.isValidIpPrefix("$ip/$len", 0, strict = true), "$ip/$len")
                val versioned = if (v4) StringValidators.checkIpv4Prefix("$ip/$len", "f") else StringValidators.checkIpv6Prefix("$ip/$len", "f")
                assertNotNull(versioned, "$ip/$len")
            }
        }
    }

    @Test
    fun `common IP parser matches InetAddress for random addresses`() {
        val rnd = Random(42)
        repeat(2_000) {
            val bytes = ByteArray(16).also { rnd.nextBytes(it) }
            // Zero runs of random length so "::" compression shows up often.
            val start = rnd.nextInt(8)
            val end = (start + rnd.nextInt(9)).coerceAtMost(8)
            for (i in start * 2 until end * 2) bytes[i] = 0
            val text = InetAddress.getByAddress(bytes).hostAddress // full form, no "::"
            val compressed = compressIpv6(text)
            for (ip in listOf(text, compressed)) {
                if (!StringValidators.isStrictIpv6(ip)) continue
                assertContentEquals(oracleBytes(ip, v6 = true), commonBytes(ip), ip)
            }
        }
    }

    private fun compressIpv6(full: String): String {
        if (!full.contains(':')) return full
        val groups = full.split(":").map { it.trimStart('0').ifEmpty { "0" } }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] == "0") {
                var j = i
                while (j < groups.size && groups[j] == "0") j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) return groups.joinToString(":")
        val left = groups.subList(0, bestStart).joinToString(":")
        val right = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return "$left::$right"
    }

    // ── Timestamps: kotlin.time.Instant vs DateTimeFormatter.ISO_INSTANT ──

    private fun commonFormat(seconds: Long, nanos: Int): String {
        val v = Validators.checkTimestampConst(seconds + 1, nanos, seconds, nanos, "f")!!
        return v.message.removePrefix("must equal ")
    }

    private fun oracleFormat(seconds: Long, nanos: Int): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(seconds, nanos.toLong()))

    @Test
    fun `timestamp formatting matches ISO_INSTANT`() {
        val minSeconds = -62_135_596_800L // 0001-01-01T00:00:00Z
        val maxSeconds = 253_402_300_799L // 9999-12-31T23:59:59Z
        val seconds = listOf(minSeconds, minSeconds + 1, -86_401L, -1L, 0L, 1L, 951_782_400L, 1_700_000_000L, maxSeconds - 1, maxSeconds)
        val nanos = listOf(0, 1, 10, 100, 1_000, 10_000, 1_000_000, 100_000_000, 123_456_789, 500_000_000, 999_999_999, -1, -500_000_000)
        for (s in seconds) for (n in nanos) {
            if (s == maxSeconds && n > 0) continue
            if (s == minSeconds && n < 0) continue
            assertEquals(oracleFormat(s, n), commonFormat(s, n), "($s, $n)")
        }
        val rnd = Random(7)
        repeat(5_000) {
            val s = rnd.nextLong(minSeconds, maxSeconds)
            val n = when (rnd.nextInt(4)) {
                0 -> 0
                1 -> rnd.nextInt(1000) * 1_000_000
                2 -> rnd.nextInt(1_000_000) * 1_000
                else -> rnd.nextInt(1_000_000_000)
            }
            assertEquals(oracleFormat(s, n), commonFormat(s, n), "($s, $n)")
        }
    }
}

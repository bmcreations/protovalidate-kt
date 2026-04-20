package dev.bmcreations.protovalidate

import java.net.InetAddress

// ── RFC 4291 IPv6 parser (no InetAddress, supports embedded IPv4) ──

/**
 * Validates an IPv6 address string per RFC 4291.
 *
 * Accepts:
 * - Standard groups: 1–8 groups of 1–4 hex digits separated by `:`
 * - `::` shorthand for one or more consecutive all-zero groups (at most one `::`)
 * - Embedded IPv4 in the last two groups (e.g. `::ffff:192.168.1.1`)
 *
 * Does NOT accept zone IDs (`%`), brackets, or prefix notation — those must be
 * stripped by the caller.
 *
 * Returns `true` if the string is a valid IPv6 address.
 */
private fun parseIpv6Strict(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.first().isWhitespace() || value.last().isWhitespace()) return false
    if (value.contains('%') || value.contains('/') ||
        value.startsWith('[') || value.endsWith(']')) return false

    // Split on "::" — there must be at most one occurrence.
    val doubleColonCount = value.split("::").size - 1
    if (doubleColonCount > 1) return false

    val hasDC = doubleColonCount == 1

    // Check for embedded IPv4: the string ends with an IPv4 address after the last ':'
    // but the character before that ':' is not also a ':' — i.e. `::ffff:1.2.3.4`.
    // We detect this by seeing if the part after the last ':' looks like IPv4.
    val lastColonIdx = value.lastIndexOf(':')
    val possibleIpv4 = if (lastColonIdx >= 0) value.substring(lastColonIdx + 1) else ""
    val hasEmbeddedIpv4 = looksLikeIpv4(possibleIpv4)

    return if (hasEmbeddedIpv4) {
        // The IPv4 tail must be a valid dotted-decimal address.
        if (!isStrictIpv4Bare(possibleIpv4)) return false
        // Everything before the last ':' is the IPv6 prefix, which must supply 6 groups worth
        // of 16-bit values (the IPv4 takes the last 2 slots → 32 bits).
        val ipv6Prefix = value.substring(0, lastColonIdx)
        validateIpv6Groups(ipv6Prefix, hasDC, expectedGroups = 6)
    } else {
        validateIpv6Groups(value, hasDC, expectedGroups = 8)
    }
}

/** Returns true if the string looks like it could be an IPv4 address (contains a dot). */
private fun looksLikeIpv4(s: String): Boolean = s.contains('.')

/**
 * Validates up to [expectedGroups] hex groups in an IPv6 string that may contain `::`.
 * Does not handle an embedded IPv4 tail — that must already be stripped.
 */
private fun validateIpv6Groups(value: String, hasDC: Boolean, expectedGroups: Int): Boolean {
    return if (hasDC) {
        val dcIdx = value.indexOf("::")
        val left = value.substring(0, dcIdx)
        val right = value.substring(dcIdx + 2)

        val leftGroups = if (left.isEmpty()) emptyList() else left.split(":")
        val rightGroups = if (right.isEmpty()) emptyList() else right.split(":")

        // Each explicit group must be valid.
        for (g in leftGroups + rightGroups) {
            if (!isValidHexGroup(g)) return false
        }

        // Total explicit groups must be fewer than expectedGroups
        // (the :: replaces at least one group).
        val explicit = leftGroups.size + rightGroups.size
        explicit < expectedGroups
    } else {
        // No "::" — must be exactly expectedGroups groups.
        val groups = value.split(":")
        if (groups.size != expectedGroups) return false
        groups.all { isValidHexGroup(it) }
    }
}

/** Returns true if [g] is a valid IPv6 hex group: 1–4 hex digits. */
private fun isValidHexGroup(g: String): Boolean {
    if (g.isEmpty() || g.length > 4) return false
    return g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

/** isStrictIpv4 without any object-method dispatch (used internally). */
private fun isStrictIpv4Bare(value: String): Boolean {
    if (value.isEmpty()) return false
    val parts = value.split(".")
    if (parts.size != 4) return false
    for (part in parts) {
        if (part.isEmpty()) return false
        if (part.length > 1 && part[0] == '0') return false
        if (part.startsWith("0x") || part.startsWith("0X")) return false
        val n = part.toIntOrNull() ?: return false
        if (n < 0 || n > 255) return false
    }
    return true
}

object StringValidators {

    fun checkPattern(value: String, pattern: String, field: String): FieldViolation? {
        if (!Regex(pattern).containsMatchIn(value)) {
            return FieldViolation(field, "string.pattern", "must match pattern \"$pattern\"")
        }
        return null
    }

    fun checkMinLen(value: String, min: Long, field: String): FieldViolation? {
        val count = value.codePointCount(0, value.length).toLong()
        if (count < min) {
            return FieldViolation(field, "string.min_len", "must be at least $min characters")
        }
        return null
    }

    fun checkMaxLen(value: String, max: Long, field: String): FieldViolation? {
        val count = value.codePointCount(0, value.length).toLong()
        if (count > max) {
            return FieldViolation(field, "string.max_len", "must be at most $max characters")
        }
        return null
    }

    fun checkLen(value: String, len: Long, field: String): FieldViolation? {
        val count = value.codePointCount(0, value.length).toLong()
        if (count != len) {
            return FieldViolation(field, "string.len", "must be exactly $len characters")
        }
        return null
    }

    fun checkMinBytes(value: String, min: Long, field: String): FieldViolation? {
        if (value.toByteArray(Charsets.UTF_8).size.toLong() < min) {
            return FieldViolation(field, "string.min_bytes", "must be at least $min bytes")
        }
        return null
    }

    fun checkMaxBytes(value: String, max: Long, field: String): FieldViolation? {
        if (value.toByteArray(Charsets.UTF_8).size.toLong() > max) {
            return FieldViolation(field, "string.max_bytes", "must be at most $max bytes")
        }
        return null
    }

    fun checkLenBytes(value: String, len: Long, field: String): FieldViolation? {
        if (value.toByteArray(Charsets.UTF_8).size.toLong() != len) {
            return FieldViolation(field, "string.len_bytes", "must be exactly $len bytes")
        }
        return null
    }

    fun checkIn(value: String, allowed: List<String>, field: String): FieldViolation? {
        if (value !in allowed) {
            return FieldViolation(field, "string.in", "must be in $allowed")
        }
        return null
    }

    fun checkNotIn(value: String, disallowed: List<String>, field: String): FieldViolation? {
        if (value in disallowed) {
            return FieldViolation(field, "string.not_in", "must not be in $disallowed")
        }
        return null
    }

    fun checkConst(value: String, expected: String, field: String): FieldViolation? {
        if (value != expected) {
            return FieldViolation(field, "string.const", "must equal `$expected`")
        }
        return null
    }

    fun checkPrefix(value: String, prefix: String, field: String): FieldViolation? {
        if (!value.startsWith(prefix)) {
            return FieldViolation(field, "string.prefix", "must start with \"$prefix\"")
        }
        return null
    }

    fun checkSuffix(value: String, suffix: String, field: String): FieldViolation? {
        if (!value.endsWith(suffix)) {
            return FieldViolation(field, "string.suffix", "must end with \"$suffix\"")
        }
        return null
    }

    fun checkContains(value: String, substring: String, field: String): FieldViolation? {
        if (substring !in value) {
            return FieldViolation(field, "string.contains", "must contain \"$substring\"")
        }
        return null
    }

    fun checkNotContains(value: String, substring: String, field: String): FieldViolation? {
        if (substring in value) {
            return FieldViolation(field, "string.not_contains", "must not contain \"$substring\"")
        }
        return null
    }

    // ── Helper: pick rule ID with _empty suffix when value is empty ──

    private fun ruleId(base: String, value: String): String =
        if (value.isEmpty()) "${base}_empty" else base

    // ── Well-known type validators ──

    fun checkEmail(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.email", value)
        if (!isValidEmail(value)) {
            return FieldViolation(field, rule, "must be a valid email address")
        }
        return null
    }

    fun checkEmailPgv(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.email", value)
        if (!isValidEmailPgv(value)) {
            return FieldViolation(field, rule, "must be a valid email address")
        }
        return null
    }

    /**
     * PGV-compatible email validation (RFC 5321 semantics):
     * - Accepts "Display Name <email>" format
     * - Enforces 64-char local part limit
     */
    fun isValidEmailPgv(value: String): Boolean {
        if (value.isEmpty()) return false

        // Extract email from angle bracket format: "Name <email>" or "<email>"
        val bareEmail = if (value.contains('<') && value.contains('>')) {
            val start = value.indexOf('<')
            val end = value.indexOf('>')
            if (end <= start + 1) return false
            value.substring(start + 1, end)
        } else {
            value
        }

        // No whitespace in bare email
        if (bareEmail.any { it.isWhitespace() || it == '\n' || it == '\r' }) return false
        // No non-ASCII
        if (bareEmail.any { it.code > 127 }) return false
        // No parentheses (comments)
        if (bareEmail.contains('(') || bareEmail.contains(')')) return false

        val atIdx = bareEmail.lastIndexOf('@')
        if (atIdx < 1) return false
        val local = bareEmail.substring(0, atIdx)
        val domain = bareEmail.substring(atIdx + 1)
        if (local.isEmpty() || domain.isEmpty()) return false

        // RFC 5321: local part max 64 chars
        if (local.length > 64) return false

        // Local part: no quoted strings (starts with ")
        if (local.startsWith('"')) return false
        // No spaces in local part
        if (local.contains(' ')) return false

        // Domain: no IP literals
        if (domain.startsWith('[')) return false
        // Domain: no trailing dot
        if (domain.endsWith('.')) return false
        if (!isValidEmailDomain(domain)) return false
        return true
    }

    fun isValidEmail(value: String): Boolean {
        if (value.isEmpty()) return false

        // Angle brackets and display name form are NOT valid emails
        if (value.contains('<') || value.contains('>')) return false

        val bareEmail = value

        // No whitespace anywhere in the bare email address
        if (bareEmail.any { it.isWhitespace() || it == '\n' || it == '\r' }) return false
        // No non-ASCII
        if (bareEmail.any { it.code > 127 }) return false
        // No parentheses (comments)
        if (bareEmail.contains('(') || bareEmail.contains(')')) return false

        val atIdx = bareEmail.lastIndexOf('@')
        if (atIdx < 1) return false
        val local = bareEmail.substring(0, atIdx)
        val domain = bareEmail.substring(atIdx + 1)
        if (local.isEmpty() || domain.isEmpty()) return false

        // Note: RFC 5321 has a 64-char limit for SMTP, but protovalidate follows RFC 5322
        // which doesn't impose this limit. Don't enforce it here.

        // Local part: no quoted strings (starts with ")
        if (local.startsWith('"')) return false
        // No spaces in local part
        if (local.contains(' ')) return false

        // Domain: no IP literals
        if (domain.startsWith('[')) return false
        // Domain: no trailing dot
        if (domain.endsWith('.')) return false
        // Domain must be a valid hostname, but email allows all-digit last labels
        // (e.g. foo@0.1.2.3.4.5.6.7.8.9), so we use a lenient check here.
        if (!isValidEmailDomain(domain)) return false
        return true
    }

    /**
     * Like [isValidHostname] but allows all-digit last labels, since email TLDs can be numeric.
     * E.g. `foo@0.1.2.3.4.5.6.7.8.9` is a valid email address per RFC 5321.
     */
    private fun isValidEmailDomain(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        if (value.any { it.code > 127 }) return false

        val normalized = if (value.endsWith(".")) value.dropLast(1) else value
        if (normalized.isEmpty() || normalized.length > 253) return false
        if (normalized.startsWith('.')) return false

        val labels = normalized.split(".")
        for (label in labels) {
            if (label.isEmpty()) return false
            if (label.length > 63) return false
            if (label.contains('_')) return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
            if (!label.first().isLetterOrDigit()) return false
            if (!label.last().isLetterOrDigit()) return false
        }
        // NOTE: Unlike isValidHostname, we do NOT reject all-digit last labels here.
        return true
    }

    fun checkUri(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.uri", value)
        if (!isValidUri(value)) {
            return FieldViolation(field, rule, "must be a valid URI")
        }
        return null
    }

    fun isValidUri(value: String): Boolean {
        if (value.isEmpty()) return false
        // A URI must have a scheme component: scheme ":" hier-part
        // Scheme: letter followed by letters/digits/+/-/.
        val colonIdx = value.indexOf(':')
        if (colonIdx < 1) return false
        val scheme = value.substring(0, colonIdx)
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*$").matches(scheme)) return false

        val rest = value.substring(colonIdx + 1)
        return isValidUriHierPart(rest)
    }

    /**
     * Validates the part of a URI after the scheme colon (hier-part + optional query + optional fragment).
     * Handles authority parsing: userinfo, host (IPv6, IPv4, reg-name), port.
     */
    private fun isValidUriHierPart(rest: String): Boolean {
        // Split off fragment first (fragment = "#" *( pchar / "/" / "?" ))
        // There must be at most one '#'; the fragment itself must not contain '#'.
        val fragIdx = rest.indexOf('#')
        val (beforeFrag, frag) = if (fragIdx >= 0) {
            Pair(rest.substring(0, fragIdx), rest.substring(fragIdx + 1))
        } else {
            Pair(rest, null)
        }

        // Fragment must not contain another '#'
        if (frag != null && frag.contains('#')) return false

        // Split off query (?...)
        val queryIdx = beforeFrag.indexOf('?')
        val (hierPart, query) = if (queryIdx >= 0) {
            Pair(beforeFrag.substring(0, queryIdx), beforeFrag.substring(queryIdx + 1))
        } else {
            Pair(beforeFrag, null)
        }

        // Validate query chars (pchar / "/" / "?") — same as general URI chars
        if (query != null && !isValidUriChars(query)) return false

        // Validate fragment chars (pchar / "/" / "?") — same as general URI chars
        if (frag != null && !isValidUriChars(frag)) return false

        // hier-part: "//" authority path-abempty | path-absolute | path-rootless | path-empty
        if (hierPart.startsWith("//")) {
            // authority + path-abempty
            val authorityAndPath = hierPart.substring(2)
            // Authority ends at the first '/' (or end of string)
            val pathStart = authorityAndPath.indexOf('/')
            val authority = if (pathStart >= 0) authorityAndPath.substring(0, pathStart)
                            else authorityAndPath
            val path = if (pathStart >= 0) authorityAndPath.substring(pathStart) else ""

            if (!isValidAuthority(authority)) return false
            if (!isValidUriChars(path)) return false
        } else {
            // path-absolute, path-rootless, or path-empty — just validate chars
            if (!isValidUriChars(hierPart)) return false
        }

        return true
    }

    /**
     * Validates an RFC 3986 authority: [ userinfo "@" ] host [ ":" port ]
     *
     * Rules enforced here (beyond character-level):
     * - Userinfo MUST NOT contain '[' or ']'
     * - Host in brackets must be valid IPv6 (not IPvFuture); zone-id ('%' suffix) must be non-empty
     *   and contain valid UTF-8 when percent-decoded
     * - Host NOT in brackets must not be a bare IPv6 address (contains ':')
     * - Port after the last ':' must be all-digit (or empty)
     * - Percent-encoded bytes in reg-name and zone-id must form valid UTF-8 sequences
     */
    private fun isValidAuthority(authority: String): Boolean {
        if (authority.isEmpty()) return true  // empty authority is valid (e.g. "file:///path")

        // Split userinfo from host[:port]
        // The '@' separator: if present, everything before the last '@' is userinfo
        val atIdx = authority.lastIndexOf('@')
        val userinfo = if (atIdx >= 0) authority.substring(0, atIdx) else null
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

        // Validate userinfo: must not contain '[', ']', or '@'
        // (they are gen-delims not allowed in userinfo per RFC 3986 §3.2.1)
        // userinfo = *( unreserved / pct-encoded / sub-delims / ":" )
        if (userinfo != null) {
            if (userinfo.contains('[') || userinfo.contains(']') || userinfo.contains('@')) return false
            if (!isValidUriChars(userinfo)) return false
        }

        // Parse host and port from hostPort
        val host: String
        val portStr: String?

        if (hostPort.startsWith('[')) {
            // IP-literal: "[" ( IPv6address / IPvFuture ) "]"
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket < 0) return false
            val inner = hostPort.substring(1, closeBracket)
            val afterBracket = hostPort.substring(closeBracket + 1)

            // IPvFuture format [vHEXDIG.chars] — valid per RFC 3986 §3.2.2
            if (inner.startsWith('v') || inner.startsWith('V')) {
                // Format: "v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )
                val dotIdx = inner.indexOf('.')
                if (dotIdx < 2) return false // need at least "vX."
                val hexPart = inner.substring(1, dotIdx)
                if (!hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
                val addrPart = inner.substring(dotIdx + 1)
                if (addrPart.isEmpty()) return false
                // addrPart chars: unreserved / sub-delims / ":"
                val ipFutureChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:"
                if (!addrPart.all { it in ipFutureChars }) return false
                // IPvFuture is valid — skip IPv6 validation
            } else {
                // Zone-id per RFC 6874: in URI form, zone separator is "%25" (not bare "%")
                // Zone-id = 1*( unreserved / pct-encoded )
                val zoneSepIdx = inner.indexOf("%25")
                val ipv6Part: String
                val zoneId: String?
                if (zoneSepIdx >= 0) {
                    ipv6Part = inner.substring(0, zoneSepIdx)
                    zoneId = inner.substring(zoneSepIdx + 3) // after "%25"
                } else {
                    // Bare "%" is NOT valid in URI (must be percent-encoded as %25)
                    if (inner.contains('%')) return false
                    ipv6Part = inner
                    zoneId = null
                }

                // Validate IPv6 address part
                if (!isStrictIpv6(ipv6Part)) return false

                // Zone-id must be non-empty and consist of unreserved / pct-encoded
                if (zoneId != null) {
                    if (zoneId.isEmpty()) return false
                    if (!isValidUriChars(zoneId)) return false
                    if (!isValidPctEncodedUtf8(zoneId)) return false
                }
            }

            // After the ']', only an optional port ":digits" is allowed
            portStr = when {
                afterBracket.isEmpty() -> null
                afterBracket.startsWith(':') -> afterBracket.substring(1)
                else -> return false
            }
            host = inner  // already validated above
        } else {
            // reg-name or IPv4: find port by looking for the LAST ':'
            // A bare IPv6 address (unbracketed) with multiple colons is rejected:
            // if splitting on the last ':' leaves a host that still contains ':', it's bare IPv6.
            val lastColon = hostPort.lastIndexOf(':')
            if (lastColon >= 0) {
                val possibleHost = hostPort.substring(0, lastColon)
                val possiblePort = hostPort.substring(lastColon + 1)

                // If possibleHost still contains ':', this is a bare IPv6 — invalid
                if (possibleHost.contains(':')) return false

                host = possibleHost
                portStr = possiblePort
            } else {
                host = hostPort
                portStr = null
            }

            // Validate reg-name chars and percent-encoded UTF-8
            if (!isValidUriChars(host)) return false
            if (!isValidPctEncodedUtf8(host)) return false
        }

        // Validate port: must be all-digit (empty port "" after ":" is allowed by RFC 3986
        // grammar, but we enforce digits-only when non-empty)
        if (portStr != null && portStr.isNotEmpty()) {
            if (!portStr.all { it.isDigit() }) return false
        }

        return true
    }

    /**
     * Decodes percent-encoded sequences in [s] and verifies they form valid UTF-8.
     * Non-percent-encoded bytes are left as-is (assumed ASCII-safe by isValidUriChars).
     * Returns false if any percent-encoded run decodes to invalid UTF-8.
     */
    private fun isValidPctEncodedUtf8(s: String): Boolean {
        var i = 0
        val bytes = mutableListOf<Byte>()
        while (i < s.length) {
            if (s[i] == '%') {
                // isValidUriChars already guaranteed 2 hex digits follow
                val hi = s[i + 1].digitToInt(16)
                val lo = s[i + 2].digitToInt(16)
                bytes += ((hi shl 4) or lo).toByte()
                i += 3
            } else {
                // Flush any accumulated bytes
                if (bytes.isNotEmpty()) {
                    if (!isValidUtf8Bytes(bytes)) return false
                    bytes.clear()
                }
                i++
            }
        }
        if (bytes.isNotEmpty() && !isValidUtf8Bytes(bytes)) return false
        return true
    }

    /** Returns true if the byte array is a valid UTF-8 byte sequence. */
    private fun isValidUtf8Bytes(bytes: List<Byte>): Boolean {
        return try {
            val arr = bytes.toByteArray()
            val decoded = String(arr, Charsets.UTF_8)
            // Re-encode and compare to detect replacement characters from lenient decode
            decoded.toByteArray(Charsets.UTF_8).contentEquals(arr)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check that all characters in the URI (after scheme) are valid RFC 3986 characters.
     * Returns false if any invalid characters (control chars, carets, invalid % encoding, etc.)
     */
    private fun isValidUriChars(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    // Percent-encoding: must be followed by exactly 2 hex digits
                    if (i + 2 >= s.length) return false
                    val h1 = s[i + 1]
                    val h2 = s[i + 2]
                    if (!h1.isHexDigit() || !h2.isHexDigit()) return false
                    i += 3
                }
                c.isUriSafeChar() -> i++
                else -> return false
            }
        }
        return true
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Returns true if the character is a valid URI character (unreserved, reserved, or pchar).
     * Excludes: control chars (< 0x20 or 0x7F), non-ASCII (> 0x7F), and characters not in RFC 3986.
     * Valid chars: unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
     *              sub-delims = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
     *              gen-delims = ":" / "/" / "?" / "#" / "[" / "]" / "@"
     * Excluded: space, ", <, >, \, ^, `, {, |, }
     */
    private fun Char.isUriSafeChar(): Boolean {
        if (this.code < 0x21) return false  // control chars and space
        if (this.code > 0x7E) return false  // non-ASCII and DEL
        return when (this) {
            // Excluded chars in URI
            '"', '<', '>', '\\', '^', '`', '{', '|', '}' -> false
            ' ' -> false
            else -> true
        }
    }

    fun checkUuid(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.uuid", value)
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        if (!uuidPattern.matches(value)) {
            return FieldViolation(field, rule, "must be a valid UUID")
        }
        return null
    }

    fun checkHostname(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.hostname", value)
        if (!isValidHostname(value)) {
            return FieldViolation(field, rule, "must be a valid hostname")
        }
        return null
    }

    fun isValidHostname(value: String): Boolean {
        if (value.isEmpty()) return false
        // No leading/trailing whitespace
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        // No non-ASCII characters
        if (value.any { it.code > 127 }) return false

        // Strip optional trailing dot (FQDN)
        val normalized = if (value.endsWith(".")) value.dropLast(1) else value
        if (normalized.isEmpty() || normalized.length > 253) return false

        // Must not start with a dot (empty first label)
        if (normalized.startsWith('.')) return false

        val labels = normalized.split(".")
        for (label in labels) {
            if (label.isEmpty()) return false  // consecutive dots
            if (label.length > 63) return false
            // No underscores
            if (label.contains('_')) return false
            // No non-alphanumeric/hyphen characters
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
            // Must start and end with alphanumeric
            if (!label.first().isLetterOrDigit()) return false
            if (!label.last().isLetterOrDigit()) return false
        }

        // The last label must not be all-digits (to distinguish from an IP address)
        val lastLabel = labels.last()
        if (lastLabel.all { it.isDigit() }) return false

        return true
    }

    fun checkIp(value: String, field: String): FieldViolation? {
        return checkIp(value, field, version = 0)
    }

    fun checkIp(value: String, field: String, version: Int): FieldViolation? {
        val rule = ruleId("string.ip", value)
        if (!isValidIpForVersion(value, version)) {
            return FieldViolation(field, rule, "must be a valid IP address")
        }
        return null
    }

    fun isValidIpForVersion(value: String, version: Int): Boolean {
        if (value.isEmpty()) return false
        // No leading/trailing whitespace
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        // No brackets (IP literals)
        if (value.startsWith('[') || value.endsWith(']')) return false
        // No prefix notation
        if (value.contains('/')) return false
        // No zone IDs
        if (value.contains('%')) return false

        return when (version) {
            4 -> isStrictIpv4(value)
            6 -> isStrictIpv6(value)
            0 -> isStrictIpv4(value) || isStrictIpv6(value)
            else -> false  // unknown IP version is always invalid
        }
    }

    fun checkIpv4(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv4", value)
        if (!isStrictIpv4(value)) {
            return FieldViolation(field, rule, "must be a valid IPv4 address")
        }
        return null
    }

    /**
     * Strict IPv4 validation:
     * - Exactly 4 decimal octets separated by dots
     * - Each octet in 0-255
     * - No leading zeros (e.g. "01" is invalid)
     * - No extra whitespace
     * - No trailing content
     */
    fun isStrictIpv4(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty()) return false
            // No leading zeros (except "0" itself)
            if (part.length > 1 && part[0] == '0') return false
            // No hex prefix
            if (part.startsWith("0x") || part.startsWith("0X")) return false
            val n = part.toIntOrNull() ?: return false
            if (n < 0 || n > 255) return false
        }
        return true
    }

    fun checkIpv6(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv6", value)
        if (!isStrictIpv6(value)) {
            return FieldViolation(field, rule, "must be a valid IPv6 address")
        }
        return null
    }

    fun checkUriRef(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.uri_ref", value)
        if (!isValidUriRef(value)) {
            return FieldViolation(field, rule, "must be a valid URI reference")
        }
        return null
    }

    fun isValidUriRef(value: String): Boolean {
        // Empty string is a valid URI reference (path-empty relative-reference per RFC 3986 §4.2)
        if (value.isEmpty()) return true

        // URI reference: URI or relative-ref
        // A relative-ref starts with no scheme (no "letter [letter|digit|+|-|.]*:") before a / or ? or #

        // Check if it has a scheme-like prefix
        val colonIdx = value.indexOf(':')
        val slashIdx = value.indexOf('/')
        val queryIdx = value.indexOf('?')
        val fragIdx = value.indexOf('#')

        val hasScheme = colonIdx > 0 &&
            (slashIdx < 0 || colonIdx < slashIdx) &&
            (queryIdx < 0 || colonIdx < queryIdx) &&
            (fragIdx < 0 || colonIdx < fragIdx)

        return if (hasScheme) {
            val scheme = value.substring(0, colonIdx)
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*$").matches(scheme)) return false
            val rest = value.substring(colonIdx + 1)
            isValidUriHierPart(rest)
        } else {
            // Relative reference - first character must not be ':'
            if (value.startsWith(':')) return false
            // Apply the same hier-part validation (fragment '#' check, authority parsing, etc.)
            // For a relative-ref the "rest" is the whole value (no scheme prefix to strip).
            isValidUriHierPart(value)
        }
    }

    fun checkAddress(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.address", value)
        val isHostname = checkHostname(value, field) == null
        val isIp = checkIp(value, field) == null
        if (!isHostname && !isIp) {
            return FieldViolation(field, rule, "must be a valid hostname or IP address")
        }
        return null
    }

    fun checkHttpHeaderName(value: String, strict: Boolean, field: String): FieldViolation? {
        if (value.isEmpty()) {
            return FieldViolation(field, "string.well_known_regex.header_name_empty", "value is required")
        }
        if (strict) {
            // HTTP/2 pseudo-headers start with ':' and are valid
            if (value.startsWith(":")) {
                val pseudo = value.substring(1)
                if (pseudo.isEmpty() || !Regex("^[a-z]+$").matches(pseudo)) {
                    return FieldViolation(field, "string.well_known_regex.header_name", "must be a valid HTTP header name")
                }
                return null
            }
            val tokenPattern = Regex("^[!#\$%&'*+\\-.^_`|~0-9a-zA-Z]+$")
            if (!tokenPattern.matches(value)) {
                return FieldViolation(field, "string.well_known_regex.header_name", "must be a valid HTTP header name")
            }
        } else {
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                return FieldViolation(field, "string.well_known_regex.header_name", "must not contain CR, LF, or NUL")
            }
        }
        return null
    }

    fun checkHttpHeaderValue(value: String, strict: Boolean, field: String): FieldViolation? {
        if (value.isEmpty()) {
            return null
        }
        if (strict) {
            val fieldValuePattern = Regex("^[\t\u0020-\u007E\u0080-\u00FF]*$")
            if (!fieldValuePattern.matches(value) || value.any { it == '\r' || it == '\n' }) {
                return FieldViolation(field, "string.well_known_regex.header_value", "must be a valid HTTP header value")
            }
        } else {
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                return FieldViolation(field, "string.well_known_regex.header_value", "must not contain CR, LF, or NUL")
            }
        }
        return null
    }

    // ── New well-known type validators ──

    fun checkHostAndPort(value: String, field: String): FieldViolation? {
        return checkHostAndPort(value, field, portRequired = true)
    }

    fun checkHostAndPort(value: String, field: String, portRequired: Boolean): FieldViolation? {
        val rule = ruleId("string.host_and_port", value)
        if (value.isEmpty()) {
            return FieldViolation(field, rule, "must be a valid host:port")
        }
        // No leading/trailing whitespace
        if (value.first().isWhitespace() || value.last().isWhitespace()) {
            return FieldViolation(field, rule, "must be a valid host:port")
        }

        val (host, portStr, hasPort) = if (value.startsWith("[")) {
            // IPv6 bracketed: [::1]:80, [::1], [::1%zone]:80, [::1%zone]
            // When a zone-id is present the ']' character may itself appear inside the zone-id
            // (conformance test `[::0%00]]`).  We therefore look for the LAST ']' in the string
            // as the closing bracket, which allows any non-null character in the zone-id.
            val closeBracket = value.lastIndexOf(']')
            if (closeBracket < 0) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
            val innerHost = value.substring(1, closeBracket)
            val rest = value.substring(closeBracket + 1)
            if (rest.isEmpty()) {
                // No port - [host] without port
                Triple(innerHost, "", false)
            } else if (rest.startsWith(":")) {
                Triple(innerHost, rest.substring(1), true)
            } else {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
        } else {
            val lastColon = value.lastIndexOf(':')
            if (lastColon < 0) {
                // No port
                Triple(value, "", false)
            } else {
                Triple(value.substring(0, lastColon), value.substring(lastColon + 1), true)
            }
        }

        if (host.isEmpty()) {
            return FieldViolation(field, rule, "must be a valid host:port")
        }

        // If we have a bracketed expression, the host inside must be IPv6
        // (possibly with a zone-id after %) — not IPv4, not a hostname.
        if (value.startsWith("[")) {
            // Split on '%' to separate IPv6 address from zone-id.
            val percentIdx = host.indexOf('%')
            val ipv6Part = if (percentIdx >= 0) host.substring(0, percentIdx) else host
            val zoneId   = if (percentIdx >= 0) host.substring(percentIdx + 1) else null

            // IPv4 in brackets is invalid.
            if (isStrictIpv4(ipv6Part)) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
            // The IPv6 part (before any %) must be a valid IPv6 address (may include embedded IPv4).
            if (!parseIpv6Strict(ipv6Part)) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
            // If a zone-id is present it must be non-empty (any non-null characters are OK per
            // Go's net.SplitHostPort behaviour used by the reference conformance implementation).
            if (zoneId != null && zoneId.isEmpty()) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
        }

        // Validate port
        if (hasPort) {
            if (portStr.isEmpty()) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
            if (!isValidPort(portStr)) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
        } else if (portRequired) {
            return FieldViolation(field, rule, "must be a valid host:port")
        }

        // Validate host when not bracketed
        if (!value.startsWith("[")) {
            // No non-ASCII
            if (host.any { it.code > 127 }) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
            val hostValid = isStrictIpv4(host) || isValidHostname(host)
            if (!hostValid) {
                return FieldViolation(field, rule, "must be a valid host:port")
            }
        }

        return null
    }

    /**
     * Validates a port string (decimal digits, 0-65535, no leading zeros, no sign prefix, no hex).
     * Port 0 is valid per the conformance tests.
     */
    private fun isValidPort(portStr: String): Boolean {
        if (portStr.isEmpty()) return false
        // No sign prefix
        if (portStr[0] == '+' || portStr[0] == '-') return false
        // No hex prefix
        if (portStr.startsWith("0x") || portStr.startsWith("0X")) return false
        // No non-digit characters
        if (!portStr.all { it.isDigit() }) return false
        // No leading zeros (except "0" itself — port 0 is valid)
        if (portStr.length > 1 && portStr[0] == '0') return false
        val port = portStr.toLongOrNull() ?: return false
        return port in 0..65535
    }

    fun checkUlid(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ulid", value)
        if (value.length != 26) {
            return FieldViolation(field, rule, "must be a valid ULID")
        }
        val ulidAlphabet = Regex("^[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{26}$")
        if (!ulidAlphabet.matches(value)) {
            return FieldViolation(field, rule, "must be a valid ULID")
        }
        // First character must be <= '7' (timestamp overflow guard)
        if (value[0].uppercaseChar() > '7') {
            return FieldViolation(field, rule, "must be a valid ULID")
        }
        return null
    }

    fun checkProtobufFqn(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.protobuf_fqn", value)
        if (value.isEmpty()) {
            return FieldViolation(field, rule, "must be a valid protobuf FQN")
        }
        // Must match: [a-zA-Z_][a-zA-Z0-9_]* (. [a-zA-Z_][a-zA-Z0-9_]*)*
        // No leading/trailing dot, no double dots, each segment starts with letter or underscore
        val fqnPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")
        if (!fqnPattern.matches(value)) {
            return FieldViolation(field, rule, "must be a valid protobuf FQN")
        }
        return null
    }

    fun checkProtobufDotFqn(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.protobuf_dot_fqn", value)
        if (value.isEmpty()) {
            return FieldViolation(field, rule, "must be a valid protobuf dot-prefixed FQN")
        }
        // Starts with '.', then same as FQN
        if (!value.startsWith(".")) {
            return FieldViolation(field, rule, "must be a valid protobuf dot-prefixed FQN")
        }
        val rest = value.substring(1)
        if (rest.isEmpty()) {
            return FieldViolation(field, rule, "must be a valid protobuf dot-prefixed FQN")
        }
        val fqnPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$")
        if (!fqnPattern.matches(rest)) {
            return FieldViolation(field, rule, "must be a valid protobuf dot-prefixed FQN")
        }
        return null
    }

    fun checkTuuid(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.tuuid", value)
        if (value.length != 32) {
            return FieldViolation(field, rule, "must be a valid TUUID")
        }
        if (!Regex("^[0-9a-fA-F]{32}$").matches(value)) {
            return FieldViolation(field, rule, "must be a valid TUUID")
        }
        return null
    }

    fun checkIpWithPrefixlen(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ip_with_prefixlen", value)
        val (_, prefixLen, maxLen) = parseIpPrefix(value)
            ?: return FieldViolation(field, rule, "must be a valid IP address with prefix length")
        if (prefixLen < 0 || prefixLen > maxLen) {
            return FieldViolation(field, rule, "must be a valid IP address with prefix length")
        }
        return null
    }

    fun checkIpv4WithPrefixlen(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv4_with_prefixlen", value)
        val (_, prefixLen, maxLen) = parseIpPrefix(value)
            ?: return FieldViolation(field, rule, "must be a valid IPv4 address with prefix length")
        if (maxLen != 32) {
            return FieldViolation(field, rule, "must be a valid IPv4 address with prefix length")
        }
        if (prefixLen < 0 || prefixLen > 32) {
            return FieldViolation(field, rule, "must be a valid IPv4 address with prefix length")
        }
        return null
    }

    fun checkIpv6WithPrefixlen(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv6_with_prefixlen", value)
        val (_, prefixLen, maxLen) = parseIpPrefix(value)
            ?: return FieldViolation(field, rule, "must be a valid IPv6 address with prefix length")
        if (maxLen != 128) {
            return FieldViolation(field, rule, "must be a valid IPv6 address with prefix length")
        }
        if (prefixLen < 0 || prefixLen > 128) {
            return FieldViolation(field, rule, "must be a valid IPv6 address with prefix length")
        }
        return null
    }

    fun checkIpPrefix(value: String, field: String): FieldViolation? {
        return checkIpPrefix(value, field, version = 0, strict = false)
    }

    fun checkIpPrefix(value: String, field: String, version: Int = 0, strict: Boolean = false): FieldViolation? {
        val rule = ruleId("string.ip_prefix", value)
        if (!isValidIpPrefix(value, version, strict)) {
            return FieldViolation(field, rule, "must be a valid IP prefix (network address)")
        }
        return null
    }

    fun isValidIpPrefix(value: String, version: Int, strict: Boolean): Boolean {
        if (value.isEmpty()) return false
        // No leading/trailing whitespace
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        // No zone IDs
        if (value.contains('%')) return false

        val slashIdx = value.indexOf('/')
        if (slashIdx < 0) return false  // Must have a slash

        val ipPart = value.substring(0, slashIdx)
        val lenPart = value.substring(slashIdx + 1)

        if (lenPart.isEmpty()) return false
        // No leading zeros in prefix length
        if (lenPart.length > 1 && lenPart[0] == '0') return false
        val prefixLen = lenPart.toLongOrNull() ?: return false
        if (prefixLen < 0) return false

        val isV4 = isStrictIpv4(ipPart)
        val isV6 = !isV4 && isStrictIpv6(ipPart)
        if (!isV4 && !isV6) return false

        val maxLen = if (isV4) 32L else 128L

        // Version check
        when (version) {
            4 -> if (!isV4) return false
            6 -> if (!isV6) return false
            0 -> { /* accept either */ }
            else -> return false  // unknown IP version is always invalid
        }

        if (prefixLen > maxLen) return false

        if (strict) {
            if (!isNetworkAddress(ipPart, prefixLen.toInt(), maxLen.toInt())) return false
        }

        return true
    }

    fun checkIpv4Prefix(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv4_prefix", value)
        val triple = parseIpPrefix(value)
            ?: return FieldViolation(field, rule, "must be a valid IPv4 prefix")
        val (ip, prefixLen, maxLen) = triple
        if (maxLen != 32) {
            return FieldViolation(field, rule, "must be a valid IPv4 prefix")
        }
        if (prefixLen < 0 || prefixLen > 32) {
            return FieldViolation(field, rule, "must be a valid IPv4 prefix")
        }
        if (!isNetworkAddress(ip, prefixLen, 32)) {
            return FieldViolation(field, rule, "must be a valid IPv4 prefix")
        }
        return null
    }

    fun checkIpv6Prefix(value: String, field: String): FieldViolation? {
        val rule = ruleId("string.ipv6_prefix", value)
        val triple = parseIpPrefix(value)
            ?: return FieldViolation(field, rule, "must be a valid IPv6 prefix")
        val (ip, prefixLen, maxLen) = triple
        if (maxLen != 128) {
            return FieldViolation(field, rule, "must be a valid IPv6 prefix")
        }
        if (prefixLen < 0 || prefixLen > 128) {
            return FieldViolation(field, rule, "must be a valid IPv6 prefix")
        }
        if (!isNetworkAddress(ip, prefixLen, 128)) {
            return FieldViolation(field, rule, "must be a valid IPv6 prefix")
        }
        return null
    }

    // ── Internal IP helpers ──

    /**
     * Returns true if [value] is a syntactically valid IPv6 address string (strict).
     *
     * Strict mode:
     * - No zone IDs (`%`)
     * - No brackets (`[`, `]`)
     * - No prefix (`/`)
     * - Supports embedded IPv4 in the last two groups (e.g. `::ffff:192.168.1.1`)
     * - Each hex group is at most 4 digits (rejects `::0000ffff`)
     *
     * Uses [parseIpv6Strict] — does NOT rely on [InetAddress.getByName].
     */
    fun isStrictIpv6(value: String): Boolean {
        if (value.isEmpty()) return false
        // No leading/trailing whitespace
        if (value.first().isWhitespace() || value.last().isWhitespace()) return false
        // No brackets
        if (value.startsWith('[') || value.endsWith(']')) return false
        // No zone IDs
        if (value.contains('%')) return false
        // No prefix
        if (value.contains('/')) return false
        // Must contain at least one colon
        if (!value.contains(':')) return false
        return parseIpv6Strict(value)
    }

    /**
     * Returns true if [value] is a valid IPv6 address optionally followed by a zone ID.
     *
     * Zone ID syntax (RFC 6874): `%` followed by one or more non-null characters.
     * Examples: `::1%eth0`, `fe80::1%25eth0`, `::1%% :x\u001F`.
     *
     * Does NOT accept brackets or prefix notation.
     */
    fun isValidIpv6WithZone(value: String): Boolean {
        if (value.isEmpty()) return false
        // Don't check trailing whitespace — zone IDs can contain any non-null character
        if (value.startsWith('[') || value.endsWith(']')) return false
        if (value.contains('/')) return false
        if (!value.contains(':')) return false

        val percentIdx = value.indexOf('%')
        return if (percentIdx >= 0) {
            val ipv6Part = value.substring(0, percentIdx)
            val zoneId   = value.substring(percentIdx + 1)
            // Zone-id must be non-empty and contain no null bytes.
            if (zoneId.isEmpty() || zoneId.contains('\u0000')) return false
            parseIpv6Strict(ipv6Part)
        } else {
            parseIpv6Strict(value)
        }
    }

    /**
     * Validates an IP address for the library `is_ip` rule (version 0, 4, or 6).
     *
     * Unlike [isValidIpForVersion], this function:
     * - Accepts IPv6 zone IDs (text after `%`)
     * - Accepts embedded IPv4 in IPv6 (e.g. `::ffff:192.168.1.1`)
     *
     * It still rejects brackets and prefix notation.
     */
    fun isValidIpLibrary(value: String, version: Int): Boolean {
        if (value.isEmpty()) return false
        // Don't check trailing whitespace — zone IDs can contain control chars
        if (value.startsWith('[') || value.endsWith(']')) return false
        if (value.contains('/')) return false

        return when (version) {
            4 -> isStrictIpv4(value)
            6 -> isValidIpv6WithZone(value)
            0 -> isStrictIpv4(value) || isValidIpv6WithZone(value)
            else -> false
        }
    }

    /**
     * Parses "ip/prefix" notation.
     * Returns Triple(ipString, prefixLen, maxPrefixLen) or null on parse failure.
     * maxPrefixLen is 32 for IPv4, 128 for IPv6.
     */
    private fun parseIpPrefix(value: String): Triple<String, Int, Int>? {
        if (value.isEmpty()) return null
        if (value.first().isWhitespace() || value.last().isWhitespace()) return null
        val slashIdx = value.indexOf('/')
        if (slashIdx < 0) return null
        val ipPart = value.substring(0, slashIdx)
        val lenPart = value.substring(slashIdx + 1)
        if (lenPart.isEmpty()) return null
        // No leading zeros
        if (lenPart.length > 1 && lenPart[0] == '0') return null
        val prefixLen = lenPart.toIntOrNull() ?: return null
        val isV4 = isStrictIpv4(ipPart)
        val isV6 = !isV4 && isStrictIpv6(ipPart)
        if (!isV4 && !isV6) return null
        val maxLen = if (isV4) 32 else 128
        return Triple(ipPart, prefixLen, maxLen)
    }

    /**
     * Returns true if the IP address has all host bits zeroed for the given prefix length,
     * i.e. it is a valid network address.
     */
    private fun isNetworkAddress(ip: String, prefixLen: Int, maxLen: Int): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            val rawBytes = addr.address
            // If we expect 128 bits (IPv6) but got 4 bytes (Java resolved IPv4-mapped to Inet4Address),
            // reconstruct the full 16-byte IPv6 representation: ::ffff:<4 bytes>
            val bytes = if (maxLen == 128 && rawBytes.size == 4) {
                ByteArray(16).also { full ->
                    full[10] = 0xFF.toByte()
                    full[11] = 0xFF.toByte()
                    rawBytes.copyInto(full, 12)
                }
            } else {
                rawBytes
            }
            // For each bit position >= prefixLen, it must be 0
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
}

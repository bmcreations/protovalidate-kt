package dev.bmcreations.protovalidate

object BytesValidators {

    fun checkLen(value: ByteArray, len: Long, field: String): FieldViolation? {
        if (value.size.toLong() != len) {
            return FieldViolation(field, "bytes.len", "must be exactly $len bytes")
        }
        return null
    }

    fun checkMinLen(value: ByteArray, min: Long, field: String): FieldViolation? {
        if (value.size.toLong() < min) {
            return FieldViolation(field, "bytes.min_len", "must be at least $min bytes")
        }
        return null
    }

    fun checkMaxLen(value: ByteArray, max: Long, field: String): FieldViolation? {
        if (value.size.toLong() > max) {
            return FieldViolation(field, "bytes.max_len", "must be at most $max bytes")
        }
        return null
    }

    fun checkConst(value: ByteArray, expected: ByteArray, field: String): FieldViolation? {
        if (!value.contentEquals(expected)) {
            val hex = expected.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
            return FieldViolation(field, "bytes.const", "must be $hex")
        }
        return null
    }

    fun checkPattern(value: ByteArray, pattern: String, field: String): FieldViolation? {
        // Check if the bytes are valid UTF-8 before applying regex
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val str = try {
            decoder.decode(java.nio.ByteBuffer.wrap(value)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw RuntimeException("must be valid UTF-8 to apply regexp")
        }
        if (!Regex(pattern).containsMatchIn(str)) {
            return FieldViolation(field, "bytes.pattern", "must match pattern \"$pattern\"")
        }
        return null
    }

    fun checkPrefix(value: ByteArray, prefix: ByteArray, field: String): FieldViolation? {
        if (value.size < prefix.size || !value.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            return FieldViolation(field, "bytes.prefix", "must start with the expected byte sequence")
        }
        return null
    }

    fun checkSuffix(value: ByteArray, suffix: ByteArray, field: String): FieldViolation? {
        val offset = value.size - suffix.size
        if (offset < 0 || !value.copyOfRange(offset, value.size).contentEquals(suffix)) {
            return FieldViolation(field, "bytes.suffix", "must end with the expected byte sequence")
        }
        return null
    }

    fun checkContains(value: ByteArray, contained: ByteArray, field: String): FieldViolation? {
        if (contained.isEmpty()) return null
        val found = (0..value.size - contained.size).any { i ->
            value.copyOfRange(i, i + contained.size).contentEquals(contained)
        }
        if (!found) {
            return FieldViolation(field, "bytes.contains", "must contain the expected byte subsequence")
        }
        return null
    }

    fun checkIn(value: ByteArray, allowed: List<ByteArray>, field: String): FieldViolation? {
        if (allowed.none { it.contentEquals(value) }) {
            return FieldViolation(field, "bytes.in", "must be in the allowed list")
        }
        return null
    }

    fun checkNotIn(value: ByteArray, disallowed: List<ByteArray>, field: String): FieldViolation? {
        if (disallowed.any { it.contentEquals(value) }) {
            return FieldViolation(field, "bytes.not_in", "must not be in the disallowed list")
        }
        return null
    }

    fun checkIp(value: ByteArray, field: String): FieldViolation? {
        if (value.size != 4 && value.size != 16) {
            return FieldViolation(field, "bytes.ip", "must be a 4-byte IPv4 or 16-byte IPv6 address")
        }
        return null
    }

    fun checkIpv4(value: ByteArray, field: String): FieldViolation? {
        if (value.size != 4) {
            return FieldViolation(field, "bytes.ipv4", "must be a 4-byte IPv4 address")
        }
        return null
    }

    fun checkIpv6(value: ByteArray, field: String): FieldViolation? {
        if (value.size != 16) {
            return FieldViolation(field, "bytes.ipv6", "must be a 16-byte IPv6 address")
        }
        return null
    }

    fun checkUuid(value: ByteArray, field: String): FieldViolation? {
        if (value.isEmpty()) {
            return FieldViolation(field, "bytes.uuid_empty", "must be a valid UUID")
        }
        if (value.size != 16) {
            return FieldViolation(field, "bytes.uuid", "must be a valid UUID")
        }
        return null
    }
}

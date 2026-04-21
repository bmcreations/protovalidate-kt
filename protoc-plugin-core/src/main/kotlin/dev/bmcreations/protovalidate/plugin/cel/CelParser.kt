package dev.bmcreations.protovalidate.plugin.cel

/**
 * AST nodes for the CEL subset used in buf validate conformance.
 */
sealed class CelExpr {
    data class Binary(val op: String, val left: CelExpr, val right: CelExpr) : CelExpr()
    data class Unary(val op: String, val operand: CelExpr) : CelExpr()
    data object This : CelExpr()
    data class FieldAccess(val receiver: CelExpr, val field: String) : CelExpr()
    data class IndexAccess(val receiver: CelExpr, val key: CelExpr) : CelExpr()
    data class Call(val function: String, val receiver: CelExpr?, val args: List<CelExpr>) : CelExpr()
    data class Literal(val value: Any) : CelExpr() // Int, Long, UInt, ULong, Double, String, Boolean
    data class Ident(val name: String) : CelExpr()
    data class Ternary(val cond: CelExpr, val then: CelExpr, val else_: CelExpr) : CelExpr()
    data class Comprehension(val op: String, val varName: String, val iter: CelExpr, val body: CelExpr) : CelExpr()
}

class CelParseException(message: String) : RuntimeException(message)
class CelRuntimeException(message: String) : RuntimeException(message)

object CelBuiltins {
    val COMPREHENSION_FUNCTIONS = setOf("all", "exists", "map", "filter", "exists_one")
    const val FN_LIST = "__list__"
    const val FN_FORMAT = "format"
    const val FN_SIZE = "size"
    const val FN_INT = "int"
    const val FN_UINT = "uint"
    const val FN_DOUBLE = "double"
    const val FN_STRING = "string"
    const val FN_BOOL = "bool"
    const val FN_MATCHES = "matches"
    const val FN_STARTS_WITH = "startsWith"
    const val FN_ENDS_WITH = "endsWith"
    const val FN_CONTAINS = "contains"
    const val FN_DURATION = "duration"
    const val FN_TIMESTAMP = "timestamp"
    const val FN_EXISTS = "exists"
    const val FN_ALL = "all"
    const val FN_HAS = "has"
    const val FN_DYN = "dyn"
    const val FN_TYPE = "type"
    const val FN_IS_HOSTNAME = "isHostname"
    const val FN_IS_EMAIL = "isEmail"
    const val FN_IS_URI = "isUri"
    const val FN_IS_URI_REF = "isUriRef"
    const val FN_IS_IP = "isIp"
    const val FN_IS_IP_PREFIX = "isIpPrefix"
    const val FN_IS_HOST_AND_PORT = "isHostAndPort"
}

/**
 * Recursive-descent parser for the CEL subset used in buf validate constraints.
 * Produces a [CelExpr] AST from a CEL expression string.
 */
class CelParser(private val source: String) {
    private var pos = 0

    fun parse(): CelExpr {
        skipWhitespace()
        val expr = parseExpression()
        skipWhitespace()
        if (pos < source.length) {
            throw CelParseException("Unexpected character at position $pos: '${source[pos]}' in: $source")
        }
        return expr
    }

    // ── Expression parsing with operator precedence ──

    private fun parseExpression(): CelExpr = parseTernary()

    private fun parseTernary(): CelExpr {
        val cond = parseOr()
        skipWhitespace()
        if (peek() == '?') {
            advance() // consume '?'
            skipWhitespace()
            val then = parseOr()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val else_ = parseTernary()
            return CelExpr.Ternary(cond, then, else_)
        }
        return cond
    }

    private fun parseOr(): CelExpr {
        var left = parseAnd()
        while (true) {
            skipWhitespace()
            if (matchStr("||")) {
                skipWhitespace()
                val right = parseAnd()
                left = CelExpr.Binary("||", left, right)
            } else break
        }
        return left
    }

    private fun parseAnd(): CelExpr {
        var left = parseComparison()
        while (true) {
            skipWhitespace()
            if (matchStr("&&")) {
                skipWhitespace()
                val right = parseComparison()
                left = CelExpr.Binary("&&", left, right)
            } else break
        }
        return left
    }

    private fun parseComparison(): CelExpr {
        var left = parseAddSub()
        skipWhitespace()
        // Handle "in" keyword as a binary operator
        if (matchKeyword("in")) {
            skipWhitespace()
            val right = parseAddSub()
            return CelExpr.Binary("in", left, right)
        }
        val op = when {
            matchStr("==") -> "=="
            matchStr("!=") -> "!="
            matchStr(">=") -> ">="
            matchStr("<=") -> "<="
            matchStr(">") -> ">"
            matchStr("<") -> "<"
            else -> return left
        }
        skipWhitespace()
        val right = parseAddSub()
        left = CelExpr.Binary(op, left, right)
        return left
    }

    private fun parseAddSub(): CelExpr {
        var left = parseMulDivMod()
        while (true) {
            skipWhitespace()
            val op = when {
                matchStr("+") -> "+"
                matchStr("-") && !(pos < source.length && source[pos].isDigit()) -> "-"
                else -> return left
            }
            skipWhitespace()
            val right = parseMulDivMod()
            left = CelExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseMulDivMod(): CelExpr {
        var left = parseUnary()
        while (true) {
            skipWhitespace()
            val op = when {
                matchStr("%") -> "%"
                matchStr("*") -> "*"
                matchStr("/") -> "/"
                else -> return left
            }
            skipWhitespace()
            val right = parseUnary()
            left = CelExpr.Binary(op, left, right)
        }
        return left
    }

    private fun parseUnary(): CelExpr {
        skipWhitespace()
        if (peek() == '!') {
            advance()
            skipWhitespace()
            val operand = parseUnary()
            return CelExpr.Unary("!", operand)
        }
        if (peek() == '-') {
            advance()
            skipWhitespace()
            val operand = parseUnary()
            return CelExpr.Unary("-", operand)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): CelExpr {
        var expr = parsePrimary()
        while (true) {
            skipWhitespace()
            when {
                peek() == '.' -> {
                    advance() // consume '.'
                    skipWhitespace()
                    val name = readIdentifier()
                    skipWhitespace()
                    if (peek() == '(') {
                        // Method call: expr.name(args)
                        advance() // consume '('
                        skipWhitespace()
                        // Check for comprehension-style calls: all(v, body), exists(v, body), map(v, body)
                        if (name in CelBuiltins.COMPREHENSION_FUNCTIONS) {
                            val result = parseComprehensionArgs(name, expr)
                            expect(')')
                            expr = result
                        } else {
                            val args = parseArgList()
                            expect(')')
                            expr = CelExpr.Call(name, expr, args)
                        }
                    } else {
                        expr = CelExpr.FieldAccess(expr, name)
                    }
                }
                peek() == '[' -> {
                    advance() // consume '['
                    skipWhitespace()
                    val key = parseExpression()
                    skipWhitespace()
                    expect(']')
                    expr = CelExpr.IndexAccess(expr, key)
                }
                else -> break
            }
        }
        return expr
    }

    private fun parsePrimary(): CelExpr {
        skipWhitespace()
        val c = peek()

        // Parenthesized expression
        if (c == '(') {
            advance()
            skipWhitespace()
            val inner = parseExpression()
            skipWhitespace()
            expect(')')
            return inner
        }

        // String literal (single-quoted)
        if (c == '\'') return parseStringLiteral()

        // String literal (double-quoted)
        if (c == '"') return parseDoubleQuotedStringLiteral()

        // List literal: [expr1, expr2, ...]
        if (c == '[') {
            advance() // consume '['
            skipWhitespace()
            val elements = mutableListOf<CelExpr>()
            if (peek() != ']') {
                elements.add(parseExpression())
                skipWhitespace()
                while (peek() == ',') {
                    advance()
                    skipWhitespace()
                    elements.add(parseExpression())
                    skipWhitespace()
                }
            }
            expect(']')
            return CelExpr.Call(CelBuiltins.FN_LIST, null, elements)
        }

        // Numeric literal
        if (c.isDigit()) return parseNumber()

        // Identifier or keyword
        if (c.isLetter() || c == '_') {
            val ident = readIdentifier()
            skipWhitespace()

            // Keywords
            when (ident) {
                "true" -> return CelExpr.Literal(true)
                "false" -> return CelExpr.Literal(false)
                "null" -> return CelExpr.Literal(Unit) // null sentinel
                "this" -> return CelExpr.This
            }

            // Global function call: ident(args)
            if (peek() == '(') {
                advance()
                skipWhitespace()
                // Check for comprehension-style calls used as global functions (unusual but possible)
                if (ident in CelBuiltins.COMPREHENSION_FUNCTIONS) {
                    val result = parseComprehensionArgs(ident, null)
                    expect(')')
                    return result
                }
                val args = parseArgList()
                expect(')')
                return CelExpr.Call(ident, null, args)
            }

            return CelExpr.Ident(ident)
        }

        throw CelParseException("Unexpected character '${if (pos < source.length) c else "EOF"}' at position $pos in: $source")
    }

    private fun parseComprehensionArgs(op: String, iter: CelExpr?): CelExpr {
        // Comprehension: iter.op(varName, bodyExpr)
        val varName = readIdentifier()
        skipWhitespace()
        expect(',')
        skipWhitespace()
        val body = parseExpression()
        skipWhitespace()
        return if (iter != null) {
            CelExpr.Comprehension(op, varName, iter, body)
        } else {
            // Shouldn't happen in practice, but handle gracefully
            CelExpr.Call(op, null, listOf(CelExpr.Ident(varName), body))
        }
    }

    private fun parseArgList(): List<CelExpr> {
        val args = mutableListOf<CelExpr>()
        if (peek() == ')') return args
        args.add(parseExpression())
        while (true) {
            skipWhitespace()
            if (peek() == ',') {
                advance()
                skipWhitespace()
                args.add(parseExpression())
            } else break
        }
        return args
    }

    private fun parseStringLiteral(): CelExpr {
        expect('\'')
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '\'') {
            if (source[pos] == '\\') {
                pos++
                if (pos >= source.length) throw CelParseException("Unterminated string escape in: $source")
                when (source[pos]) {
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> {
                        sb.append('\\')
                        sb.append(source[pos])
                    }
                }
            } else {
                sb.append(source[pos])
            }
            pos++
        }
        expect('\'')
        return CelExpr.Literal(sb.toString())
    }

    private fun parseDoubleQuotedStringLiteral(): CelExpr {
        expect('"')
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != '"') {
            if (source[pos] == '\\') {
                pos++
                if (pos >= source.length) throw CelParseException("Unterminated string escape in: $source")
                when (source[pos]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> {
                        sb.append('\\')
                        sb.append(source[pos])
                    }
                }
            } else {
                sb.append(source[pos])
            }
            pos++
        }
        expect('"')
        return CelExpr.Literal(sb.toString())
    }

    private fun parseNumber(): CelExpr {
        val start = pos
        while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) {
            pos++
        }
        val numStr = source.substring(start, pos)

        // Check for 'u' suffix (unsigned)
        if (pos < source.length && source[pos] == 'u') {
            pos++ // consume 'u'
            return if ('.' in numStr) {
                CelExpr.Literal(numStr.toDouble()) // shouldn't happen, but safe
            } else {
                val value = numStr.toLong()
                CelExpr.Literal(ULong(value))
            }
        }

        return if ('.' in numStr) {
            CelExpr.Literal(numStr.toDouble())
        } else {
            val value = numStr.toLong()
            if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                CelExpr.Literal(value.toInt())
            } else {
                CelExpr.Literal(value)
            }
        }
    }

    // ── Helpers ──

    private fun peek(): Char = if (pos < source.length) source[pos] else '\u0000'

    private fun advance(): Char {
        if (pos >= source.length) throw CelParseException("Unexpected end of expression: $source")
        return source[pos++]
    }

    private fun expect(c: Char) {
        if (pos >= source.length || source[pos] != c) {
            throw CelParseException("Expected '$c' at position $pos but got '${if (pos < source.length) source[pos] else "EOF"}' in: $source")
        }
        pos++
    }

    private fun matchStr(s: String): Boolean {
        if (source.startsWith(s, pos)) {
            // For multi-char operators, ensure we're not matching a prefix of a longer operator
            if (s.length == 1 && s[0] in setOf('>', '<')) {
                if (pos + 1 < source.length && source[pos + 1] == '=') return false
            }
            // For single '|' and '&', don't match partial || or &&
            if (s == "|" && pos + 1 < source.length && source[pos + 1] == '|') return false
            if (s == "&" && pos + 1 < source.length && source[pos + 1] == '&') return false
            pos += s.length
            return true
        }
        return false
    }

    private fun matchKeyword(keyword: String): Boolean {
        if (source.startsWith(keyword, pos)) {
            val endPos = pos + keyword.length
            // Ensure it's not a prefix of a longer identifier
            if (endPos < source.length && (source[endPos].isLetterOrDigit() || source[endPos] == '_')) {
                return false
            }
            pos = endPos
            return true
        }
        return false
    }

    private fun readIdentifier(): String {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
            pos++
        }
        if (pos == start) throw CelParseException("Expected identifier at position $pos in: $source")
        return source.substring(start, pos)
    }

    private fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) {
            pos++
        }
    }
}

/**
 * Wrapper type to distinguish unsigned longs in the AST.
 */
data class ULong(val value: Long)

package dev.bmcreations.protovalidate.plugin.cel

import dev.bmcreations.protovalidate.plugin.escapeForKotlinString

/**
 * Context for CEL-to-Kotlin transpilation.
 *
 * @param thisAccessor Kotlin expression for `this` in the CEL context
 * @param ruleValue Kotlin literal expression for `rule` (predefined rules only)
 * @param fieldType Hint about the CEL `this` type for correct Kotlin emission
 */
data class CelContext(
    val thisAccessor: String,
    val ruleValue: String? = null,
    val fieldType: CelFieldType = CelFieldType.UNKNOWN,
    /** For MAP fields: the type of the map values */
    val mapValueType: CelFieldType = CelFieldType.UNKNOWN,
    /** For REPEATED fields: the type of the list elements */
    val elementType: CelFieldType = CelFieldType.UNKNOWN,
)

enum class CelFieldType {
    INT32, INT64, UINT32, UINT64,
    SINT32, SINT64, FIXED32, FIXED64, SFIXED32, SFIXED64,
    FLOAT, DOUBLE, STRING, BYTES, BOOL, ENUM,
    DURATION, TIMESTAMP,
    REPEATED, MAP, MESSAGE,
    UNKNOWN
}

/**
 * Transpiles a parsed [CelExpr] AST to a Kotlin boolean expression string.
 *
 * The resulting expression evaluates to `true` when the constraint is satisfied (valid).
 */
object CelTranspiler {

    /**
     * Transpiles a CEL expression to Kotlin code that evaluates the constraint.
     * Returns a Kotlin expression that is `true` when valid.
     *
     * For ternary expressions of the form `expr ? '' : 'msg'` (CEL violation idiom),
     * returns the condition directly (the `expr` part) since empty string means valid.
     */
    fun transpile(expr: CelExpr, ctx: CelContext): String {
        return emitExpr(expr, ctx)
    }

    /**
     * Determines whether a CEL expression uses the ternary violation pattern
     * (`expr ? '' : 'message'`), and if so returns the condition expression
     * that must be true for validity. Otherwise returns null.
     */
    fun extractTernaryCondition(expr: CelExpr, ctx: CelContext): String? {
        if (expr is CelExpr.Ternary) {
            val thenVal = tryLiteralString(expr.then)
            if (thenVal == "") {
                // `cond ? '' : 'error'` → valid when cond is true
                return emitExpr(expr.cond, ctx)
            }
            val elseVal = tryLiteralString(expr.else_)
            if (elseVal == "") {
                // `cond ? 'error' : ''` → valid when cond is FALSE (negate)
                return "!(${emitExpr(expr.cond, ctx)})"
            }
        }
        return null
    }

    /**
     * Extracts the violation message from a ternary CEL expression.
     * For `expr ? '' : 'msg'` returns "msg". For `expr ? '' : 'fmt'.format([args])` returns
     * a Kotlin string-format expression.
     * Also handles the inverted pattern: `expr ? 'msg' : ''`.
     */
    fun extractTernaryMessage(expr: CelExpr, ctx: CelContext): String? {
        if (expr is CelExpr.Ternary) {
            val thenVal = tryLiteralString(expr.then)
            if (thenVal == "") {
                return emitMessageExpr(expr.else_, ctx)
            }
            val elseVal = tryLiteralString(expr.else_)
            if (elseVal == "") {
                // Inverted: error message is in `then`
                return emitMessageExpr(expr.then, ctx)
            }
        }
        return null
    }

    /**
     * Determines whether the expression returns a string (violation pattern where
     * non-empty string = error message).
     */
    fun isStringReturning(expr: CelExpr): Boolean {
        return when (expr) {
            is CelExpr.Ternary -> {
                val thenStr = tryLiteralString(expr.then)
                val elseStr = tryLiteralString(expr.else_)
                thenStr != null || elseStr != null
            }
            is CelExpr.Literal -> expr.value is String
            is CelExpr.Call -> expr.function == CelBuiltins.FN_FORMAT
            else -> false
        }
    }

    /**
     * Returns true if this CEL expression references `now`.
     */
    fun referencesNow(expr: CelExpr): Boolean = when (expr) {
        is CelExpr.Ident -> expr.name == "now"
        is CelExpr.Binary -> referencesNow(expr.left) || referencesNow(expr.right)
        is CelExpr.Unary -> referencesNow(expr.operand)
        is CelExpr.FieldAccess -> referencesNow(expr.receiver)
        is CelExpr.IndexAccess -> referencesNow(expr.receiver) || referencesNow(expr.key)
        is CelExpr.Call -> (expr.receiver != null && referencesNow(expr.receiver)) || expr.args.any { referencesNow(it) }
        is CelExpr.Ternary -> referencesNow(expr.cond) || referencesNow(expr.then) || referencesNow(expr.else_)
        is CelExpr.Comprehension -> referencesNow(expr.iter) || referencesNow(expr.body)
        else -> false
    }

    // ── Internal emission ──

    private fun emitExpr(expr: CelExpr, ctx: CelContext): String = when (expr) {
        is CelExpr.This -> ctx.thisAccessor
        is CelExpr.Ident -> emitIdent(expr, ctx)
        is CelExpr.Literal -> emitLiteral(expr, ctx)
        is CelExpr.Binary -> emitBinary(expr, ctx)
        is CelExpr.Unary -> emitUnary(expr, ctx)
        is CelExpr.FieldAccess -> emitFieldAccess(expr, ctx)
        is CelExpr.IndexAccess -> emitIndexAccess(expr, ctx)
        is CelExpr.Call -> emitCall(expr, ctx)
        is CelExpr.Ternary -> emitTernary(expr, ctx)
        is CelExpr.Comprehension -> emitComprehension(expr, ctx)
    }

    private fun emitIdent(expr: CelExpr.Ident, ctx: CelContext): String = when (expr.name) {
        "rule" -> ctx.ruleValue ?: "rule"
        "rules" -> "rules"
        "now" -> "_celNow"
        else -> expr.name
    }

    private fun emitLiteral(expr: CelExpr.Literal, ctx: CelContext): String = when (val v = expr.value) {
        is String -> "\"${escapeForKotlinString(v)}\""
        is Boolean -> v.toString()
        is Int -> v.toString()
        is Long -> "${v}L"
        is Double -> {
            if (v == v.toLong().toDouble() && !v.isInfinite() && !v.isNaN()) {
                "${v.toLong()}.0"
            } else v.toString()
        }
        is ULong -> {
            // Strip 'u' suffix semantics: treat as regular number for Kotlin emission
            // since Kotlin doesn't have unsigned literal suffix in the same way
            "${v.value}"
        }
        is Unit -> "null"
        else -> v.toString()
    }

    private fun emitBinary(expr: CelExpr.Binary, ctx: CelContext): String {
        val left = emitExpr(expr.left, ctx)
        val right = emitExpr(expr.right, ctx)
        return when (expr.op) {
            "in" -> "$right.contains($left)"
            "+" -> "($left + $right)"
            "-" -> "($left - $right)"
            "*" -> "($left * $right)"
            "/" -> "($left / $right)"
            "%" -> "($left % $right)"
            "&&" -> "($left && $right)"
            "||" -> "($left || $right)"
            "==", "!=", "<", "<=", ">", ">=" -> {
                // Duration comparison: compare as (seconds, nanos) pairs
                if (ctx.fieldType == CelFieldType.DURATION && isDurationExpr(expr.left, ctx) && isDurationExpr(expr.right, ctx)) {
                    emitDurationComparison(left, right, expr.op)
                } else {
                    // Handle type coercion for comparisons involving map index access
                    val coercedLeft = coerceForComparison(left, expr.left, expr.right, ctx)
                    val coercedRight = coerceForComparison(right, expr.right, expr.left, ctx)
                    "($coercedLeft ${expr.op} $coercedRight)"
                }
            }
            else -> "($left ${expr.op} $right)"
        }
    }

    /**
     * Coerces a value for comparison when the other side is a map index access or
     * typed differently. Handles enum→number conversion and Long/Int coercion.
     */
    private fun coerceForComparison(
        emitted: String,
        thisExpr: CelExpr,
        otherExpr: CelExpr,
        ctx: CelContext
    ): String {
        // If the other side is a map index access and this side is a plain integer literal,
        // apply correct coercion based on the map value type
        if (otherExpr is CelExpr.IndexAccess && thisExpr is CelExpr.Literal) {
            val v = thisExpr.value
            if (v is Int) {
                return when (ctx.mapValueType) {
                    CelFieldType.INT64, CelFieldType.UINT64,
                    CelFieldType.SINT64, CelFieldType.FIXED64, CelFieldType.SFIXED64 -> "${v}.toLong()"
                    CelFieldType.ENUM -> "${v}" // Enum side gets .number instead
                    else -> "${v}" // Int32, Uint32 etc — keep as Int
                }
            }
        }
        // If this side is a uint() or int() cast and the other side is a map index access
        // with a 32-bit value type, downcast from toLong() to toInt()
        if (thisExpr is CelExpr.Call && thisExpr.function in listOf(CelBuiltins.FN_UINT, CelBuiltins.FN_INT) && otherExpr is CelExpr.IndexAccess) {
            if (ctx.mapValueType in listOf(CelFieldType.INT32, CelFieldType.UINT32, CelFieldType.SINT32,
                    CelFieldType.FIXED32, CelFieldType.SFIXED32)) {
                // Replace .toLong() with .toInt() in the emitted string
                return emitted.replace(").toLong()", ").toInt()")
            }
        }
        // If this side is an index access on a map with enum values, add .number
        if (thisExpr is CelExpr.IndexAccess && ctx.mapValueType == CelFieldType.ENUM) {
            if (otherExpr is CelExpr.Literal && (otherExpr.value is Int || otherExpr.value is Long)) {
                return "$emitted.number"
            }
            // Also handle uint()/int() calls on the other side
            if (otherExpr is CelExpr.Call && otherExpr.function in listOf(CelBuiltins.FN_UINT, CelBuiltins.FN_INT)) {
                return "$emitted.number"
            }
        }
        // If this side is an ident (loop var) in a comprehension over a repeated enum field,
        // and the other side is an int literal → add .number
        if (thisExpr is CelExpr.Ident && ctx.elementType == CelFieldType.ENUM) {
            if (otherExpr is CelExpr.Literal && (otherExpr.value is Int || otherExpr.value is Long)) {
                return "$emitted.number"
            }
        }
        // If this side is an int/uint literal and the other side is `this` (or derived from this)
        // and the field type is 64-bit → add .toLong()
        if (thisExpr is CelExpr.Literal && (thisExpr.value is Int || thisExpr.value is ULong)) {
            val is64bit = ctx.fieldType in listOf(
                CelFieldType.INT64, CelFieldType.UINT64, CelFieldType.SINT64,
                CelFieldType.FIXED64, CelFieldType.SFIXED64
            )
            if (is64bit && (otherExpr is CelExpr.This || otherExpr is CelExpr.Binary)) {
                val v = when (thisExpr.value) {
                    is ULong -> (thisExpr.value as ULong).value
                    else -> thisExpr.value
                }
                return "${v}.toLong()"
            }
        }
        return emitted
    }

    private fun emitUnary(expr: CelExpr.Unary, ctx: CelContext): String {
        val operand = emitExpr(expr.operand, ctx)
        return when (expr.op) {
            "!" -> "!($operand)"
            "-" -> "-($operand)"
            else -> "${expr.op}($operand)"
        }
    }

    private fun emitFieldAccess(expr: CelExpr.FieldAccess, ctx: CelContext): String {
        val receiver = emitExpr(expr.receiver, ctx)
        // Convert snake_case proto field names to camelCase for protobuf-java accessors
        val camelField = snakeToCamelLower(expr.field)
        val field = escapeKotlinKeyword(camelField)
        return "$receiver.$field"
    }

    private fun snakeToCamelLower(name: String): String {
        if (!name.contains('_')) return name
        val parts = name.split('_')
        return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private val KOTLIN_KEYWORDS = setOf(
        "val", "var", "class", "object", "fun", "interface", "package", "import",
        "return", "throw", "try", "catch", "finally", "if", "else", "when",
        "while", "for", "do", "break", "continue", "is", "in", "as", "null",
        "true", "false", "this", "super", "typeof", "typealias", "data",
        "sealed", "abstract", "open", "inner", "override", "private", "protected",
        "public", "internal", "lateinit", "companion", "enum", "annotation",
        "suspend", "tailrec", "operator", "inline", "infix", "external",
        "const", "crossinline", "noinline", "reified", "vararg", "out",
    )

    private fun escapeKotlinKeyword(name: String): String {
        return if (name in KOTLIN_KEYWORDS) "`$name`" else name
    }

    private fun emitIndexAccess(expr: CelExpr.IndexAccess, ctx: CelContext): String {
        val receiver = emitExpr(expr.receiver, ctx)
        val key = emitExpr(expr.key, ctx)
        // Map access in Kotlin is nullable; use !! since CEL assumes the key exists
        // We also need type coercion for enum and numeric comparisons, so we use
        // a helper that gets the "CEL value" (enum → number, message → as-is)
        return "$receiver[$key]!!"
    }

    private fun emitCall(expr: CelExpr.Call, ctx: CelContext): String {
        val receiver = expr.receiver?.let { emitExpr(it, ctx) }
        val args = expr.args.map { emitExpr(it, ctx) }

        return when (expr.function) {
            // List literal: [a, b, c] → listOf(a, b, c)
            CelBuiltins.FN_LIST -> "listOf(${args.joinToString(", ")})"

            // String format: 'pattern'.format([args]) → String.format(pattern, *args)
            CelBuiltins.FN_FORMAT -> {
                val fmtStr = receiver ?: "\"\""
                // The args to format are typically a single list literal [a, b, c]
                // Extract elements from the list for varargs
                val formatArgs = if (expr.args.size == 1 && expr.args[0] is CelExpr.Call &&
                    (expr.args[0] as CelExpr.Call).function == CelBuiltins.FN_LIST) {
                    (expr.args[0] as CelExpr.Call).args.map { emitExpr(it, ctx) }
                } else {
                    args
                }
                "String.format($fmtStr, ${formatArgs.joinToString(", ")})"
            }

            // Type casts
            CelBuiltins.FN_INT -> {
                val arg = args.firstOrNull() ?: receiver ?: "0"
                emitIntCast(arg, expr.args.firstOrNull() ?: expr.receiver, ctx)
            }
            CelBuiltins.FN_UINT -> {
                val arg = args.firstOrNull() ?: receiver ?: "0"
                // uint() in CEL casts to unsigned - emit as Long for Kotlin compatibility
                "($arg).toLong()"
            }
            CelBuiltins.FN_DOUBLE -> {
                val arg = args.firstOrNull() ?: receiver ?: "0.0"
                "($arg).toDouble()"
            }
            CelBuiltins.FN_STRING -> {
                val arg = args.firstOrNull() ?: receiver ?: "\"\""
                emitStringCast(arg, expr.args.firstOrNull() ?: expr.receiver, ctx)
            }
            CelBuiltins.FN_BOOL -> {
                val arg = args.firstOrNull() ?: receiver ?: "false"
                arg
            }

            // Size
            CelBuiltins.FN_SIZE -> {
                if (receiver != null) {
                    emitSize(receiver, expr.receiver, ctx)
                } else {
                    val arg = args.firstOrNull() ?: "0"
                    "$arg.size"
                }
            }

            // String operations
            CelBuiltins.FN_MATCHES -> {
                val pattern = args.firstOrNull() ?: "\"\""
                "Regex($pattern).containsMatchIn($receiver)"
            }
            CelBuiltins.FN_STARTS_WITH -> "$receiver.startsWith(${args.first()})"
            CelBuiltins.FN_ENDS_WITH -> "$receiver.endsWith(${args.first()})"
            CelBuiltins.FN_CONTAINS -> "$receiver.contains(${args.first()})"

            // Duration
            CelBuiltins.FN_DURATION -> {
                val arg = args.firstOrNull() ?: "\"0s\""
                emitDurationLiteral(arg)
            }

            // Timestamp
            CelBuiltins.FN_TIMESTAMP -> {
                val arg = args.firstOrNull() ?: "\"0\""
                arg // simplified
            }

            // Collection: exists, all handled via Comprehension node mostly,
            // but in case they appear as Call nodes:
            CelBuiltins.FN_EXISTS -> "$receiver.any { ${args.joinToString()} }"
            CelBuiltins.FN_ALL -> "$receiver.all { ${args.joinToString()} }"

            // Library validation functions → delegate to runtime StringValidators
            CelBuiltins.FN_IS_HOSTNAME -> "(dev.bmcreations.protovalidate.StringValidators.checkHostname($receiver, \"\") == null)"
            CelBuiltins.FN_IS_EMAIL -> "(dev.bmcreations.protovalidate.StringValidators.checkEmail($receiver, \"\") == null)"
            CelBuiltins.FN_IS_URI -> "(dev.bmcreations.protovalidate.StringValidators.checkUri($receiver, \"\") == null)"
            CelBuiltins.FN_IS_URI_REF -> "(dev.bmcreations.protovalidate.StringValidators.checkUriRef($receiver, \"\") == null)"
            CelBuiltins.FN_IS_IP -> if (args.isEmpty()) {
                "(dev.bmcreations.protovalidate.StringValidators.isValidIpLibrary($receiver, 0))"
            } else {
                "(dev.bmcreations.protovalidate.StringValidators.isValidIpLibrary($receiver, ${args[0]}))"
            }
            CelBuiltins.FN_IS_IP_PREFIX -> if (args.isEmpty()) {
                "(dev.bmcreations.protovalidate.StringValidators.checkIpPrefix($receiver, \"\") == null)"
            } else if (args.size == 1) {
                // Determine if the single arg is version (Int) or strict (Boolean) based on the AST
                val argExpr = expr.args.firstOrNull()
                val isBoolArg = argExpr is CelExpr.Literal && argExpr.value is Boolean ||
                    (argExpr is CelExpr.FieldAccess && argExpr.field in listOf("strict", "port_required"))
                if (isBoolArg) {
                    "(dev.bmcreations.protovalidate.StringValidators.checkIpPrefix($receiver, \"\", strict = ${args[0]}) == null)"
                } else {
                    "(dev.bmcreations.protovalidate.StringValidators.checkIpPrefix($receiver, \"\", ${args[0]}) == null)"
                }
            } else {
                "(dev.bmcreations.protovalidate.StringValidators.checkIpPrefix($receiver, \"\", ${args[0]}, ${args[1]}) == null)"
            }
            CelBuiltins.FN_IS_HOST_AND_PORT -> if (args.isEmpty()) {
                "(dev.bmcreations.protovalidate.StringValidators.checkHostAndPort($receiver, \"\") == null)"
            } else {
                "(dev.bmcreations.protovalidate.StringValidators.checkHostAndPort($receiver, \"\", ${args[0]}) == null)"
            }

            // has(expr.field) → expr.hasField() — checks protobuf field presence
            CelBuiltins.FN_HAS -> {
                val arg = expr.args.firstOrNull()
                if (arg is CelExpr.FieldAccess) {
                    val recv = emitExpr(arg.receiver, ctx)
                    val camelField = arg.field.split("_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
                    if (recv == "this") {
                        "has${camelField}()"
                    } else {
                        "$recv.has${camelField}()"
                    }
                } else {
                    throw CelParseException("unsupported has() argument")
                }
            }

            // dyn() → runtime type access that we can't resolve statically
            CelBuiltins.FN_DYN -> throw CelRuntimeException("dynamic type field access results in runtime type error")

            // Known unsupported functions → throw to signal CompilationError
            CelBuiltins.FN_TYPE ->
                throw CelParseException("unsupported CEL function: ${expr.function}")

            // Generic method call — fallback for any other function
            else -> {
                if (receiver != null) {
                    "$receiver.${expr.function}(${args.joinToString(", ")})"
                } else {
                    "${expr.function}(${args.joinToString(", ")})"
                }
            }
        }
    }

    private fun emitIntCast(arg: String, argExpr: CelExpr?, ctx: CelContext): String {
        // int(this) on a timestamp → seconds, on an enum → numeric value (already converted by accessor)
        if (argExpr is CelExpr.This) {
            return when (ctx.fieldType) {
                CelFieldType.TIMESTAMP -> "${ctx.thisAccessor}.seconds"
                CelFieldType.ENUM -> ctx.thisAccessor // accessor already points to the numeric value
                CelFieldType.DURATION -> "${ctx.thisAccessor}.seconds"
                else -> arg
            }
        }
        // int(expr) on other expressions
        return "($arg).toLong()"
    }

    private fun emitStringCast(arg: String, argExpr: CelExpr?, ctx: CelContext): String {
        // string(this) on bytes → String(bytes)
        if (argExpr is CelExpr.This && ctx.fieldType == CelFieldType.BYTES) {
            return "${ctx.thisAccessor}.toStringUtf8()"
        }
        return "($arg).toString()"
    }

    private fun emitSize(receiver: String, receiverExpr: CelExpr?, ctx: CelContext): String {
        // .size() on string → .length, on bytes → .size(), on collection → .size
        if (receiverExpr is CelExpr.This) {
            return when (ctx.fieldType) {
                CelFieldType.STRING -> "$receiver.length"
                CelFieldType.BYTES -> "$receiver.size()"
                CelFieldType.REPEATED -> "${receiver}.size"
                CelFieldType.MAP -> "${receiver}.size"
                else -> "$receiver.size"
            }
        }
        return "$receiver.size"
    }

    private fun emitTernary(expr: CelExpr.Ternary, ctx: CelContext): String {
        val cond = emitExpr(expr.cond, ctx)
        val then = emitExpr(expr.then, ctx)
        val else_ = emitExpr(expr.else_, ctx)
        return "(if ($cond) $then else $else_)"
    }

    private fun emitComprehension(expr: CelExpr.Comprehension, ctx: CelContext): String {
        val iter = emitExpr(expr.iter, ctx)

        // For map types, comprehensions iterate over keys (CEL maps iterate keys by default)
        val isMapIter = ctx.fieldType == CelFieldType.MAP &&
            (expr.iter is CelExpr.This || expr.iter is CelExpr.Ident)
        val iterExpr = if (isMapIter) "$iter.keys" else iter

        // Propagate type context for the body:
        // - For repeated fields, the loop var is an element → set elementType
        // - For map fields iterating keys, the body may index back into the map → keep mapValueType
        val bodyCtx = if (!isMapIter && ctx.fieldType == CelFieldType.REPEATED) {
            ctx.copy(elementType = ctx.elementType)
        } else {
            ctx
        }

        // Within the body, the loop variable refers to each element
        val bodyWithVar = replaceIdent(expr.body, expr.varName, bodyCtx)

        return when (expr.op) {
            "all" -> "$iterExpr.all { ${expr.varName} -> $bodyWithVar }"
            "exists" -> "$iterExpr.any { ${expr.varName} -> $bodyWithVar }"
            "map" -> "$iterExpr.map { ${expr.varName} -> $bodyWithVar }"
            "filter" -> "$iterExpr.filter { ${expr.varName} -> $bodyWithVar }"
            "exists_one" -> "$iterExpr.count { ${expr.varName} -> $bodyWithVar } == 1"
            else -> "$iterExpr.${expr.op} { ${expr.varName} -> $bodyWithVar }"
        }
    }

    /**
     * Emits the body of a comprehension, properly resolving the loop variable.
     */
    private fun replaceIdent(body: CelExpr, varName: String, ctx: CelContext): String {
        // Create a context where the loop variable is available as-is
        // The body already uses Ident(varName), which will emit as the variable name
        return emitExpr(body, ctx)
    }

    private fun emitDurationLiteral(arg: String): String {
        // duration('10s') → parse to seconds
        // arg is a Kotlin string literal like "\"10s\""
        val stripped = arg.removeSurrounding("\"")
        val match = Regex("""^(-?)(\d+(?:\.\d+)?)s$""").find(stripped)
        if (match != null) {
            val sign = match.groupValues[1]
            val value = match.groupValues[2]
            return if ('.' in value) {
                val seconds = value.toDouble()
                val totalNanos = (seconds * 1_000_000_000).toLong()
                val secs = totalNanos / 1_000_000_000
                val nanos = (totalNanos % 1_000_000_000).toInt()
                "com.google.protobuf.Duration.newBuilder().setSeconds(${sign}${secs}L).setNanos(${sign}${nanos}).build()"
            } else {
                "com.google.protobuf.Duration.newBuilder().setSeconds(${sign}${value}L).setNanos(0).build()"
            }
        }
        // Fallback: try to parse as-is
        return "com.google.protobuf.Duration.getDefaultInstance() /* unparseable: $arg */"
    }

    private fun emitMessageExpr(expr: CelExpr, ctx: CelContext): String {
        // For the message part of a ternary, try to extract a constant string
        return when (expr) {
            is CelExpr.Literal -> when (val v = expr.value) {
                is String -> v
                else -> v.toString()
            }
            is CelExpr.Call -> {
                if (expr.function == CelBuiltins.FN_FORMAT) {
                    // 'fmt'.format([args]) → String.format(fmt, arg1, arg2, ...)
                    val fmtStr = expr.receiver?.let { emitExpr(it, ctx) } ?: "\"\""
                    val formatArgs = if (expr.args.size == 1 && expr.args[0] is CelExpr.Call &&
                        (expr.args[0] as CelExpr.Call).function == CelBuiltins.FN_LIST) {
                        (expr.args[0] as CelExpr.Call).args.map { emitExpr(it, ctx) }
                    } else {
                        expr.args.map { emitExpr(it, ctx) }
                    }
                    return "\${String.format($fmtStr, ${formatArgs.joinToString(", ")})}"
                }
                emitExpr(expr, ctx)
            }
            else -> emitExpr(expr, ctx)
        }
    }

    /**
     * Extracts all top-level field accesses on `this` from a CEL expression.
     * Returns field names accessed as `this.fieldName`.
     */
    fun extractThisFieldRefs(expr: CelExpr): Set<String> {
        val refs = mutableSetOf<String>()
        collectThisFieldRefs(expr, refs)
        return refs
    }

    private fun collectThisFieldRefs(expr: CelExpr, refs: MutableSet<String>) {
        when (expr) {
            is CelExpr.FieldAccess -> {
                if (expr.receiver is CelExpr.This) {
                    refs.add(expr.field)
                } else {
                    collectThisFieldRefs(expr.receiver, refs)
                }
            }
            is CelExpr.Binary -> {
                collectThisFieldRefs(expr.left, refs)
                collectThisFieldRefs(expr.right, refs)
            }
            is CelExpr.Unary -> collectThisFieldRefs(expr.operand, refs)
            is CelExpr.IndexAccess -> {
                collectThisFieldRefs(expr.receiver, refs)
                collectThisFieldRefs(expr.key, refs)
            }
            is CelExpr.Call -> {
                expr.receiver?.let { collectThisFieldRefs(it, refs) }
                expr.args.forEach { collectThisFieldRefs(it, refs) }
            }
            is CelExpr.Ternary -> {
                collectThisFieldRefs(expr.cond, refs)
                collectThisFieldRefs(expr.then, refs)
                collectThisFieldRefs(expr.else_, refs)
            }
            is CelExpr.Comprehension -> {
                collectThisFieldRefs(expr.iter, refs)
                collectThisFieldRefs(expr.body, refs)
            }
            else -> {}
        }
    }

    // ── Duration comparison ──

    private fun isDurationExpr(expr: CelExpr, ctx: CelContext): Boolean = when (expr) {
        is CelExpr.This -> ctx.fieldType == CelFieldType.DURATION
        is CelExpr.Ident -> expr.name == "rule" || expr.name == "now"
        is CelExpr.Call -> expr.function == CelBuiltins.FN_DURATION
        is CelExpr.Unary -> isDurationExpr(expr.operand, ctx)
        else -> false
    }

    private fun emitDurationComparison(left: String, right: String, op: String): String {
        // Compare durations by (seconds, nanos) — inline comparison
        val cmpExpr = "compareValuesBy($left, $right, { it.seconds }, { it.nanos })"
        return when (op) {
            "==" -> "($cmpExpr == 0)"
            "!=" -> "($cmpExpr != 0)"
            "<" -> "($cmpExpr < 0)"
            "<=" -> "($cmpExpr <= 0)"
            ">" -> "($cmpExpr > 0)"
            ">=" -> "($cmpExpr >= 0)"
            else -> "($left $op $right)"
        }
    }

    // ── Utilities ──

    private fun tryLiteralString(expr: CelExpr): String? {
        return if (expr is CelExpr.Literal && expr.value is String) expr.value else null
    }
}

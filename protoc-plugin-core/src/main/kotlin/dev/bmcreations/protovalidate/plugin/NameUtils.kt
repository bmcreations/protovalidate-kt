package dev.bmcreations.protovalidate.plugin

fun snakeToCamel(name: String): String =
    name.split("_").joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while"
)

fun snakeToCamelLower(name: String): String {
    val camel = snakeToCamel(name)
    return camel.replaceFirstChar { it.lowercaseChar() }
}

fun escapeIfKeyword(identifier: String): String =
    if (identifier in KOTLIN_KEYWORDS) "`$identifier`" else identifier

fun escapeForKotlinString(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\$", "\\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

fun outerClassNameFromFileName(fileName: String): String {
    val baseName = fileName.substringAfterLast("/").removeSuffix(".proto")
    return snakeToCamel(baseName)
}

fun bytesToKotlinLiteral(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "byteArrayOf()"
    return bytes.joinToString(", ", "byteArrayOf(") { b ->
        val unsigned = b.toInt() and 0xFF
        val hex = "0x${String.format("%02x", unsigned)}"
        if (unsigned > 0x7F) "${hex}.toByte()" else hex
    } + ")"
}

package dev.bmcreations.protovalidate

data class FieldViolation(
    val field: String,
    val rule: String,
    val message: String,
    val forKey: Boolean = false,
    val celIndex: Int = -1,
    val isCelExpression: Boolean = false,
    val isMessageLevelCel: Boolean = false,
)

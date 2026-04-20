package dev.bmcreations.protovalidate.conformance.pgv

import dev.bmcreations.protovalidate.ValidationResult
import com.google.protobuf.Any
import com.google.protobuf.Message
import tests.harness.TestCase
import tests.harness.TestResult

fun main() {
    val input = System.`in`.readBytes()
    val testCase = TestCase.parseFrom(input)

    val result = executeCase(testCase.message)
    result.writeTo(System.out)
    System.out.flush()
}

private fun executeCase(anyMsg: Any): TestResult {
    return try {
        val typeName = anyMsg.typeUrl.substringAfterLast('/')

        val messageClass = resolveMessageClass(typeName)
            ?: return TestResult.newBuilder()
                .setError(true)
                .addReasons("Unknown type: $typeName")
                .build()

        val parseFrom = messageClass.getMethod("parseFrom", ByteArray::class.java)
        val message = parseFrom.invoke(null, anyMsg.value.toByteArray()) as Message

        // Find the validator extension function
        val validatorClass = resolveValidatorClass(typeName)
            ?: return TestResult.newBuilder()
                .setValid(true) // No validator means no constraints
                .build()

        val validateMethod = validatorClass.methods.find {
            it.name == "validate" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(messageClass)
        } ?: return TestResult.newBuilder()
            .setValid(true) // No validate method means no constraints
            .build()

        val result = validateMethod.invoke(null, message) as ValidationResult

        when (result) {
            is ValidationResult.Valid -> TestResult.newBuilder()
                .setValid(true)
                .build()

            is ValidationResult.Invalid -> TestResult.newBuilder()
                .setValid(false)
                .addAllReasons(result.violations.map { "${it.field}: ${it.message}" })
                .build()
        }
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException
        TestResult.newBuilder()
            .setError(true)
            .addReasons("Validation threw: ${cause.javaClass.simpleName}: ${cause.message}")
            .build()
    } catch (e: Exception) {
        TestResult.newBuilder()
            .setError(true)
            .addReasons("${e.javaClass.simpleName}: ${e.message}")
            .build()
    }
}

private fun resolveMessageClass(protoTypeName: String): Class<*>? {
    // Try direct class name
    tryLoadClass(protoTypeName)?.let { return it }
    // Split on dots and try package.OuterClass$Inner patterns
    val parts = protoTypeName.split('.')
    for (i in parts.size - 1 downTo 1) {
        val pkg = parts.subList(0, i).joinToString(".")
        val nested = parts.subList(i, parts.size).joinToString("$")
        tryLoadClass("$pkg.$nested")?.let { return it }
    }
    return null
}

private fun resolveValidatorClass(protoTypeName: String): Class<*>? {
    val parts = protoTypeName.split('.')
    val messageName = parts.last()
    val pkg = parts.dropLast(1).joinToString(".")
    tryLoadClass("$pkg.${messageName}ValidatorKt")?.let { return it }
    // Try nested message patterns
    if (parts.size > 1) {
        for (i in parts.size - 2 downTo 1) {
            val candidatePkg = parts.subList(0, i).joinToString(".")
            val messageNames = parts.subList(i, parts.size).joinToString("")
            tryLoadClass("$candidatePkg.${messageNames}ValidatorKt")?.let { return it }
        }
    }
    return null
}

private fun tryLoadClass(name: String): Class<*>? {
    return try {
        Class.forName(name)
    } catch (_: ClassNotFoundException) {
        null
    }
}

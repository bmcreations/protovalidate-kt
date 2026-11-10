package dev.bmcreations.protovalidate.conformance.pgv.wire

import com.squareup.wire.AnyMessage
import com.squareup.wire.ProtoAdapter
import dev.bmcreations.protovalidate.ValidationResult
import tests.harness.TestCase
import tests.harness.TestResult
import java.io.File

/**
 * PGV conformance executor for Wire messages: reads a `TestCase` from stdin and
 * writes a `TestResult` to stdout, the protocol `pgv-conformance/runner` drives.
 *
 * Set `PGV_NO_VALIDATOR_LOG` to a file path to have every case whose type has no
 * validator appended to it; those cases report valid without checking anything.
 */
fun main() {
    val testCase = TestCase.ADAPTER.decode(System.`in`.readBytes())
    val result = executeCase(testCase.message)
    System.out.write(TestResult.ADAPTER.encode(result))
    System.out.flush()
}

private fun executeCase(anyMsg: AnyMessage?): TestResult {
    if (anyMsg == null) return TestResult(Error = true, Reasons = listOf("TestCase has no message"))
    return try {
        val typeName = anyMsg.typeUrl.substringAfterLast('/')

        val messageClass = resolveMessageClass(typeName)
            ?: return TestResult(Error = true, Reasons = listOf("Unknown type: $typeName"))

        val adapter = messageClass.getField("ADAPTER").get(null) as ProtoAdapter<*>
        val message = adapter.decode(anyMsg.value.toByteArray())!!

        val validateMethod = resolveValidatorClass(typeName)?.methods?.find {
            it.name == "validate" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(messageClass)
        }
        if (validateMethod == null) {
            System.getenv("PGV_NO_VALIDATOR_LOG")?.let { File(it).appendText("$typeName\n") }
            return TestResult(Valid = true) // no validator means no constraints
        }

        when (val result = validateMethod.invoke(null, message) as ValidationResult) {
            is ValidationResult.Valid -> TestResult(Valid = true)
            is ValidationResult.Invalid -> TestResult(
                Valid = false,
                Reasons = result.violations.map { "${it.field}: ${it.message}" },
            )
        }
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.targetException
        TestResult(Error = true, Reasons = listOf("Validation threw: ${cause.javaClass.simpleName}: ${cause.message}"))
    } catch (e: Exception) {
        TestResult(Error = true, Reasons = listOf("${e.javaClass.simpleName}: ${e.message}"))
    }
}

// Wire has no outer class, so `pkg.Outer.Inner` is `pkg.Outer$Inner`.
private fun resolveMessageClass(protoTypeName: String): Class<*>? {
    tryLoadClass(protoTypeName)?.let { return it }
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
    for (i in parts.size - 1 downTo 1) {
        val pkg = parts.subList(0, i).joinToString(".")
        val names = parts.subList(i, parts.size).joinToString("")
        tryLoadClass("$pkg.${names}ValidatorKt")?.let { return it }
    }
    return null
}

private fun tryLoadClass(name: String): Class<*>? = try {
    Class.forName(name)
} catch (_: ClassNotFoundException) {
    null
}

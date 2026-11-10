package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions

/**
 * The package a file's message classes, and so its validators, are generated in.
 *
 * protobuf-java uses `java_package`, falling back to the proto package. Wire
 * puts `(wire.wire_package)` first (`JvmLanguages.javaPackage` in wire-schema).
 */
fun kotlinPackage(fileProto: FileDescriptorProto, target: GeneratorTarget): String {
    if (target == GeneratorTarget.WIRE) {
        wirePackageOption(fileProto.options)?.let { return it }
    }
    return if (fileProto.options.hasJavaPackage()) fileProto.options.javaPackage else fileProto.`package`
}

/** `wire_package`'s field number on `google.protobuf.FileOptions`, from Wire's `wire/extensions.proto`. */
internal const val WIRE_PACKAGE_FIELD_NUMBER = 1087

/**
 * The `(wire.wire_package)` file option, or null.
 *
 * The plugins parse requests with only their own rule extensions registered,
 * so the option normally arrives as an unknown field. A caller whose registry
 * does know `wire.wire_package` gets it as a parsed extension instead.
 */
internal fun wirePackageOption(options: FileOptions): String? {
    options.allFields.entries
        .firstOrNull { (field, _) -> field.isExtension && field.fullName == "wire.wire_package" }
        ?.let { (_, value) -> return value as String }
    // A repeated non-repeated field means the last occurrence wins.
    return options.unknownFields.getField(WIRE_PACKAGE_FIELD_NUMBER)
        ?.lengthDelimitedList?.lastOrNull()?.toStringUtf8()
}

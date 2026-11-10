package dev.bmcreations.protovalidate.plugin

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.UnknownFieldSet
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test

/** wire_package's number on FileOptions in Wire 7.1.2's wire/extensions.proto. */
private const val WIRE_PACKAGE = 1087

class KotlinPackageTest {

    private fun file(options: FileOptions) = FileDescriptorProto.newBuilder()
        .setName("a.proto").setPackage("proto.pkg").setOptions(options).build()

    private val javaPackageOnly = FileOptions.newBuilder().setJavaPackage("java.pkg").build()

    /** FileOptions bytes carrying java_package and wire_package = each of [wirePackages]. */
    private fun withWirePackage(vararg wirePackages: String): ByteString {
        val field = UnknownFieldSet.Field.newBuilder()
        wirePackages.forEach { field.addLengthDelimited(ByteString.copyFromUtf8(it)) }
        return javaPackageOnly.toBuilder()
            .setUnknownFields(UnknownFieldSet.newBuilder().addField(WIRE_PACKAGE, field.build()).build())
            .build().toByteString()
    }

    @Test
    fun `java_package wins, then the proto package`() {
        assertEquals("java.pkg", kotlinPackage(file(javaPackageOnly), GeneratorTarget.WIRE))
        assertEquals("proto.pkg", kotlinPackage(file(FileOptions.getDefaultInstance()), GeneratorTarget.WIRE))
    }

    @Test
    fun `wire_package read as an unknown field applies only to wire`() {
        val options = FileOptions.parseFrom(withWirePackage("wire.first", "wire.pkg"))
        assertEquals("wire.pkg", kotlinPackage(file(options), GeneratorTarget.WIRE))
        assertEquals("java.pkg", kotlinPackage(file(options), GeneratorTarget.JAVA))
    }

    @Test
    fun `wire_package read as a registered extension`() {
        // The shape of wire/extensions.proto's declaration.
        val extensions = FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("wire/extensions.proto").setPackage("wire")
                .addDependency("google/protobuf/descriptor.proto")
                .addExtension(
                    FieldDescriptorProto.newBuilder()
                        .setName("wire_package").setNumber(WIRE_PACKAGE)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setExtendee(".google.protobuf.FileOptions")
                )
                .build(),
            arrayOf(com.google.protobuf.DescriptorProtos.getDescriptor()),
        )
        val registry = ExtensionRegistry.newInstance()
        registry.add(extensions.findExtensionByName("wire_package"))

        val options = FileOptions.parseFrom(withWirePackage("wire.pkg"), registry)
        assertEquals(0, options.unknownFields.asMap().size)
        assertEquals("wire.pkg", kotlinPackage(file(options), GeneratorTarget.WIRE))
    }
}

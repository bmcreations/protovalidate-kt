package dev.bmcreations.protovalidate.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Which constraint system to generate validation code for.
 */
enum class ProtoVariant {
    /** buf validate (`buf/validate/validate.proto`) — recommended for new projects */
    BUF,
    /** protoc-gen-validate (`validate/validate.proto`) — legacy */
    PGV,
}

/**
 * Which generated message classes the validators are written against.
 */
enum class MessageTarget {
    /** protobuf-java classes (`hasX()`, `getXList()`, oneof case enums). */
    JAVA,

    /**
     * Wire 7.x Kotlin classes (nullable properties, no outer class). Requires
     * [ProtoVariant.PGV]. The message classes come from Wire's own Gradle
     * plugin.
     *
     * With `org.jetbrains.kotlin.multiplatform` applied, the plugin runs protoc
     * itself (task `generateProtovalidateWire`) over
     * [ProtovalidateExtension.protoDirs] and adds the output to `commonMain`.
     * Otherwise it hooks protobuf-gradle-plugin's generate tasks and removes
     * their `java` and `kotlin` builtins.
     */
    WIRE,
}

interface ProtovalidateExtension {
    val variant: Property<ProtoVariant>

    /** Defaults to [MessageTarget.JAVA]. Passed to the protoc plugin as `target=`. */
    val target: Property<MessageTarget>

    /**
     * KMP + [MessageTarget.WIRE] only: proto roots to generate validators for.
     * Defaults to `src/commonMain/proto`, Wire's default source path. Set it
     * to match `wire { sourcePath { ... } }`.
     */
    val protoDirs: ConfigurableFileCollection

    /**
     * KMP + [MessageTarget.WIRE] only: include-only proto roots, used to
     * resolve imports; no validators are generated for them. Set it to match
     * `wire { protoPath { ... } }`. `validate/validate.proto` and the
     * well-known types are always on the include path.
     */
    val importDirs: ConfigurableFileCollection

    /**
     * [MessageTarget.WIRE] only: passed as `wire_box_oneofs_min_size=N`. Set it
     * to the same value as `wire { kotlin { boxOneOfsMinSize = N } }`, so the
     * validators read oneofs the way Wire generated them.
     */
    val wireBoxOneOfsMinSize: Property<Int>

    /**
     * KMP + [MessageTarget.WIRE] only, read-only: a directory holding the
     * bundled `validate/validate.proto`. Wire needs it to compile PGV protos:
     * `wire { protoPath { srcDir(protovalidate.validateProtoDir) } }`. It is a
     * task output, so Wire's generate task runs after the extraction.
     */
    val validateProtoDir: DirectoryProperty
}

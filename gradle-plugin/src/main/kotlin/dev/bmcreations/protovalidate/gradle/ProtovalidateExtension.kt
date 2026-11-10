package dev.bmcreations.protovalidate.gradle

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
     * plugin, so protobuf-java's `java` and `kotlin` builtins are removed from
     * the generate tasks.
     */
    WIRE,
}

interface ProtovalidateExtension {
    val variant: Property<ProtoVariant>

    /** Defaults to [MessageTarget.JAVA]. Passed to the protoc plugin as `target=`. */
    val target: Property<MessageTarget>
}

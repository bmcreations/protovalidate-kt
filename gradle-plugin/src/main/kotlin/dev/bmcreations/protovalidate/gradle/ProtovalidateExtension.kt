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

interface ProtovalidateExtension {
    val variant: Property<ProtoVariant>
}

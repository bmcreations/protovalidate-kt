package dev.bmcreations.protovalidate.gradle

import org.gradle.api.provider.Property

interface ProtovalidateExtension {
    /**
     * Which constraint system to generate validation code for.
     *
     * - `"buf"` (default) — buf validate (`buf/validate/validate.proto`)
     * - `"pgv"` — protoc-gen-validate (`validate/validate.proto`)
     */
    val variant: Property<String>
}

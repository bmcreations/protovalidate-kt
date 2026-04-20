# protovalidate-kt

Kotlin code generation for [protovalidate](https://github.com/bufbuild/protovalidate) (buf.validate) and [protoc-gen-validate](https://github.com/bufbuild/protoc-gen-validate) (PGV) constraints.

Generates Kotlin validation functions from protobuf constraint annotations at compile time via a `protoc` plugin. Includes a lightweight runtime library for common validation logic.

## Modules

| Module | Description |
|---|---|
| `runtime` | Validation helper functions used by generated code |
| `protoc-plugin-core` | Shared code generator logic (field emitters, CEL transpiler) |
| `protoc-plugin` | `protoc` plugin for PGV (`validate/validate.proto`) constraints |
| `protoc-plugin-buf` | `protoc` plugin for buf validate (`buf/validate/validate.proto`) constraints |
| `conformance` | Buf protovalidate conformance test executor |
| `pgv-conformance` | PGV conformance test executor |

## Usage

### As a protoc plugin

Build the plugin JAR:

```bash
./gradlew :protoc-plugin-buf:jar
```

Then reference it in your Gradle protobuf configuration:

```kotlin
protobuf {
    plugins {
        create("validate-kt-buf") {
            path = "/path/to/protoc-plugin-buf/build/libs/protoc-plugin-buf.jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins { create("validate-kt-buf") }
        }
    }
}
```

### Runtime dependency

Add the `runtime` module as a dependency for the generated validation code:

```kotlin
dependencies {
    implementation("dev.bmcreations.protovalidate:runtime:<version>")
}
```

## Conformance

### buf protovalidate (2872/2872 passing)

```bash
./gradlew :conformance:jar
conformance/run-conformance.sh
```

### PGV (1053/1053 passing)

```bash
./gradlew :pgv-conformance:jar
cd pgv-conformance && ./run-conformance.sh
```

## Building

Requires JDK 21+.

```bash
./gradlew build
```

## License

MIT

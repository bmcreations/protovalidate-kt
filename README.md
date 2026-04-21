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
| `gradle-plugin` | Gradle plugin that wires everything together for consumers |
| `conformance` | Buf protovalidate conformance test executor |
| `pgv-conformance` | PGV conformance test executor |

## Setup

### Gradle Plugin

The simplest way to use protovalidate-kt. Requires the [protobuf-gradle-plugin](https://github.com/google/protobuf-gradle-plugin).

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.6"
    id("dev.bmcreations.protovalidate") version "<version>"
}
```

This automatically:
- Registers the protoc plugin for code generation
- Adds the `runtime` dependency
- Configures `generateProtoTasks` to invoke the plugin

By default the **buf validate** variant is used. To use PGV instead:

```kotlin
import dev.bmcreations.protovalidate.gradle.ProtoVariant

protovalidate {
    variant.set(ProtoVariant.PGV)
}
```

### buf validate (recommended)

[buf validate](https://github.com/bufbuild/protovalidate) is the actively-maintained successor to PGV. Protos use `buf.validate` annotations:

```protobuf
import "buf/validate/validate.proto";

message User {
  string email = 1 [(buf.validate.field).string.email = true];
  uint32 age = 2 [(buf.validate.field).uint32 = {gte: 0, lte: 150}];
}
```

### PGV (legacy)

[protoc-gen-validate](https://github.com/bufbuild/protoc-gen-validate) uses `validate` annotations:

```protobuf
import "validate/validate.proto";

message User {
  string email = 1 [(validate.rules).string.email = true];
  uint32 age = 2 [(validate.rules).uint32 = {gte: 0, lte: 150}];
}
```

PGV protos import `validate/validate.proto`. If protoc can't find it, add the proto include path. The Gradle plugin handles this automatically when using `ProtoVariant.PGV`.

### Manual setup (without the Gradle plugin)

If you prefer to configure things yourself:

```kotlin
dependencies {
    implementation("dev.bmcreations.protovalidate:runtime:<version>")
}

protobuf {
    plugins {
        // The protobuf-gradle-plugin auto-generates a wrapper script for JAR artifacts
        create("validate-kt-buf") {
            artifact = "dev.bmcreations.protovalidate:protoc-plugin-buf:<version>@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins { create("validate-kt-buf") }
        }
    }
}
```

For PGV, replace `validate-kt-buf` / `protoc-plugin-buf` with `validate-kt` / `protoc-plugin`.

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

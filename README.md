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
    implementation("dev.bmcreations:protovalidate-runtime:<version>")
}

protobuf {
    plugins {
        // The protobuf-gradle-plugin auto-generates a wrapper script for JAR artifacts
        create("validate-kt-buf") {
            artifact = "dev.bmcreations:protovalidate-protoc-plugin-buf:<version>@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins { create("validate-kt-buf") }
        }
    }
}
```

For PGV, replace `validate-kt-buf` / `protovalidate-protoc-plugin-buf` with
`validate-kt` / `protovalidate-protoc-plugin`.

## Variant differences

`required` on a oneof member means different things in the two dialects, and the
generated code differs to match:

- **buf validate** gives it `has` semantics. A member that is not the set case
  fails its own check, so at most one member of a oneof can be `required`.
- **PGV** applies `(validate.rules).message.required` only when that member is
  the set case, so several members can carry it.

"Some member must be set" is a oneof-level option in both:
`option (buf.validate.oneof).required = true` for buf,
`option (validate.required) = true` for PGV.

## Building

Requires JDK 21+.

```bash
./gradlew build
```

### Golden tests

`protoc-plugin` and `protoc-plugin-buf` each pin the Kotlin the generator emits
for a fixture proto, under `src/test/resources/golden`. A codegen change fails
`./gradlew build` with a diff of the emitted code. Read the diff, and once the
new output is right, accept it:

```bash
./gradlew :protoc-plugin:test :protoc-plugin-buf:test -PupdateGoldens
```

Both modules use the same message shapes in their own dialect, so a diff between
the two golden directories is where the variants diverge.

## Conformance

### buf protovalidate (2872/2872 passing)

```bash
go install github.com/bufbuild/protovalidate/tools/protovalidate-conformance@$(cat conformance/HARNESS_VERSION)
./gradlew :conformance:jar
./conformance/run-conformance.sh
```

The harness version is pinned in `conformance/HARNESS_VERSION` so a local run and
CI check the same corpus.

### PGV (1070/1070 passing)

```bash
(cd pgv-conformance/runner && go build -o pgv-conformance-runner .)
./gradlew :pgv-conformance:jar
./pgv-conformance/run-conformance.sh
```

Every message in the vendored harness protos has to be the subject of at least
one case. CI enforces that:

```bash
./pgv-conformance/check-coverage.sh
```

## License

MIT

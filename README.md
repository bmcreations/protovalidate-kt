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

### Wire messages

With PGV rules, the validators can target [Wire](https://github.com/square/wire)
7.x Kotlin classes instead of protobuf-java. Generate the messages with Wire's
Gradle plugin and select the Wire target:

```kotlin
import dev.bmcreations.protovalidate.gradle.MessageTarget
import dev.bmcreations.protovalidate.gradle.ProtoVariant

protovalidate {
    variant.set(ProtoVariant.PGV)
    target.set(MessageTarget.WIRE)
}
```

The plugin then passes `target=wire` to `protoc-gen-validate-kt` and removes
protobuf-java's `java` and `kotlin` builtins from the generate tasks, since
those classes would clash with Wire's. Without the Gradle plugin, pass the
option to protoc directly: `--validate-kt_out=target=wire:<dir>`. The default
is `target=java`; any other value fails the run.

The generated `validate()` has the same name and return type in both modes. In
Wire mode it uses only common Kotlin APIs, so it compiles in a KMP `commonMain`.

### Kotlin Multiplatform with Wire

protobuf-gradle-plugin only generates into JVM source sets. When
`org.jetbrains.kotlin.multiplatform` is applied and `target` is `WIRE`, the
plugin runs protoc itself in a `generateProtovalidateWire` task and adds the
output and `protovalidate-runtime` to `commonMain`. Wire's Gradle plugin still
generates the message classes:

```kotlin
import dev.bmcreations.protovalidate.gradle.MessageTarget
import dev.bmcreations.protovalidate.gradle.ProtoVariant

plugins {
    kotlin("multiplatform") version "2.3.20"
    id("com.squareup.wire") version "7.1.2"
    id("dev.bmcreations.protovalidate") version "<version>"
}

kotlin {
    jvm()
    js(IR) { nodejs() }
    iosSimulatorArm64()
}

wire {
    // Wire needs validate/validate.proto to compile PGV protos. This is the
    // copy bundled with the plugin; Wire's task depends on its extraction.
    protoPath { srcDir(protovalidate.validateProtoDir) }
    kotlin {}
}

protovalidate {
    variant.set(ProtoVariant.PGV)
    target.set(MessageTarget.WIRE)
}
```

The validators are generated from `protoDirs`, which defaults to
`src/commonMain/proto`, Wire's default source path. If your `wire { }` block
points elsewhere, set the same directories on the plugin:

```kotlin
protovalidate {
    protoDirs.setFrom("src/commonMain/protos")   // match wire { sourcePath { } }
    importDirs.from("third_party/protos")        // match wire { protoPath { } }
    wireBoxOneOfsMinSize.set(2)                  // match wire { kotlin { boxOneOfsMinSize = 2 } }
}
```

Protos under `importDirs` resolve imports but get no validators.
`validate/validate.proto` and the well-known types are always on the include
path. Set `wireBoxOneOfsMinSize` to the same value as Wire's `boxOneOfsMinSize`, so
the validators match the oneof shape Wire generated. It is passed to the
generator as `wire_box_oneofs_min_size=N`.

The protoc version is the one this plugin is built against, resolved as
`com.google.protobuf:protoc:<version>:<os>-<arch>@exe` from your build's
repositories. The plugin runs through a shell script, so Windows hosts are not
supported.

If Wire boxes oneofs (`boxOneOfsMinSize` in its `kotlin {}` block), pass the
same value as `wire_box_oneofs_min_size`:
`--validate-kt_out=target=wire,wire_box_oneofs_min_size=8:<dir>`. Oneofs with at
least that many members are then read through Wire's `OneOf<Key, Value>?`
property, and smaller ones stay one nullable property per member. The value has
to match Wire's, or the validators won't compile. Like Wire, the default is
5000. Protoc fails the run when the option is given without `target=wire`, on
`protoc-gen-validate-kt-buf`, or with a value that isn't a positive integer.
The Gradle plugin doesn't set the option yet.

Generated files follow Wire's package choice: `(wire.wire_package)`, else
`java_package`, else the proto `package`.

Limits of Wire mode:
- PGV only. `protoc-gen-validate-kt-buf` rejects `target=wire`.
- `enum.defined_only` can't be checked: Wire drops unknown enum values into
  `unknownFields` on decode (and throws for unknown enum map values, and for
  unknown enum values in a boxed oneof).

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

The runtime is a Kotlin Multiplatform library. Gradle resolves
`protovalidate-runtime` to the artifact for each target through Gradle module
metadata. Maven doesn't read that metadata, so Maven builds depend on the JVM
artifact directly:

```xml
<dependency>
    <groupId>dev.bmcreations</groupId>
    <artifactId>protovalidate-runtime-jvm</artifactId>
    <version>VERSION</version>
</dependency>
```

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

The same harness against Wire messages and `target=wire` validators:

```bash
./gradlew :pgv-conformance-wire:executorJar
./pgv-conformance-wire/run-conformance.sh
```

It passes 1061/1070. The 9 failures are the `defined_only` cases that Wire's
decoder makes unobservable, listed in `pgv-conformance-wire/expected-failures.txt`.
To run it with boxed oneofs, build with `-PwireBoxOneOfsMinSize=N`, which sets
both Wire's `boxOneOfsMinSize` and `wire_box_oneofs_min_size` for the harness:

```bash
./gradlew :pgv-conformance-wire:executorJar -PwireBoxOneOfsMinSize=1
./pgv-conformance-wire/run-conformance.sh
```

With N=1 every harness oneof is boxed, and the same 9 cases fail. `./gradlew :pgv-conformance-wire:check` asserts that
both targets generate the same validator files, so a misnamed Wire validator
can't turn into a silent pass.

Every message in the vendored harness protos has to be the subject of at least
one case. CI enforces that:

```bash
./pgv-conformance/check-coverage.sh
```

## License

MIT

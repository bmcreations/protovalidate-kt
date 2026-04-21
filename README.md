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

There are two plugins depending on which constraint system your protos use:

- **`protoc-plugin-buf`** — for [buf validate](https://github.com/bufbuild/protovalidate) (`buf.validate` annotations). This is the recommended approach for new projects.
- **`protoc-plugin`** — for [protoc-gen-validate](https://github.com/bufbuild/protoc-gen-validate) (PGV, `validate/validate.proto` annotations). Use this if your protos already use PGV constraints.

Both plugins work the same way: `protoc` invokes them as executables during code generation. Each module includes a shell wrapper script that calls `java -jar` on the built fat JAR.

### buf validate (recommended)

Build the plugin:

```bash
./gradlew :protoc-plugin-buf:jar
```

In your consuming project's `build.gradle.kts`:

```kotlin
protobuf {
    plugins {
        create("validate-kt-buf") {
            // Points to the shell wrapper script, which invokes the fat JAR.
            // protoc requires an executable — it cannot run a JAR directly.
            path = "/path/to/protovalidate-kt/protoc-plugin-buf/protoc-gen-validate-kt-buf"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins { create("validate-kt-buf") }
        }
    }
}

// Ensure the plugin JAR is built before protoc runs (only needed if
// protovalidate-kt is included as a composite build or subproject)
afterEvaluate {
    tasks.withType<com.google.protobuf.gradle.GenerateProtoTask>().configureEach {
        dependsOn(":protoc-plugin-buf:jar")
    }
}
```

### PGV (legacy)

Build the plugin:

```bash
./gradlew :protoc-plugin:jar
```

In your consuming project's `build.gradle.kts`:

```kotlin
protobuf {
    plugins {
        create("validate-kt") {
            path = "/path/to/protovalidate-kt/protoc-plugin/protoc-gen-validate-kt"
        }
    }
    generateProtoTasks {
        all().forEach {
            // PGV protos import validate/validate.proto — add it to the include path
            it.addIncludeDir(files("/path/to/protovalidate-kt/protos/validate"))
            it.plugins { create("validate-kt") }
        }
    }
}

afterEvaluate {
    tasks.withType<com.google.protobuf.gradle.GenerateProtoTask>().configureEach {
        dependsOn(":protoc-plugin:jar")
    }
}
```

### Runtime dependency

The generated validation code calls helper functions from the `runtime` module. Add it as a dependency:

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

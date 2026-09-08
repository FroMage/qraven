# Qraven

A fast build tool that reads Maven `pom.xml` files and generates a self-contained Java build script (`build.jar`) that can be compiled to a native binary via GraalVM.

## How it works

1. **Generate** -- Qraven parses all `pom.xml` files in a multi-module Maven project, resolves dependencies via Maven Resolver (Aether), and generates one Java source file per module plus a `Build.java` main class. These are compiled and packaged into `build.jar`.

2. **Build** -- Running `build.jar` (via `java -jar` or as a native binary) compiles all modules using the `javax.tools.JavaCompiler` API, copies resources, generates Jandex indexes, creates JARs, and installs artifacts to `~/.m2/repository`.

## Prerequisites

- JDK 17+ (for building qraven itself)
- Maven 3.9+
- GraalVM JDK 21+ (for native image compilation, optional)

## Installation

### From Maven coordinates (JBang)

After building and installing to your local Maven repository:

```bash
cd qraven
mvn install -DskipTests
jbang app install --name qraven io.quarkiverse.qraven:qraven-hardcoded:1.0-SNAPSHOT
```

### From GitHub (JBang)

```bash
jbang app install --name qraven qraven@FroMage/qraven
```

### From a local clone (JBang)

No Maven build needed -- JBang compiles and caches on first run:

```bash
jbang app install --name qraven qraven@/path/to/qraven
```

### Building from source (Maven only)

```bash
cd qraven
mvn install -DskipTests
```

## Usage

### Step 1: Generate build.jar for your project

**With JBang:**
```bash
qraven --project /path/to/your/maven/project
```

**With Maven (from source):**
```bash
cd qraven
mvn -pl hardcoded exec:java \
  -Dexec.mainClass=io.quarkiverse.qraven.hardcoded.QravenCli \
  -Dexec.args="--project /path/to/your/maven/project"
```

This creates `<project>/target/qraven/build.jar`.

Options:
- `--project <path>` or `-p <path>` -- project root (default: current dir)
- `--threads <n>` or `-t <n>` -- thread count (default: available processors)
- `--output <path>` or `-o <path>` -- output directory (default: `<project>/target/qraven`)
- `--native` -- compile build.jar to a native binary after generation
- `--graalvm-home <path>` -- GraalVM installation path (for `--native`)

### Step 2: Run the build

**JVM mode:**
```bash
java -jar target/qraven/build.jar
```

**JVM mode with AOT cache (JDK 25+):**
```bash
# Training (one-time):
java -XX:AOTCacheOutput=target/qraven/build.aot -jar target/qraven/build.jar

# Production:
java -XX:AOTCache=target/qraven/build.aot -jar target/qraven/build.jar
```

### Native image compilation

Compile `build.jar` to a native binary for maximum startup speed:

```bash
GRAALVM_HOME=/path/to/graalvm-jdk-21

$GRAALVM_HOME/bin/native-image \
  -jar target/qraven/build.jar \
  -H:IncludeResourceBundles=com.sun.tools.javac.resources.compiler,com.sun.tools.javac.resources.javac,com.sun.tools.javac.resources.launcher \
  --initialize-at-build-time=com.sun.tools.javac \
  -H:+AllowJRTFileSystem \
  -march=native \
  target/qraven/build
```

**Important:** `build.jar` must be compiled with the same JDK version used by `native-image`. A mismatch causes `UnsupportedClassVersionError`.

Run the native binary:

```bash
JAVA_HOME=/path/to/graalvm-jdk-21 ./target/qraven/build
```

#### JAVA_HOME version must match the native image's JDK

The native binary embeds a specific version of javac (from the GraalVM JDK used to build it). At runtime, `JAVA_HOME` **must point to a JDK of the same major version**.

The native image bakes in some JDK artifacts statically (jrt filesystem content, javac internals), but at runtime javac still reads `$JAVA_HOME/lib/ct.sym` to resolve `--release N` platform classes. If `JAVA_HOME` points to a newer JDK (e.g. JDK 25), its `ct.sym` contains class files with a newer version number (69.0 for JDK 25) that the embedded older javac (JDK 21, max version 65.0) cannot read, producing:

```
bad class file: /L/java.base/module-info.sig
    class file has wrong version 69.0, should be 65.0
```

For example, if you built the native image with GraalVM JDK 21:
- `JAVA_HOME=/path/to/jdk-21` -- works
- `JAVA_HOME=/path/to/jdk-25` -- fails (version 69.0 vs 65.0)

This applies to both the `AllowJRTFileSystem` path and the jmod extraction fallback -- in both cases the embedded javac must be able to read the platform class metadata from the runtime JDK.

#### Native image flags explained

| Flag | Purpose |
|------|---------|
| `-H:IncludeResourceBundles=...` | Embeds javac's error message bundles (otherwise `MissingResourceException` at runtime) |
| `--initialize-at-build-time=com.sun.tools.javac` | Initializes javac classes at build time for faster startup |
| `-H:+AllowJRTFileSystem` | Bakes the build-time JDK's jrt filesystem into the image so javac can read platform classes without extracting jmods at startup |
| `-march=native` | Optimizes for the build machine's CPU |

#### Without AllowJRTFileSystem (fallback)

If `-H:+AllowJRTFileSystem` is unavailable (e.g. linking fails due to missing `libstdc++.a`), qraven has a built-in fallback: it extracts classes from `$JAVA_HOME/jmods/*.jmod` into temporary module JARs at startup, then passes `--system none --module-path <jars> --add-modules ALL-MODULE-PATH` to javac. This adds ~11s startup overhead but works without the flag. The fallback activates automatically when the jrt filesystem is not available.

If linking fails with "libstdc++.a is missing", install:
```bash
# Fedora/RHEL
dnf install libstdc++-static

# Debian/Ubuntu
apt install libstdc++-12-dev   # or the version matching your gcc
```

## Performance

Benchmarked on quarkus-renarde (20 modules):

| Mode | Time | Speedup vs Maven |
|------|------|-------------------|
| `mvn clean install -DskipTests` | ~28s | baseline |
| qraven JVM (JDK 25) | ~15s | 1.9x |
| qraven JVM + AOT cache | ~11s | 2.5x |
| qraven native (GraalVM 21) | ~2.5s | 11x |
| qraven native + classpath warmup | ~1.4s | 20x |

Tested on Quarkus (1363 modules): native build completes in ~189s vs ~289s for JVM (1.5x speedup), with 95.4% module success rate matching JVM results.

See `BENCHMARKS.txt` for detailed measurements and optimization notes.

## Project structure

```
qraven/
  pom.xml                          # Parent POM
  hardcoded/
    pom.xml                        # Main module
    src/main/java/.../
      QravenCli.java               # CLI entry point (generation)
      PomParser.java               # Parses pom.xml files recursively
      DependencyResolver.java      # Resolves dependencies via Maven Resolver
      ModuleInfo.java              # Module metadata (coords, deps, source dirs)
      BuildFileGenerator.java      # Generates Build_*.java per module + Build.java
      runtime/
        BuildRuntime.java          # Compilation, resource copying, JAR creation
        BuildOrchestrator.java     # Multi-threaded build execution
        ModuleBuild.java           # Per-module build logic
        QuarkusBuildHelper.java    # Quarkus augmentation (quarkus:build goal)
```

The `runtime/` classes are bundled into the generated `build.jar` and execute at build time. The other classes are only used during generation.

## Supported Maven plugins

Qraven replicates the behavior of the following Maven plugins during build:

### Compilation
- **maven-compiler-plugin** -- Compiles Java sources using the `javax.tools.JavaCompiler` API (no external javac process). Supports `-parameters`, `--release`, `-source`/`-target`, `<compilerArgs>`, and annotation processor paths (`<annotationProcessorPaths>`). Reads configuration from both plugin-level and execution-level blocks. Merges compiler args from `<pluginManagement>` and `<build>/<plugins>` sections, including the `<parameters>true</parameters>` shorthand.
- **kotlin-maven-plugin** -- Compiles Kotlin sources using the embedded K2 JVM compiler. Supports mixed Java+Kotlin projects (Kotlin compiled first, then Java with Kotlin classes on the classpath).
- **protobuf-maven-plugin** -- Compiles `.proto` files using the `protoc` binary from `~/.m2/repository`. Supports gRPC and Quarkus Mutiny gRPC code generation plugins.

### Resource handling
- **maven-resources-plugin** -- Copies `src/main/resources` to `target/classes` with optional Maven-style property filtering (`${property}` interpolation). Binary file extensions are detected and copied without filtering.

### Indexing
- **jandex-maven-plugin** (SmallRye Jandex / `org.jboss.jandex:jandex-maven-plugin`) -- Generates `META-INF/jandex.idx` from compiled classes.

### Packaging
- **maven-jar-plugin** -- Creates JAR files with manifest entries from `<archive>/<manifestEntries>` configuration.
- **maven-install-plugin** -- Installs JARs and POMs to `~/.m2/repository`.

### Quarkus
- **quarkus-maven-plugin:build** -- Runs the full Quarkus augmentation pipeline (`QuarkusBootstrap` + `CuratedApplication.createAugmentor().createProductionApplication()`). Builds the `ApplicationModel` with all dependency flags (`runtimeCp`, `deploymentCp`, `runtimeExtensionArtifact`), extension properties (`parent-first-artifacts`, `excluded-artifacts`, `lesser-priority-artifacts`), and extension capabilities (`provides-capabilities`, `requires-capabilities`). Bypasses Maven entirely via `QuarkusBootstrap.builder().setExistingModel(model)`.

### Quarkus extension development
- **quarkus-extension-maven-plugin** -- Generates `META-INF/quarkus-extension.properties` and `META-INF/quarkus-extension.yaml` for Quarkus extension modules.

### Not yet supported
- **kotlin-maven-plugin** -- Kotlin compilation (modules using Kotlin are skipped)
- **protobuf-maven-plugin** -- Protocol buffer compilation (modules using protobuf are skipped)
- **maven-surefire-plugin / maven-failsafe-plugin** -- Test execution
- **maven-shade-plugin / maven-assembly-plugin** -- Uber-jar / assembly creation

## How the Quarkus build works

When a module declares `quarkus-maven-plugin` with the `build` goal, qraven:

1. **At generation time** (PomParser): Scans all runtime dependency JARs for `META-INF/quarkus-extension.properties` to identify Quarkus extensions. Extracts deployment artifact coordinates and extension properties (capabilities, excluded artifacts, etc.). Resolves the full deployment classpath transitively.

2. **At build time** (QuarkusBuildHelper): Constructs an `ApplicationModel` with:
   - The app artifact pointing to `target/classes`
   - All runtime dependencies with `runtimeCp=true, deploymentCp=true` flags
   - Extension JARs additionally flagged with `runtimeExtensionArtifact=true`
   - Extension properties processed via `handleExtensionProperties()` (parent-first, excluded-artifacts, etc.)
   - Extension capabilities registered via `addExtensionCapabilities()` (provides/requires)
   - Deployment-only JARs with `deploymentCp=true` only

3. **Bootstraps Quarkus**: Creates a `QuarkusBootstrap` with `setExistingModel()` to skip Maven resolution entirely, then calls `createAugmentor().createProductionApplication()` which runs all deployment processors (Arc CDI, REST, Hibernate ORM, etc.) and produces the `quarkus-app/` directory.

The key insight is that `setExistingModel()` tells Quarkus "I've already resolved everything -- don't try to use Maven." This is what makes it possible to run the build from a native binary without Maven.

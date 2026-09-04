# Qraven

A fast build tool that reads Maven `pom.xml` files and generates a self-contained Java build script (`build.jar`) that can be compiled to a native binary via GraalVM.

## How it works

1. **Generate** -- Qraven parses all `pom.xml` files in a multi-module Maven project, resolves dependencies via Maven Resolver (Aether), and generates one Java source file per module plus a `Build.java` main class. These are compiled and packaged into `build.jar`.

2. **Build** -- Running `build.jar` (via `java -jar` or as a native binary) compiles all modules using the `javax.tools.JavaCompiler` API, copies resources, generates Jandex indexes, creates JARs, and installs artifacts to `~/.m2/repository`.

## Prerequisites

- JDK 17+ (for building qraven itself)
- Maven 3.9+
- GraalVM JDK 21+ (for native image compilation, optional)

## Building qraven

```bash
cd qraven
mvn install -DskipTests
```

## Usage

### Step 1: Generate build.jar for your project

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
```

The `runtime/` classes are bundled into the generated `build.jar` and execute at build time. The other classes are only used during generation.

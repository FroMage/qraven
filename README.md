# Qraven

A fast build tool that reads Maven `pom.xml` files and generates a self-contained Java build script (`build.jar`) that can be compiled to a native binary via GraalVM. Automatically detects POM changes, handles bootstrapping, and runs the build.

## How it works

1. **Generate** -- Qraven parses all `pom.xml` files in a multi-module Maven project, resolves dependencies via Maven Resolver (Aether), and generates one Java source file per module plus a `Build.java` main class. These are compiled and packaged into a thin `build.jar` whose `Class-Path` manifest references `qraven-runtime` and its transitive dependencies in `~/.m2/repository`.

2. **Build** -- Running `build.jar` compiles all modules using the `javax.tools.JavaCompiler` API, copies resources, generates Jandex indexes, creates JARs, and installs artifacts to `~/.m2/repository`.

3. **Bootstrap** -- When building a project that contains qraven's own dependencies as reactor modules (e.g. Quarkus itself), qraven detects this and enters a two-phase bootstrap mode: it first builds and installs the dependency modules, then packages and runs the full build.

## Prerequisites

- JDK 17+ (for building qraven itself)
- Maven 3.9+ (for building qraven itself)
- [JBang](https://www.jbang.dev/) (for installing the CLI)
- GraalVM JDK 21+ (for native image compilation, optional)

## Installation

### Build and install

```bash
cd qraven
mvn install
```

This produces two modules:
- **`qraven-cli`** -- CLI tool (no Quarkus dependencies, installable via JBang)
- **`qraven-runtime`** -- Build execution classes (depends on Quarkus bootstrap, Jandex, Kotlin compiler)

### Install the CLI via JBang

After the Maven build:

```bash
jbang app install --name qraven --force io.github.fromage.qraven:qraven-cli:1.5-SNAPSHOT
```

JBang resolves all dependencies from the POM automatically.

## Usage

Run `qraven` from your Maven project root:

```bash
# Auto-generate if pom files changed, then build
# (--quickly is automatic for Quarkus projects)
qraven

# Build with tests enabled (overrides the Quarkus default)
qraven --no-quickly

# Build a single module and its dependencies
qraven -pl quarkus-arc -am

# Incremental build (only changed modules)
qraven -i

# Generate only, don't run the build
qraven --no-build

# Force regeneration even if poms haven't changed
qraven --force-generate

# Skip Kotlin modules and their dependents
qraven --no-kotlin

# Pass system properties through to the build
qraven -DskipTests -DskipITs
```

### CLI reference

```
Usage: qraven [options]

Generation options:
  --force-generate, -fg     Force regeneration even if pom files haven't changed
  --no-generate, -ng        Skip generation (use previously generated build.jar)
  --force-bootstrap, -fb    Force bootstrap build even if bootstrap jars are fresh

Build options:
  --no-build, -nb           Skip build execution (generate only)
  --quickly                 Skip tests and quarkus-build (default for Quarkus projects)
  --no-quickly              Disable auto-quickly for Quarkus projects
  -pl, --projects <list>    Comma-separated list of module artifactIds to build
  -am, --also-make          Build dependencies of modules specified by -pl
  -i, --incremental         Only rebuild modules with changed sources
  --no-kotlin               Skip Kotlin modules and their dependents
  --no-fast-kotlin          Disable JIT warmup for Kotlin compiler (enabled by default)
  -D<key>=<value>           Set a system property

Other options:
  -h, --help                Show this help message and exit
  -V, --version             Show version and exit
  -p, --project <path>      Project root directory (default: current directory)
  -t, --threads <n>         Thread count (default: available CPUs)
  -o, --output <path>       Output directory (default: <project>/target/qraven)
      --native              Compile build.jar to a native binary after generation
      --graalvm-home <path> GraalVM path (also checks GRAALVM_HOME, JAVA_HOME, PATH)
```

### Auto-generation

Qraven automatically detects when regeneration is needed by comparing `pom.xml` timestamps against `build.jar`. Generation is triggered when:
- `build.jar` doesn't exist, or
- Any `pom.xml` in the project is newer than `build.jar`, or
- `--force-generate` is passed

Generation is skipped when:
- `--no-generate` is passed, or
- All `pom.xml` files are older than `build.jar`

### Bootstrap mode

When building a project that contains `qraven-runtime`'s own transitive dependencies as reactor modules (e.g. building Quarkus itself), qraven detects this and enters bootstrap mode:

1. Reads `qraven-runtime`'s POM from `~/.m2` to identify its dependencies
2. Matches them against reactor modules and expands transitively
3. Checks if bootstrap jars are missing from `~/.m2` or have sources newer than the installed jars
4. If bootstrap is needed:
   - Generates and runs `bootstrap.jar` (bootstrap modules only)
   - Once bootstrap jars are installed to `~/.m2`, packages `build.jar` (all remaining modules)
   - Runs `build.jar`
5. If bootstrap jars are fresh: packages all modules into a single `build.jar`

Use `--force-bootstrap` to force a bootstrap build even when jars appear fresh.

The key detail: `build.jar` packaging is **deferred** until after bootstrap runs, because its `Class-Path` manifest references jars in `~/.m2/repository` that don't exist until bootstrap installs them.

**Classpath version substitution:** `build.jar`'s manifest classpath is derived from `qraven-runtime`'s transitive dependencies (e.g. `quarkus-bootstrap-core:3.39.4`). When the reactor being built includes a newer version of these same artifacts, qraven substitutes the classpath entries to point to the reactor-built jars instead. This avoids `NoSuchMethodError` from version mismatches between qraven's bundled dependency versions and the reactor's versions.

### Incremental mode

With `-i` / `--incremental`, qraven skips modules whose sources, resources, pom, and test sources are all older than the installed artifact in `~/.m2`. A module is also rebuilt when any of its dependencies was rebuilt in the same run.

Test compilation is also skipped for up-to-date modules unless a test dependency was rebuilt. When a module is rebuilt, its reasons are logged:

```
[incremental] [quarkus-core] not up-to-date: sources newer than artifact
[incremental] [quarkus-arc] not up-to-date: dependency rebuilt: quarkus-core
```

### Running build.jar directly

You can also run the generated build directly:

```bash
java -jar target/qraven/build.jar                     # Build everything
java -jar target/qraven/build.jar -pl my-module -am    # Build one module + deps
java -jar target/qraven/build.jar -i                   # Incremental
java -jar target/qraven/build.jar --no-kotlin           # Skip Kotlin modules
java -jar target/qraven/build.jar -DskipTests           # Skip tests
```

### JVM AOT cache (JDK 25+)

```bash
# Training (one-time):
java -XX:AOTCacheOutput=target/qraven/build.aot -jar target/qraven/build.jar

# Production:
java -XX:AOTCache=target/qraven/build.aot -jar target/qraven/build.jar
```

### Native image compilation

Compile `build.jar` to a native binary for maximum startup speed:

```bash
qraven --native --graalvm-home /path/to/graalvm-jdk-21
```

Or manually:

```bash
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

The native image bakes in some JDK artifacts statically (jrt filesystem content, javac internals), but at runtime javac still reads `$JAVA_HOME/lib/ct.sym` to resolve `--release N` platform classes. If `JAVA_HOME` points to a newer JDK (e.g. JDK 25), its `ct.sym` contains class files with a newer version number (69.0 for JDK 25) that the embedded older javac (JDK 21, max version 65.0) cannot read:

```
bad class file: /L/java.base/module-info.sig
    class file has wrong version 69.0, should be 65.0
```

#### Native image flags explained

| Flag | Purpose |
|------|---------|
| `-H:IncludeResourceBundles=...` | Embeds javac's error message bundles (otherwise `MissingResourceException` at runtime) |
| `--initialize-at-build-time=com.sun.tools.javac` | Initializes javac classes at build time for faster startup |
| `-H:+AllowJRTFileSystem` | Bakes the build-time JDK's jrt filesystem into the image so javac can read platform classes without extracting jmods at startup |
| `-march=native` | Optimizes for the build machine's CPU |

#### Without AllowJRTFileSystem (fallback)

If `-H:+AllowJRTFileSystem` is unavailable (e.g. linking fails due to missing `libstdc++.a`), qraven has a built-in fallback: it extracts classes from `$JAVA_HOME/jmods/*.jmod` into temporary module JARs at startup, then passes `--system none --module-path <jars> --add-modules ALL-MODULE-PATH` to javac. This adds ~11s startup overhead but works without the flag.

If linking fails with "libstdc++.a is missing", install:
```bash
# Fedora/RHEL
dnf install libstdc++-static

# Debian/Ubuntu
apt install libstdc++-12-dev   # or the version matching your gcc
```

## Architecture

### Module layout

```
qraven/
  pom.xml                              # Parent POM
  cli/
    pom.xml                            # CLI module (no Quarkus deps, jbang-installable)
    src/main/java/.../
      QravenCli.java                   # CLI entry point (generation + build orchestration)
      PomParser.java                   # Parses pom.xml files recursively
      DependencyResolver.java          # Resolves dependencies via Maven Resolver (Aether)
      ModuleInfo.java                  # Module metadata (coords, deps, source dirs)
      BuildFileGenerator.java          # Generates Build_*.java per module + Build.java
  runtime/
    pom.xml                            # Runtime module (depends on Quarkus bootstrap, Jandex, Kotlin)
    src/main/java/.../runtime/
      ModuleBuild.java                 # Per-module build logic (abstract base for generated classes)
      BuildRuntime.java                # Compilation, resource copying, JAR creation
      BuildOrchestrator.java           # Multi-threaded build execution
      QuarkusBuildHelper.java          # Quarkus augmentation (quarkus:build goal)
      ExtensionDescriptorHelper.java   # Extension descriptor generation
      MavenPluginDescriptorGenerator.java  # Maven plugin descriptor generation
      ProgressDisplay.java             # Terminal progress bar
      BuildStats.java                  # Build timing statistics
```

### How the pieces fit together

- **`qraven-cli`** has no Quarkus dependencies. It parses POMs, resolves dependencies, generates Java source files, compiles them, and packages them into a thin `build.jar`. JBang installs this module directly from its Maven GAV.

- **`qraven-runtime`** depends on Quarkus bootstrap, Jandex, and the Kotlin compiler. It is NOT shaded into the CLI. Instead, `build.jar` references it (and its transitive dependencies) via `Class-Path` manifest entries pointing to jars in `~/.m2/repository`.

- **Generated `Build_*.java` classes** extend `ModuleBuild` from `qraven-runtime`. Each generated class hardcodes one module's coordinates, classpath, compiler args, and plugin configuration. The generated `Build.java` main class instantiates all module classes and delegates to `BuildOrchestrator`.

## Performance

Benchmarked on quarkus-renarde (20 modules):

| Mode | Time | Speedup vs Maven |
|------|------|-------------------|
| `mvn clean install -DskipTests` | ~28s | baseline |
| qraven JVM (JDK 25) | ~15s | 1.9x |
| qraven JVM + AOT cache | ~11s | 2.5x |
| qraven native (GraalVM 21) | ~2.5s | 11x |
| qraven native + classpath warmup | ~1.4s | 20x |

Tested on Quarkus (~1438 modules): native build completes in ~189s vs ~289s for JVM (1.5x speedup), with 95.4% module success rate matching JVM results.

See `BENCHMARKS.txt` for detailed measurements and optimization notes.

## Supported Maven plugins

Qraven replicates the behavior of the following Maven plugins during build:

### Compilation
- **maven-compiler-plugin** -- Compiles Java sources using the `javax.tools.JavaCompiler` API (no external javac process). Supports `-parameters`, `--release`, `-source`/`-target`, `<compilerArgs>`, and annotation processor paths (`<annotationProcessorPaths>`). Reads configuration from both plugin-level and execution-level blocks. Merges compiler args from `<pluginManagement>` and `<build>/<plugins>` sections, including the `<parameters>true</parameters>` shorthand.
- **kotlin-maven-plugin** -- Compiles Kotlin sources using the embedded K2 JVM compiler. Supports mixed Java+Kotlin projects (Kotlin compiled first, then Java with Kotlin classes on the classpath). Generated sources from code generation and protobuf are passed as additional Java source roots.
- **protobuf-maven-plugin** -- Compiles `.proto` files using the `protoc` binary from `~/.m2/repository`. Supports gRPC (`protoc-gen-grpc-java`) and Quarkus Mutiny gRPC (`quarkus-grpc-protoc-plugin`) code generation plugins. The Quarkus protoc plugin is resolved from both the deployment classpath and reactor dependencies.
- **antlr4-maven-plugin** -- Compiles ANTLR4 `.g4` grammar files to Java sources using the `antlr4` tool jar. Supports visitor generation via `<visitor>true</visitor>` configuration.

### Code generation
- **quarkus-maven-plugin:generate-code** -- Runs Quarkus code generators (e.g. gRPC, Avro) before compilation via `io.quarkus.deployment.CodeGenerator`. Generated sources are compiled alongside regular sources and passed to the Kotlin compiler when applicable.

### Resource handling
- **maven-resources-plugin** -- Copies `src/main/resources` to `target/classes` with optional Maven-style property filtering (`${property}` interpolation). Binary file extensions are detected and copied without filtering. Supports `<targetPath>` for placing resources under a specific prefix in the output.

### Indexing
- **jandex-maven-plugin** (SmallRye Jandex / `org.jboss.jandex:jandex-maven-plugin`) -- Generates `META-INF/jandex.idx` from compiled classes.

### Packaging
- **maven-jar-plugin** -- Creates JAR files with manifest entries from `<archive>/<manifestEntries>` configuration. Empty modules (no sources or resources) produce valid empty JARs, matching Maven behavior.
- **maven-install-plugin** -- Installs JARs and POMs to `~/.m2/repository`.

### Dependency handling
- **Maven dependency exclusions** -- Honors `<exclusion>` elements on reactor dependencies. Exclusions are propagated transitively through the BFS dependency walk and applied to both the runtime extension list and the deployment classpath resolution (both Aether and reactor module traversal).

### Quarkus
- **quarkus-maven-plugin:build** -- Runs the full Quarkus augmentation pipeline (`QuarkusBootstrap` + `CuratedApplication.createAugmentor().createProductionApplication()`). Builds the `ApplicationModel` with all dependency flags (`runtimeCp`, `deploymentCp`, `runtimeExtensionArtifact`), extension properties (`parent-first-artifacts`, `excluded-artifacts`, `lesser-priority-artifacts`), and extension capabilities (`provides-capabilities`, `requires-capabilities`). Registers protoc, gRPC, and Quarkus gRPC codegen tool artifacts. Bypasses Maven entirely via `QuarkusBootstrap.builder().setExistingModel(model)`.

### Quarkus extension development
- **quarkus-extension-maven-plugin** -- Generates `META-INF/quarkus-extension.properties` and `META-INF/quarkus-extension.yaml` for Quarkus extension modules.

### Code style
- **formatter-maven-plugin** (Eclipse JDT) -- Formats Java source files in-place using the project's `eclipse-format.xml` config. The Eclipse JDT formatter and its dependencies are loaded at runtime from `~/.m2/repository` via a separate classloader. Disabled with `-Dno-format`.
- **impsort-maven-plugin** -- Sorts Java imports into groups: `java.`, `javax.`, `jakarta.`, `org.`, `com.`, other, then static imports. Normalizes blank lines between groups. Implemented natively (no external dependencies). Disabled with `-Dno-format`.
- **spotless-maven-plugin** (ktfmt) -- Formats Kotlin source files using ktfmt with `KOTLINLANG` style. The ktfmt library and its dependencies are loaded at runtime from `~/.m2/repository`. Disabled with `-Dno-format`.

### Dependency enforcement
- **maven-enforcer-plugin** (simple) -- Checks compile classpath against Quarkus banned dependency lists (`quarkus-banned-dependencies.xml`, `quarkus-banned-dependencies-okhttp.xml`). Supports exact GA matches (`groupId:artifactId`), group wildcards (`groupId:*`), and prefix patterns (`groupId:prefix-*`). Reports violations as warnings. Disabled with `-Dno-format`.

### Uber-jar / shading
- **maven-shade-plugin** -- Merges dependency JARs into the module's own JAR after packaging. Supports `<artifactSet>/<includes>` to select which dependencies to merge (empty = all), per-artifact `<filters>/<excludes>` with glob patterns (`**` recursive, `*` single-level), `<shadedArtifactAttached>` to create a classified artifact instead of replacing the main JAR, `<shadedClassifierName>` for the classifier, and `ManifestResourceTransformer` for setting the main class. Classified artifacts are installed to `~/.m2/repository` alongside the main JAR. Used by `quarkus-grpc-protoc-plugin` (two executions: one shading jprotoc into the main artifact, one creating a `-shaded.jar` fat jar for the gRPC code generator) and `quarkus-bootstrap-gradle-resolver` (shading gradle-tooling-api).

### Not yet supported
- **avro-maven-plugin** -- Avro schema (`.avsc`) to Java code generation (Avro codegen runs via the Quarkus `generate-code` step instead)
- **maven-surefire-plugin / maven-failsafe-plugin** -- Test execution
- **maven-assembly-plugin** -- Assembly creation

## Comparison with Maven

This section compares `qraven --quickly` against the equivalent Maven command:

```bash
mvn -T 1C -DskipDocs -DskipTests -DskipITs -Dinvoker.skip \
    -DskipExtensionValidation -Dskip.gradle.tests -Dskip.gradle.build \
    -Dtruststore.skip clean install -Prelocations
```

### Module set

Both build the same ~1438 reactor modules. `-Prelocations` currently adds an empty parent POM (all 3.x relocations were removed in 4.0). `-DskipDocs` skips asciidoctor in the `docs/` module.

### Plugin/goal comparison

| Phase | Maven plugin/goal | qraven equivalent | Status |
|-------|-------------------|-------------------|--------|
| clean | `maven-clean-plugin:clean` | `runtime.clean()` deletes classes dir | **Equivalent** (qraven cleans classes dir only, not full `target/`) |
| validate | `maven-enforcer-plugin:enforce` | `runEnforcer()` | **Partial** — see below |
| process-sources | `formatter-maven-plugin:format` | `CodeStyleHelper` (Eclipse JDT) | **Equivalent** — same engine, same config |
| process-sources | `impsort-maven-plugin:sort` | `sortImports()` | **Equivalent** — same group order, reimplemented |
| process-sources | `spotless-maven-plugin:apply` (ktfmt) | `formatKotlinFiles()` | **Equivalent** |
| process-resources | `maven-resources-plugin:resources` | `copyResources()` | **Equivalent** |
| process-resources | `quarkus-extension-maven-plugin:extension-descriptor` | `ExtensionDescriptorHelper` | **Equivalent** |
| compile | `maven-compiler-plugin:compile` | `runtime.compile()` (in-process javac API) | **Equivalent** — faster (no JVM fork) |
| compile | `kotlin-maven-plugin:compile` | `runtime.compileKotlin()` (in-process K2JVMCompiler) | **Equivalent** — faster |
| process-classes | `bridger:transform` | — | **Missing** — affects 2–3 modules (arc/runtime, core/processor) |
| generate-sources | `protobuf-maven-plugin` | `runtime.compileProtobuf()` | **Equivalent** |
| generate-sources | `antlr4-maven-plugin` | `runtime.compileAntlr()` | **Equivalent** |
| generate-sources | `build-helper-maven-plugin:add-source` | Implicit (generated dirs added to compiler) | **Equivalent** |
| generate-sources | `module-services-plugin:generate` | — | **Missing** — affects ~9 modules with `module-info.java` |
| process-classes | `jandex-maven-plugin:jandex` | `runtime.generateJandexIndex()` | **Equivalent** |
| process-classes | `sisu-maven-plugin` | `generateSisuIndex()` | **Simplified** — see below |
| process-classes | `maven-plugin-plugin:descriptor` | `MavenPluginDescriptorGenerator` | **Simplified** — see below |
| test-compile | `maven-compiler-plugin:testCompile` | `runtime.compileTests()` (in-process javac API) | **Equivalent** — see below |
| test | `maven-surefire-plugin:test` | — | Skipped by both (`-DskipTests`) |
| package | `maven-jar-plugin:jar` | `runtime.createJar()` | **Equivalent** |
| package | `maven-shade-plugin:shade` | `executeShade()` | **Equivalent** — see below |
| package | `maven-source-plugin:jar-no-fork` | — | **Missing** — no `-sources.jar` produced |
| verify | `forbiddenapis:check` | — | **Missing** |
| install | `maven-install-plugin:install` | `runtime.install()` | **Simplified** — see below |

### Behavioral differences

**Enforcer:**
Maven runs the full `maven-enforcer-plugin`: `dependencyConvergence`, `banDuplicatePomDependencyVersions`, Java/Maven version checks, plus banned dependencies from 3 XML rule files. Qraven only checks banned dependencies from 2 of the 3 XML files (skips `-test.xml`), and warns instead of failing.

**Sisu index:**
Maven's `sisu-maven-plugin` scans bytecode via ASM. Qraven does text search for `@Named` in `.java` source files — can miss fully-qualified annotations or annotations inherited from superclasses, and can false-positive on commented-out annotations.

**Plugin descriptor:**
Maven's `maven-plugin-plugin` uses QDox to extract javadoc for `@Parameter` descriptions. Qraven uses Jandex bytecode scanning — parameter descriptions are always empty.

**Install:**
Maven installs the effective POM plus `.md5`/`.sha1` checksums and updates `maven-metadata-local.xml`. Qraven copies the raw source POM with no checksums. Raw POMs require the parent POM chain to be present in the local repo for `<dependencyManagement>` version resolution (handled by bootstrap's parent POM detection).

**Resources:**
No `<include>`/`<exclude>` filter support. No `@property@` delimiter support (only `${property}`). Filter properties are limited to what's explicitly extracted from the POM.

**Test compilation:**
Maven with `-DskipTests` still compiles test sources (only `-Dmaven.test.skip` skips test compilation). Qraven also compiles test sources by default. Test dependencies are resolved via Aether the same way as compile dependencies, with reactor modules resolved locally via a workspace reader. The main difference is that Maven resolves test-scoped reactor dependencies transitively through the reactor, while qraven resolves them via Aether against the local repository — the artifacts must already be installed in `~/.m2/repository` (which they are, since qraven installs each module after building it).

### Missing features that affect correctness

| Feature | Modules affected | Impact |
|---------|-----------------|--------|
| `bridger:transform` | 2–3 (arc/runtime, core/processor) | Bytecode transforms for `$IMPL` binary compat not applied |
| `module-services-plugin` | ~9 with `module-info.java` | `META-INF/services/` files not generated from module-info |
| ~~`maven-shade-plugin`~~ | ~~2 (grpc/protoc, bootstrap/gradle-resolver)~~ | **Fixed** — shaded JARs now produced and installed |
| Source JARs | All | `-sources.jar` not produced (blocks releases, not needed for dev) |
| `forbiddenapis` | All | Banned API usage checks not run |

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

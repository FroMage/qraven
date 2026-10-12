# Qraven

A fast build tool that reads Maven `pom.xml` files and generates a build (`build.jar`) that reads what was learned about each module from a generation state, and that can be compiled to a native binary via GraalVM. Automatically detects POM changes, handles bootstrapping, and runs the build.

## How it works

1. **Generate** -- Qraven parses all `pom.xml` files in a multi-module Maven project, resolves dependencies via Maven Resolver (Aether), and records everything it learns about each module (classpaths, compiler arguments, plugin configuration, ...) in a generation state, `target/qraven/state.cbor`. It also generates a small `Build.java` main class that names the modules to build. That class is compiled and packaged into a thin `build.jar` whose `Class-Path` manifest references `qraven-runtime` and its transitive dependencies in `~/.m2/repository`.

2. **Build** -- Running `build.jar` reads the modules from the generation state, compiles them all using the `javax.tools.JavaCompiler` API, copies resources, generates Jandex indexes, creates JARs, and installs artifacts to `~/.m2/repository`.

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

### Running the tests

The end-to-end tests generate a `build.jar` that gets `qraven-runtime` from `~/.m2`, not from
your working tree, so install the modules before running them (this is what CI does):

```bash
mvn install -DskipTests
mvn test -f tests/
```

A plain `mvn test` from the root doesn't install anything: after changing `runtime/`, the
end-to-end tests would run against the previously installed runtime.

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
  -i, --incremental         Only rebuild modules with changed sources; when poms changed, also only
                            regenerate the modules they affect
  --no-kotlin               Skip Kotlin modules and their dependents
  --no-fast-kotlin          Disable JIT warmup for Kotlin compiler (enabled by default)
  -D<key>=<value>           Set a system property

Other options:
  --dump-module <id>        Print a module's generation state as JSON (groupId:artifactId or artifactId)
  --report-bug              Interactively report a qraven issue (alias: --report-issue)
  -h, --help                Show this help message and exit
  -V, --version             Show version and exit
  -p, --project <path>      Project root directory (default: current directory)
  -t, --threads <n>         Thread count (default: available CPUs)
  -o, --output <path>       Output directory (default: <project>/target/qraven)
      --native              Compile build.jar to a native binary after generation
      --graalvm-home <path> GraalVM path (also checks GRAALVM_HOME, JAVA_HOME, PATH)
```

### Reporting a bug

`qraven --report-bug` walks you through reporting a broken build on the qraven issue tracker.
It asks for confirmation (the tracker is public), asks you to describe the problem, then shows
**exactly which files will go in a diagnostic zip** and lets you cancel before anything is created.
Your project's source code is never included, except for an optional patch of your own changes.

- **In a git repository with an upstream branch**, the zip records the remote URL and the commit
  your tree is based on (the merge-base with the upstream branch), so a maintainer can check out
  the same tree. If your tree differs from it (unpushed commits, uncommitted changes, untracked
  files), you are asked whether to include a patch of those changes.
- **Otherwise**, it includes all `pom.xml` files and a listing of the project's file names.
- **In both cases** it includes the logs (`build.log`, `generate.log`, `bootstrap.log` and the
  previous runs), the generation state and generated main class, and the qraven/JDK/OS versions.

Files that look sensitive (`.env*`, `*.pem`, `*.key`, `*.jks`, `settings.xml`, ...) are always
left out, but logs contain absolute paths from your machine, so read the list before confirming.

The issue is created with `gh` if it is installed and logged in, otherwise a pre-filled issue
form is opened in your browser. GitHub offers no API to attach files to an issue, so the zip is
written to `target/qraven/` and you are asked to drag it into the issue yourself.

### Auto-generation

Qraven automatically detects when regeneration is needed by comparing `pom.xml` timestamps against `build.jar`. Generation is triggered when:
- `build.jar` doesn't exist, or
- Any `pom.xml` in the project is newer than `build.jar`, or
- `--force-generate` is passed

Generation is skipped when:
- `--no-generate` is passed, or
- All `pom.xml` files are older than `build.jar`

### Incremental generation

With `--incremental`, a change to a pom no longer regenerates the whole build. qraven reads the generation state
(see below), finds the modules whose input poms changed (a module's own pom, its parents, and the BOMs it imports,
compared by content hash), and redoes the expensive work (building the effective model and resolving dependencies)
only for those. The steps that combine several modules are cheap and always redone, so a change to an extension is
reflected in the modules that depend on it. Only the generated build files that actually differ are rewritten and
recompiled.

On Quarkus (1,443 modules), generating after editing a pom takes about 2.5 s instead of about 18 s, whether it is a
leaf module's or a widely used extension's such as `quarkus-arc`; the modules that need it are then rebuilt (see below). The result is checked to be identical to a full regeneration: a test
generates a project, edits it, regenerates incrementally and then fully, and compares every generated file and every
module's stored data.

#### Status

Incremental generation is an opt-in feature (`--incremental`). Without it, a generation (which happens when a pom is
newer than `build.jar`) always redoes everything, and `--force-generate` always does, even together with
`--incremental`. It has been validated on the test projects and on
Quarkus, but it is newer than the full path, so if a build ever looks wrong after an incremental generation,
`--force-generate` is the first thing to try (and a bug report with `--report-bug` is welcome).

#### When it falls back to a full regeneration

qraven then prints `Incremental generation not possible (<reason>), regenerating everything`:

- **No usable previous state.** `target/qraven/state.cbor` is missing or corrupt, or was written by another qraven
  build, for another project directory, or with a different internal layout. It is also ignored when the JVM
  environment differs: the JDK, the OS, or any `-D` property that is not part of the JDK, since poms are interpolated
  and profiles activated from them.
- **The previous generated build files are missing** (`build.jar`, `build-src` or `build-classes`).
- **Modules were added or removed.** This also applies, on every run, to a project that contains a pom whose model
  fails to build, because that pom is never part of the stored state.
- **A changed module's model cannot be built.**
- **A module's coordinates or packaging changed** (groupId, artifactId, version or packaging).

If a changed pom introduces a dependency that cannot be resolved, the run fails with the unresolved dependency report,
as a full generation would, and the next run is a full one.

#### Limitations

- **Only pom changes are noticed.** What a module's generated build file says also depends on files that are not poms:
  whether it has Java, Kotlin, protobuf or ANTLR sources, a `CodeGenProvider` service file in the sources of a module
  it depends on, or the properties file of a platform BOM. Adding the first `.proto` file to a module, for example, is
  not noticed. This is the same blind spot as the rule that decides whether to regenerate at all (a pom is newer than
  `build.jar`); use `--force-generate` after such a change.
- **Resolution results are reused.** The dependencies of a module whose input poms did not change are not resolved
  again, so a SNAPSHOT that changed upstream is not picked up until the module's poms change or you use
  `--force-generate`.
- **Bootstrap modules are always regenerated in full**, since there are few of them.
- **After rebuilding qraven itself the first generation is a full one**, as the state records which build of qraven
  wrote it.

#### Planned improvements

These are known and deliberately left for later:

- Handle a removed or added module incrementally instead of regenerating everything. Today it is safe but
  conservative: the generated main class and jar are rebuilt from the new list of modules, so nothing of a removed module is left behind.
- Detect changes that are not in poms, but only for the modules whose plugins make them relevant (for example look
  for Kotlin sources only in modules with the Kotlin plugin), stopping at the first file found, so the check stays
  cheap.
- Update `build.jar` by copying the entries that did not change instead of writing the whole jar again, which is most
  of what is left of the time for a one-module change.
- Move the rest of `Build.java` (option parsing and setting up the build) into the runtime, so that the only thing
  generation produces besides the state is the list of modules.

### Rebuilding after a pom change

A pom change touches no source file, so timestamps alone can't tell an incremental build that a module has to be
rebuilt. The build therefore computes a fingerprint of each module's data (classpaths, compiler arguments,
plugin configuration, ... as it reads them from the generation state). The build records it in `<module>/target/qraven-build-data` when the module has been
built, and `--incremental` rebuilds a module whose fingerprint differs, or that has none recorded, along with its
dependents. This also covers changes made in a parent pom or a BOM, which don't touch the module's own pom. Because
of this, `-i` stays in effect after a regeneration: a pom edit rebuilds the modules it affects instead of everything.
The first incremental build after upgrading qraven rebuilds everything once, since nothing was recorded yet.

### Generation state

Generation writes `target/qraven/state.cbor`: the parsed and resolved data of every module, as a
[CBOR](https://www.rfc-editor.org/rfc/rfc8949) file (about 4 MB for Quarkus, with strings shared between modules).
It is what the build reads its modules from, and what incremental generation starts from. It can always be rebuilt
from the poms, so it is discarded when it is missing or corrupt, or was written by another qraven build or for
another project. Next to it, `generation.complete` records that the generated files go with that state: it is removed
when a generation starts and written when it ends, so an interrupted generation is never mistaken for a complete one.
`qraven --dump-module <artifactId>` prints a module's data as JSON.

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

`build.jar` reads the modules to build from `target/qraven/state.cbor`, so it has to stay next to the state generation wrote for it.

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

The binary reads the generation state at run time, which uses reflection on `ModuleInfo`; `qraven-model` ships the reachability metadata for it (`META-INF/native-image/.../reflect-config.json`). This has not been tried with GraalVM since modules became data, so report a problem if the native binary cannot read the state.

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
      BuildFileGenerator.java          # Generates the Build.java main class and the flattened poms
  model/
    pom.xml                            # Model module (small: shared by the CLI and the runtime)
    src/main/java/.../
      ModuleInfo.java                  # Module metadata (coords, deps, source dirs)
      StateStore.java, Cbor.java       # The generation state, as CBOR
  runtime/
    pom.xml                            # Runtime module (depends on Quarkus bootstrap, Jandex, Kotlin)
    src/main/java/.../runtime/
      ModuleBuild.java                 # Per-module build logic
      DataModuleBuild.java             # A ModuleBuild whose data is a module of the generation state
      ModuleStates.java                # Opens the generation state and creates the modules
      BuildRuntime.java                # Compilation, resource copying, JAR creation
      BuildOrchestrator.java           # Multi-threaded build execution
      QuarkusBuildHelper.java          # Quarkus augmentation (quarkus:build goal)
      ExtensionDescriptorHelper.java   # Extension descriptor generation
      MavenPluginDescriptorGenerator.java  # Maven plugin descriptor generation
      ProgressDisplay.java             # Terminal progress bar
      BuildStats.java                  # Build timing statistics
```

### How the pieces fit together

- **`qraven-model`** is small: `ModuleInfo` (what generation learns about a module) and the code that stores it as the generation state. Both the CLI, which writes it, and the runtime, which reads it, depend on it.

- **`qraven-cli`** has no Quarkus dependencies. It parses POMs, resolves dependencies, writes the generation state, generates the `Build.java` main class, compiles it, and packages it into a thin `build.jar`. JBang installs this module directly from its Maven GAV.

- **`qraven-runtime`** depends on Quarkus bootstrap, Jandex, and the Kotlin compiler. It is NOT shaded into the CLI. Instead, `build.jar` references it (and its transitive dependencies) via `Class-Path` manifest entries pointing to jars in `~/.m2/repository`.

- **Modules are data.** `DataModuleBuild` in `qraven-runtime` is a `ModuleBuild` whose coordinates, classpath, compiler arguments and plugin configuration come from one module of the generation state; there is no class per module. The generated `Build.java` main class opens the state, creates the modules it names and delegates to `BuildOrchestrator`. Because of that, `build.jar` is not self-contained: it reads `target/qraven/state.cbor` (the path is written into `Build.java`), so it only works while that file is there and is the one generation wrote for it.

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

- **exec-maven-plugin:java** (bound to `generate-sources`) -- Runs a code generator's `mainClass` in-process, in its own class loader, with the configured `<arguments>`, before compilation. The classpath is the project's plus, with `<includePluginDependencies>`, the plugin's `<dependencies>`: reactor modules among them are built first (without ending up on the module's compile classpath). Disabled with `-Dexec.skip`. Other phases and goals (`exec:exec`, `prepare-package`, ...) are ignored.
- **build-helper-maven-plugin:add-source** -- Adds the configured directories as source roots of the compilation (and of the Kotlin compilation, for Kotlin modules).

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
- **quarkus-platform-bom-maven-plugin:platform-properties** -- Filters the module's `platform-properties.properties` resource, checks that every key starts with `platform.` (unless `-DskipPlatformPrefixCheck`), and installs it as the `<artifactId>-<version>.properties` artifact next to the POM. The `platform.*` properties it contains are fed to `quarkus-maven-plugin:build`, including on a first build when it isn't installed yet.
- **quarkus-platform-bom-maven-plugin:flatten-platform-bom** -- Replaces the installed BOM POM with its flattened form: only the project metadata and the effective (import-expanded) `dependencyManagement`, minus `<excludeScopes>` and `<excludeArtifactKeys>` (globs), sorted alphabetically with the platform descriptor and properties first. Disabled with `-DskipPlatformBom`.

### Code style
- **formatter-maven-plugin** (Eclipse JDT) -- Formats Java source files in-place using the project's `eclipse-format.xml` config. The Eclipse JDT formatter and its dependencies are loaded at runtime from `~/.m2/repository` via a separate classloader. Disabled with `-Dno-format`.
- **impsort-maven-plugin** -- Sorts Java imports into groups: `java.`, `javax.`, `jakarta.`, `org.`, `com.`, other, then static imports. Normalizes blank lines between groups. Implemented natively (no external dependencies). Only runs when the project declares the plugin. Disabled with `-Dno-format`.
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
| generate-sources | `build-helper-maven-plugin:add-source` | Configured directories added to the compiler | **Equivalent** |
| generate-sources | `exec-maven-plugin:java` | In-process execution of the main class (`SourceGeneratorRunner`) | **Equivalent** — generate-sources only |
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

## Using with incus-spawn

[incus-spawn](https://github.com/Sanne/incus-spawn) can provision containers with qraven pre-installed.

### Tool definition

Create `~/.config/incus-spawn/tools/qraven.yaml`:

#### Released version

```yaml
name: qraven
description: Qraven build tool
run_as_user:
  - curl -Ls https://sh.jbang.dev | bash -s - app setup
  - ~/.jbang/bin/jbang app install --name qraven io.github.fromage.qraven:qraven-cli:1.8
env:
  - name: PATH
    value: "$HOME/.jbang/bin"
    strategy: prepend
    separator: ":"
```

#### Locally-built snapshot

To use a snapshot built on the host, start from the released definition above
and change the version in the `jbang app install` line to the snapshot version
(e.g. `qraven-cli:1.8-SNAPSHOT`).

The snapshot artifacts are not published anywhere, so your host-specific
template (e.g. `tpl-mydev`) must copy them from the host's `~/.m2` into the
container, and reference the tool:

```yaml
tools:
  - qraven
host-resources:
  - source: ~/.m2/repository/io/github/fromage/qraven
    target: /home/agentuser/.m2/repository/io/github/fromage/qraven
    mode: copy
```

Copied resources end up owned by root, and `host-resources` has no option to
set the owner, so the tool definition must also fix the ownership of the `.m2`
directory. Add this to the tool definition:

```yaml
run:
  - chown -R agentuser:agentuser /home/agentuser/.m2
```

### Quarkus template

In your Quarkus template, use `qraven` as the repo prime command to generate
`build.jar` when the container is first created:

```yaml
repos:
  - url: https://github.com/quarkusio/quarkus.git
    path: ~/quarkus
    prime: qraven
agent_note: |
  The quarkus project uses qraven, not maven. To build, run `qraven --incremental`
  instead of `mvn package`: it only rebuilds modules with changed sources, and
  their dependents. A plain `qraven` does a full build, which is only needed
  the first time. You can also use `qraven -pl` with a project list and `-am`
  for building dependencies just like Maven, but incremental mode is usually
  better because it builds dependents too. Qraven does not support running
  tests, so once you have built the project/modules, use `mvn` to run the tests
  as normal, just do not use it for compiling/installing because it is much slower
  than qraven.

  Qraven shows a progress bar by default. When running non-interactively, pass
  `-Dno-progress` to disable it. The full build output is always written to
  `target/qraven/build.log` (in the project root), so on failure grep that file
  for errors rather than re-running the build. `generate.log` and `bootstrap.log` hold
  the other phases, and the previous 20 runs of each log are kept in `target/qraven/logs/`
  (`build.1.log` is the most recent). The exit status can be trusted:
  0 means the build succeeded, non-zero means it failed.
```

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

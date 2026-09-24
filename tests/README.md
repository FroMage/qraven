# qraven Test Suite

134 tests across 8 test classes, using 14 real Maven test projects.

## Running Tests

```bash
mvn test -f tests/                          # Run all tests
mvn test -f tests/ -Dtest=PomParserTest     # Run one test class
mvn test -f tests/ -Dtest="PomParserTest\$SimpleJar"  # Run one nested class
```

Prerequisites: `mvn install -DskipTests -pl runtime,cli` (and parent POM `mvn install -N`).

## Test Classes

| Class | Tests | What it covers |
|-------|-------|----------------|
| `PomParserTest` | 89 | GAV, packaging, sources, resources, filtering, jandex, manifest, compiler config, shade, test sources, test-jar deps, annotation processors, Quarkus extension metadata, Kotlin/protobuf/ANTLR detection |
| `BuildRuntimeTest` | 13 | compile (basic, flags, empty, AP, test sources, reactor deps), copyResources, copyResourcesFiltered, createJar, generateJandexIndex, install |
| `EndToEndBuildTest` | 8 | Full pipeline (parse -> generate -> build.jar -> execute) for 7 test projects + `-pl`/`-am` filtering |
| `BuildOrchestratorTest` | 6 | `-pl` filtering, `-am` also-make, multi-module selection, parallel threads, success reporting, transitive deps |
| `CliTest` | 6 | `--help`, `--version`, `--no-build`, project dir, thread count, `--force-generate` |
| `CodeStyleHelperTest` | 5 | Import group ordering, static-first, blank line separation, code preservation, no-op |
| `BuildFileGeneratorTest` | 4 | build.jar generation for simple/multi-module, source file counts |
| `IncrementalBuildTest` | 3 | Skip up-to-date modules, rebuild on source change, rebuild dependents |

## Test Projects

All under `test-projects/` in the repo root. Each is a standalone Maven project (not a qraven module).

| Project | Features exercised |
|---------|-------------------|
| `simple-jar/` | Single-module JAR: GAV parsing, compile, jar, install |
| `multi-module/` | 3 children with inter-deps, parent property inheritance, dependencyManagement |
| `resources-and-filtering/` | Plain + filtered resources, `${property}` interpolation, custom resource dirs |
| `jandex-project/` | Jandex plugin detection, index generation |
| `manifest-entries/` | maven-jar-plugin manifestEntries extraction |
| `compiler-config/` | `--release`, `-parameters`, extra compilerArgs |
| `shade-project/` | maven-shade-plugin: attached, classifier, includes, filters, mainClass |
| `test-compilation/` | 3-module: test sources, test-scoped reactor deps (lib-testutils) |
| `test-jar-dependency/` | 2-module: `<type>test-jar</type>` dependency |
| `with-annotation-processor/` | processor + app with annotationProcessorPaths |
| `fake-quarkus-extension/` | runtime with quarkus-extension-maven-plugin + deployment module |
| `kotlin-project/` | Multi-module Kotlin: kotlin-maven-plugin, compilerPlugins, pluginOptions, mixed Java+Kotlin |
| `protobuf-project/` | protobuf-maven-plugin: compile/test-compile/compile-custom, gRPC, Mutiny |
| `antlr-project/` | antlr4-maven-plugin: .g4 grammar, visitor=true |

## Coverage Gaps

### Not tested (needs external toolchains or real Quarkus)

| Component | Gap | Reason |
|-----------|-----|--------|
| QuarkusBuildHelper | bootstrap(), generateCode(), run() | Needs real Quarkus runtime on classpath |
| ExtensionDescriptorHelper | quarkus-extension.yaml generation | Needs real Quarkus extension jars for validation |
| MavenPluginDescriptorGenerator | plugin.xml from @Mojo annotations | Needs maven-plugin packaging + real annotation processing |
| BannedDependencyChecker | Rule loading + checking | Needs Quarkus project enforcer-rules XML |

### Partially tested

| Component | Covered | Missing |
|-----------|---------|---------|
| BuildRuntime | compile, resources, jar, jandex, install | clean(), compileKotlin(), compileProtobuf(), compileAntlr(), warmupClasspath(), warmupKotlin(), installClassified(), AP cache |
| CodeStyleHelper | sortImports() | formatJavaFiles() (Eclipse JDT), formatKotlinFiles() (ktfmt) |
| BuildOrchestrator | Filtering, parallel, also-make | --no-kotlin flag, cascade failure reporting |
| CLI | 6 code paths | --no-generate, --native, --force-bootstrap, -D flags, bootstrap detection |
| BuildFileGenerator | generate + package | Bootstrap mode (setPreBuiltModules()), generated source content validation |
| EndToEnd | 7 projects | shade-project (parsing covered, no E2E) |

### Summary

The Java-only pipeline (parse, generate, compile, resources, jar, install, incremental, filtering) is well covered. PomParser detection of Kotlin, protobuf, and ANTLR is tested. The main blind spots are:
1. Kotlin/Protobuf/ANTLR **compilation** (needs those toolchains available at test time)
2. Quarkus-specific features (needs real Quarkus on the classpath)
3. Java/Kotlin **formatting** (needs Eclipse JDT and ktfmt)

## Architecture

- Tests use real Maven test projects, not @TempDir-generated POMs
- PomParser tests instantiate a real DependencyResolver + PomParser
- BuildRuntime tests use @TempDir for output, real test project sources as input
- EndToEnd tests generate build.jar and run it as a subprocess
- CLI tests call QravenCli.main() in-process with captured stdout/stderr
- The tests module skips deploy, install, source-jar, and javadoc to prevent accidental publishing

package io.github.fromage.qraven.hardcoded;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

public class QravenCli {

    private static final String ERASE_LINE = Status.ERASE_LINE;
    private static final String RUNTIME_GROUP_ID = "io.github.fromage.qraven";
    private static final String RUNTIME_ARTIFACT_ID = "qraven-runtime";
    private static final String RUNTIME_VERSION = loadVersion();

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (DependencyResolver.UnresolvedDependenciesException e) {
            System.err.print(ERASE_LINE);
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Nothing was built. Details are in target/qraven/generate.log.");
            printReportBugHint();
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            printReportBugHint();
            System.exit(1);
        }
    }

    private static PomParser newParser(Path projectDir, DependencyResolver resolver, PhaseTimings timings,
            int threads) {
        PomParser parser = new PomParser(projectDir, resolver);
        parser.setPhaseTimings(timings);
        parser.setThreads(threads);
        parser.setProgressListener((phase, detail, current, total) -> {
            String msg = switch (phase) {
                case "scan" -> "Scanning modules... " + current + " found (" + detail + ")";
                case "resolve" -> "Resolving " + current + "/" + total + " (" + detail + ")";
                default -> phase + ": " + detail;
            };
            System.err.print(ERASE_LINE + "  " + msg);
        });
        return parser;
    }

    private static final String STATE_FILE = "state.cbor";

    /**
     * Identifies this build of qraven: a state written by another build is not reused, since the logic that derived
     * it may differ even when the version string is the same (every development build says 1.9-SNAPSHOT).
     */
    private static final String STATE_VERSION = RUNTIME_VERSION + "/" + buildTimestamp();

    private static String buildTimestamp() {
        try (var is = QravenCli.class.getResourceAsStream("/qraven-build.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String ts = props.getProperty("build.timestamp");
                if (ts != null && !ts.isBlank()) {
                    return ts;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return "unknown";
    }

    /** The generation state is a cache; failing to write it must never fail the generation. */
    private static void writeState(Path outputDir, Path projectDir, String generationKey, List<ModuleInfo> modules) {
        try {
            StateStore.write(outputDir.resolve(STATE_FILE), STATE_VERSION, projectDir, generationKey, modules);
        } catch (Exception e) {
            System.err.println("WARNING: could not write the generation state: " + e);
        }
    }

    private static final String COMPLETE_FILE = "generation.complete";

    private static String stateDigest(Path stateFile) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(stateFile)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Records that the generated files are complete and go with the current state. The state is written as soon as
     * the modules are known, since the bootstrap build reads it before the main build is packaged; this marker is
     * what says generation then ran to the end, so a crash or --no-build never leaves a state that claims more than
     * was generated.
     */
    private static void markGenerationComplete(Path outputDir) {
        try {
            Files.writeString(outputDir.resolve(COMPLETE_FILE), stateDigest(outputDir.resolve(STATE_FILE)));
        } catch (IOException e) {
            System.err.println("WARNING: could not record that generation completed: " + e);
        }
    }

    private static boolean generationComplete(Path outputDir) {
        try {
            Path marker = outputDir.resolve(COMPLETE_FILE);
            return Files.exists(marker)
                    && Files.readString(marker).equals(stateDigest(outputDir.resolve(STATE_FILE)));
        } catch (IOException e) {
            return false;
        }
    }

    private static int dumpModule(Path stateFile, String module) {
        var state = StateStore.read(stateFile, null, null, null);
        if (state.isEmpty()) {
            System.err.println("No usable generation state at " + stateFile
                    + " (run qraven first, or it was written by an incompatible qraven).");
            return 1;
        }
        var found = state.get().find(module);
        if (found.isEmpty()) {
            System.err.println("No module '" + module + "' in the generation state.");
            return 1;
        }
        System.out.print(StateStore.toJson(found.get()));
        return 0;
    }

    /** Printed on stderr when qraven fails, so users who think it should not have failed know what to do. */
    private static void printReportBugHint() {
        System.err.println();
        System.err.println("If this build should not have failed (for example, it works with Maven), it may be a");
        System.err.println("qraven bug. Run `qraven --report-bug` from the same project to report it: it shows");
        System.err.println("exactly what would be collected and asks for confirmation before anything is created.");
    }

    private static void run(String[] args) throws Exception {
        Path projectDir = Path.of(".").toAbsolutePath().normalize();
        int threads = Runtime.getRuntime().availableProcessors();
        Path outputDir = null;
        boolean buildNative = false;
        String graalvmHome = null;

        boolean forceGenerate = false;
        boolean noGenerate = false;
        boolean noBuild = false;
        boolean forceBootstrap = false;
        boolean quickly = false;
        boolean noQuickly = false;
        boolean reportBug = false;
        String dumpModule = null;
        boolean incremental = false;

        List<String> commonBuildArgs = new ArrayList<>();
        List<String> mainBuildArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> { printHelp(); return; }
                case "--version", "-V" -> { printVersion(); return; }
                case "--report-bug", "--report-issue" -> reportBug = true;
                case "--dump-module" -> dumpModule = args[++i];
                case "--project", "-p" -> projectDir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--threads", "-t" -> {
                    threads = Integer.parseInt(args[++i]);
                    commonBuildArgs.add("-t");
                    commonBuildArgs.add(String.valueOf(threads));
                }
                case "--output", "-o" -> outputDir = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--native" -> buildNative = true;
                case "--graalvm-home" -> graalvmHome = args[++i];
                case "--force-generate", "-fg" -> forceGenerate = true;
                case "--no-generate", "-ng" -> noGenerate = true;
                case "--no-build", "-nb" -> noBuild = true;
                case "--force-bootstrap", "-fb" -> forceBootstrap = true;
                case "--quickly" -> quickly = true;
                case "--no-quickly" -> noQuickly = true;
                case "--projects", "-pl" -> {
                    mainBuildArgs.add("-pl");
                    mainBuildArgs.add(args[++i]);
                }
                case "--also-make", "-am" -> mainBuildArgs.add("-am");
                case "--incremental", "-i" -> {
                    commonBuildArgs.add("-i");
                    incremental = true;
                }
                case "--no-kotlin" -> commonBuildArgs.add("--no-kotlin");
                case "--no-fast-kotlin" -> commonBuildArgs.add("--no-fast-kotlin");
                default -> {
                    if (args[i].startsWith("-D")) {
                        commonBuildArgs.add(args[i]);
                    } else {
                        System.err.println("Unknown option: " + args[i]);
                        System.err.println("Run with --help for usage information.");
                        System.exit(1);
                    }
                }
            }
        }

        if (outputDir == null) {
            outputDir = projectDir.resolve("target/qraven");
        }

        if (dumpModule != null) {
            System.exit(dumpModule(outputDir.resolve(STATE_FILE), dumpModule));
        }

        if (reportBug) {
            System.exit(new BugReporter(projectDir, outputDir,
                    new java.io.BufferedReader(new java.io.InputStreamReader(System.in)), System.out,
                    BugReporter.defaultSubmitter(System.out), RUNTIME_VERSION, List.of(args)).run());
        }

        if (!noQuickly && !quickly && isQuarkusProject(projectDir)) {
            quickly = true;
        }
        if (quickly && !noQuickly) {
            commonBuildArgs.add("-DskipTests");
            commonBuildArgs.add("-DskipITs");
            commonBuildArgs.add("-Dquarkus.build.skip");
        }

        Path nativeImageBin = null;
        if (buildNative) {
            nativeImageBin = resolveNativeImage(graalvmHome);
        }

        System.out.println("Qraven Build Tool");
        System.out.println("Project:  " + projectDir);
        System.out.println("Threads:  " + threads);
        System.out.println("Output:   " + outputDir);
        if (buildNative) {
            System.out.println("Native:   yes");
        }
        System.out.println();

        DependencyResolver resolver = new DependencyResolver();
        Path buildJar = outputDir.resolve("build.jar");
        Path bootstrapJar = outputDir.resolve("bootstrap.jar");
        boolean needsBootstrapRun = false;
        BuildFileGenerator deferredMainGen = null;
        Runnable pendingStateWrite = null;

        // Phase 1: Generation
        long regenCheckStart = System.currentTimeMillis();
        String regenReason = noGenerate ? null : (forceGenerate ? "forced" : regenerationReason(projectDir, buildJar));
        long regenCheckMs = System.currentTimeMillis() - regenCheckStart;
        boolean shouldGenerate = regenReason != null;

        if (shouldGenerate) {
            System.out.println("Regenerating: " + regenReason + " (" + regenCheckMs + "ms)");
            long totalStart = System.currentTimeMillis();

            PhaseTimings timings = new PhaseTimings();
            PomParser parser = newParser(projectDir, resolver, timings, threads);
            Path stateFile = outputDir.resolve(STATE_FILE);
            String generationKey = GenerationEnvironment.key();

            // --incremental: bring the previous generation up to date instead of redoing all of it
            List<ModuleInfo> modules = null;
            boolean incrementalGeneration = false;
            if (incremental && !forceGenerate) {
                String why;
                var state = StateStore.read(stateFile, STATE_VERSION, projectDir, generationKey);
                if (state.isEmpty()) {
                    why = "no usable previous generation state";
                } else if (!generationComplete(outputDir)) {
                    why = "the previous generation did not complete";
                } else if (!Files.exists(buildJar) || !Files.isDirectory(outputDir.resolve("build-src"))
                        || !Files.isDirectory(outputDir.resolve("build-classes"))) {
                    why = "the previous generated build files are missing";
                } else {
                    var result = parser.parseIncremental(state.get());
                    if (result.usable()) {
                        modules = result.modules();
                        incrementalGeneration = true;
                        System.err.print(ERASE_LINE);
                        System.out.println("Incremental generation: " + result.recomputed().size() + " of "
                                + modules.size() + " modules recomputed"
                                + (result.recomputed().isEmpty() ? "" : " ("
                                + result.recomputed().stream().limit(8).collect(Collectors.joining(", "))
                                + (result.recomputed().size() > 8 ? ", ..." : "") + ")"));
                    }
                    why = result.fallbackReason();
                }
                if (modules == null) {
                    System.out.println("Incremental generation not possible (" + why
                            + "), regenerating everything");
                    resolver.clearUnresolved();
                    parser = newParser(projectDir, resolver, timings, threads);
                }
            }
            // from here on the generated files change; they only go with the state again once this generation has
            // completed
            Files.deleteIfExists(outputDir.resolve(COMPLETE_FILE));
            if (modules == null) {
                modules = parser.parseProject();
                System.err.print(ERASE_LINE);
                System.out.println("Scanned " + modules.size() + " modules in " + parser.getScanTimeMs() + "ms");
                System.out.println("Resolved dependencies in " + parser.getResolveTimeMs() + "ms");
            }
            System.out.println(resolver.resolutionCacheStats());

            List<String> warnings = parser.getWarnings();
            if (!warnings.isEmpty()) {
                for (String w : warnings) {
                    System.err.println("WARNING: " + w);
                }
            }

            timings.reset();
            writeState(outputDir, projectDir, generationKey, modules);
            timings.lap("write state");
            final Path generatedIn = outputDir;
            final PhaseTimings generationTimings = timings;
            List<ModuleInfo> bootstrapModules = detectBootstrapModules(modules, resolver);
            timings.lap("detect bootstrap modules");
            boolean bootstrapRequired = !bootstrapModules.isEmpty()
                    && (forceBootstrap || bootstrapNeeded(bootstrapModules, resolver));
            timings.lap("check bootstrap jars");

            if (bootstrapRequired) {
                needsBootstrapRun = true;

                Set<String> bootstrapIds = bootstrapModules.stream()
                        .map(ModuleInfo::getArtifactId)
                        .collect(Collectors.toSet());
                List<ModuleInfo> mainModules = modules.stream()
                        .filter(m -> !bootstrapIds.contains(m.getArtifactId()))
                        .toList();

                System.out.println();
                System.out.println("Bootstrap needed: " + bootstrapModules.size() + " modules");
                for (ModuleInfo m : bootstrapModules) {
                    String status = bootstrapStatus(m, resolver);
                    System.out.println("  " + m.getArtifactId() + " (" + (status != null ? status : "up to date") + ")");
                }

                long stepStart = System.currentTimeMillis();
                BuildFileGenerator bootstrapGen = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                bootstrapGen.setPhaseTimings(timings);
                bootstrapGen.setStateWrittenByCaller(true);
                bootstrapGen.generate(bootstrapModules, "bootstrap");
                bootstrapGen.compileAndPackage("bootstrap");
                timings.lap("compile bootstrap.jar");
                System.out.println("Generated bootstrap.jar (" + bootstrapModules.size() + " modules) in " +
                        (System.currentTimeMillis() - stepStart) + "ms");

                stepStart = System.currentTimeMillis();
                deferredMainGen = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                deferredMainGen.setPhaseTimings(timings);
                deferredMainGen.setStateWrittenByCaller(true);
                deferredMainGen.setIncremental(incrementalGeneration);
                deferredMainGen.setPreBuiltModules(bootstrapModules);
                deferredMainGen.setProgressListener((detail, current, total) ->
                        System.err.print(ERASE_LINE + "  Generating " + current + "/" + total + " (" + detail + ")"));
                deferredMainGen.generate(mainModules, "build");
                System.err.print(ERASE_LINE);
                System.out.println("Prepared the main build of " + mainModules.size() + " modules in " +
                        (System.currentTimeMillis() - stepStart) + "ms");
                System.out.println("  (build.jar will be packaged after bootstrap runs)");
                // the generated files are only complete once build.jar is packaged, after the bootstrap build
                pendingStateWrite = () -> markGenerationComplete(generatedIn);
            } else {
                if (!bootstrapModules.isEmpty()) {
                    System.out.println("Bootstrap modules found but jars are fresh — including in main build");
                }

                long stepStart = System.currentTimeMillis();
                BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                generator.setPhaseTimings(timings);
                generator.setStateWrittenByCaller(true);
                generator.setIncremental(incrementalGeneration);
                generator.setProgressListener((detail, current, total) ->
                        System.err.print(ERASE_LINE + "  Generating " + current + "/" + total + " (" + detail + ")"));
                generator.generate(modules, "build");
                System.err.print(ERASE_LINE);

                System.out.println("Prepared the build of " + modules.size() + " modules in " +
                        (System.currentTimeMillis() - stepStart) + "ms");

                stepStart = System.currentTimeMillis();
                System.err.print("  Compiling and packaging build.jar...");
                generator.compileAndPackage("build");
                timings.lap("compile + package build.jar");
                System.err.print(ERASE_LINE);
                System.out.println("Packaged build.jar in " + (System.currentTimeMillis() - stepStart) + "ms");

                Files.deleteIfExists(bootstrapJar);
                markGenerationComplete(generatedIn);
            }

            savePomPaths(outputDir, modules, projectDir);

            System.out.println();
            System.out.println("Total generation time: " + (System.currentTimeMillis() - totalStart) + "ms");
            timings.lap("other");
            System.out.println(timings.report());
            try {
                Files.writeString(projectDir.resolve("target/qraven/generate.log"), "\n" + timings.report(),
                        java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // best effort
            }
        } else if (!noGenerate) {
            System.out.println("Build files up to date, skipping generation (" + regenCheckMs + "ms)");
        }

        // Phase 2: Native image (optional)
        if (buildNative) {
            long stepStart = System.currentTimeMillis();
            Path nativeBinary = outputDir.resolve("build");
            compileNativeImage(nativeImageBin, buildJar, nativeBinary);
            System.out.println("Native image compiled in " +
                    ((System.currentTimeMillis() - stepStart) / 1000) + "s");
            System.out.println("Binary: " + nativeBinary);
            System.out.println();
        }

        // Phase 3: Build execution
        if (!noBuild) {
            if (needsBootstrapRun) {
                System.out.println();
                System.out.println("Running bootstrap build...");
                System.out.println();
                int exitCode = runBuild(bootstrapJar, projectDir, commonBuildArgs);
                if (exitCode != 0) {
                    System.err.println("Bootstrap build failed with exit code " + exitCode);
                    printReportBugHint();
                    System.exit(exitCode);
                }
                System.out.println();
                System.out.println("Bootstrap build completed");

                if (deferredMainGen != null) {
                    long stepStart = System.currentTimeMillis();
                    System.err.print("  Packaging build.jar...");
                    deferredMainGen.compileAndPackage("build");
                    System.err.print(ERASE_LINE);
                    System.out.println("Packaged build.jar in " + (System.currentTimeMillis() - stepStart) + "ms");
                    deferredMainGen = null;
                    if (pendingStateWrite != null) {
                        pendingStateWrite.run();
                        pendingStateWrite = null;
                    }
                }
                System.out.println();
            }

            if (!Files.exists(buildJar)) {
                System.err.println("Error: build.jar not found at " + buildJar);
                System.err.println("Run without --no-generate to create it.");
                System.exit(1);
            }

            // -i stays after a regeneration: each module records the build data it was built with, so a pom
            // change that altered a module's build data rebuilds exactly that module (and its dependents)
            List<String> allBuildArgs = new ArrayList<>(commonBuildArgs);
            allBuildArgs.addAll(mainBuildArgs);

            System.out.println("Running build...");
            System.out.println();
            int exitCode = runBuild(buildJar, projectDir, allBuildArgs);
            if (exitCode != 0) {
                printReportBugHint();
            }
            System.exit(exitCode);
        } else {
            System.out.println();
            if (deferredMainGen != null) {
                System.out.println("Bootstrap must run before build.jar can be packaged.");
                System.out.println("Run without --no-build, or run bootstrap manually:");
                System.out.println("  java -jar " + projectDir.relativize(bootstrapJar));
                System.out.println("then re-run qraven to package build.jar.");
            } else {
                Path relativeJar = projectDir.relativize(buildJar);
                if (buildNative) {
                    System.out.println("To build the project, run:");
                    System.out.println("  JAVA_HOME=$GRAALVM_HOME " + projectDir.relativize(outputDir.resolve("build")));
                } else {
                    System.out.println("To build the project, run:");
                    System.out.println("  java -jar " + relativeJar);
                }
            }
        }
    }

    private static List<ModuleInfo> detectBootstrapModules(List<ModuleInfo> allModules, DependencyResolver resolver) {
        Path runtimePom = resolver.resolvePom(RUNTIME_GROUP_ID, RUNTIME_ARTIFACT_ID, RUNTIME_VERSION);
        if (runtimePom == null) {
            return List.of();
        }

        Set<String> seedIds = new LinkedHashSet<>();
        try (var input = Files.newInputStream(runtimePom)) {
            Model model = new MavenXpp3Reader().read(input);
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                seedIds.add(dep.getArtifactId());
            }
        } catch (Exception e) {
            return List.of();
        }

        Map<String, ModuleInfo> moduleMap = allModules.stream()
                .collect(Collectors.toMap(ModuleInfo::getArtifactId, m -> m, (a, b) -> a));

        Set<String> reactorSeeds = seedIds.stream()
                .filter(moduleMap::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (reactorSeeds.isEmpty()) {
            return List.of();
        }

        Set<String> visited = new LinkedHashSet<>();
        LinkedList<String> queue = new LinkedList<>(reactorSeeds);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) continue;
            ModuleInfo module = moduleMap.get(id);
            if (module == null) continue;
            for (String dep : module.getReactorDependencies()) {
                if (!visited.contains(dep)) {
                    queue.add(dep);
                }
            }
            if (module.getParentArtifactId() != null && !visited.contains(module.getParentArtifactId())) {
                queue.add(module.getParentArtifactId());
            }
        }

        return allModules.stream()
                .filter(m -> visited.contains(m.getArtifactId()))
                .toList();
    }

    private static boolean bootstrapNeeded(List<ModuleInfo> bootstrapModules, DependencyResolver resolver) {
        for (ModuleInfo module : bootstrapModules) {
            if (bootstrapStatus(module, resolver) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why a bootstrap module needs rebuilding: "MISSING" if it is not installed in the local repository, "STALE"
     * if it is installed but older than its sources (or, for pom-packaged modules, its pom); null if up to date.
     * Pom-packaged modules have no jar, so they are looked up by their installed pom.
     */
    public static String bootstrapStatus(ModuleInfo module, DependencyResolver resolver) {
        boolean pomPackaging = "pom".equals(module.getPackaging());
        String installed = resolver.resolveArtifactPath(module.getGroupId(), module.getArtifactId(),
                module.getVersion(), pomPackaging ? "pom" : "jar");
        if (installed == null) {
            return "MISSING";
        }
        try {
            long installedTime = Files.getLastModifiedTime(Path.of(installed)).toMillis();
            if (pomPackaging) {
                return module.getPomFile() != null
                        && Files.getLastModifiedTime(module.getPomFile()).toMillis() > installedTime ? "STALE" : null;
            }
            return hasNewerSources(module.getBaseDir(), installedTime) ? "STALE" : null;
        } catch (IOException e) {
            return "STALE";
        }
    }

    private static boolean hasNewerSources(Path moduleDir, long referenceTime) {
        Path srcDir = moduleDir.resolve("src");
        if (!Files.isDirectory(srcDir)) return false;
        try (var stream = Files.walk(srcDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() > referenceTime;
                        } catch (IOException e) {
                            return true;
                        }
                    });
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isQuarkusProject(Path projectDir) {
        Path pom = projectDir.resolve("pom.xml");
        if (!Files.exists(pom)) return false;
        try {
            String content = Files.readString(pom);
            return content.contains("<groupId>io.quarkus</groupId>");
        } catch (IOException e) {
            return false;
        }
    }

    private static void savePomPaths(Path outputDir, List<ModuleInfo> modules, Path projectDir) {
        try {
            List<String> relativePaths = modules.stream()
                    .map(m -> projectDir.relativize(m.getPomFile()).toString())
                    .toList();
            Files.writeString(outputDir.resolve("pom-paths.txt"), String.join("\n", relativePaths) + "\n");
        } catch (IOException e) {
            // non-fatal: next check will fall back to tree walk
        }
    }

    private static String regenerationReason(Path projectDir, Path buildJar) {
        if (!Files.exists(buildJar)) return "build.jar not found";
        try {
            long buildJarTime = Files.getLastModifiedTime(buildJar).toMillis();

            try (var is = QravenCli.class.getResourceAsStream("/qraven-build.properties")) {
                if (is != null) {
                    var props = new java.util.Properties();
                    props.load(is);
                    String ts = props.getProperty("build.timestamp");
                    if (ts != null) {
                        long cliBuildTime = java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli();
                        if (cliBuildTime > buildJarTime) {
                            return "qraven CLI updated";
                        }
                    }
                }
            }

            Path pomPathsFile = buildJar.getParent().resolve("pom-paths.txt");
            if (Files.exists(pomPathsFile)) {
                return checkKnownPoms(projectDir, pomPathsFile, buildJarTime);
            }
            return walkForChangedPoms(projectDir, buildJarTime);
        } catch (IOException e) {
            return "error checking: " + e.getMessage();
        }
    }

    private static String checkKnownPoms(Path projectDir, Path pomPathsFile, long buildJarTime) throws IOException {
        for (String line : Files.readAllLines(pomPathsFile)) {
            if (line.isBlank()) continue;
            Path pom = projectDir.resolve(line);
            if (!Files.exists(pom)) {
                return "pom removed: " + line;
            }
            if (Files.getLastModifiedTime(pom).toMillis() > buildJarTime) {
                return "pom changed: " + line;
            }
        }
        return null;
    }

    private static String walkForChangedPoms(Path projectDir, long buildJarTime) throws IOException {
        String[] result = new String[1];
        Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("pom.xml")
                        && attrs.lastModifiedTime().toMillis() > buildJarTime) {
                    result[0] = "pom changed: " + projectDir.relativize(file);
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result[0];
    }

    private static int runBuild(Path jarFile, Path workDir, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.add("-jar");
        cmd.add(jarFile.toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.directory(workDir.toFile());
        return pb.start().waitFor();
    }

    private static void printHelp() {
        System.out.println("""
                qraven - Fast build tool for Maven projects

                Generates a self-contained build.jar from pom.xml files, then executes the
                build. Automatically detects when regeneration is needed (pom files changed)
                and supports bootstrapping for projects that contain qraven's own dependencies.

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
                  -i, --incremental         Only rebuild modules with changed sources, and when poms changed,
                                            only regenerate the modules they affect (falls back to a full
                                            regeneration when that isn't possible; see --force-generate)
                  --no-kotlin               Skip Kotlin modules and their dependents
                  --no-fast-kotlin          Disable JIT warmup for Kotlin compiler (enabled by default)
                  -D<key>=<value>           Set a system property

                Other options:
                  --dump-module <id>        Print a module's generation state as JSON (groupId:artifactId or
                                            artifactId), from the last generation
                  --report-bug              Interactively report a qraven issue (shows what will be
                                            collected and asks for confirmation; alias: --report-issue)
                  -h, --help                Show this help message and exit
                  -V, --version             Show version and exit
                  -p, --project <path>      Project root directory (default: current directory)
                  -t, --threads <n>         Thread count (default: available CPUs)
                  -o, --output <path>       Output directory (default: <project>/target/qraven)
                      --native              Compile build.jar to a native binary after generation
                      --graalvm-home <path>  GraalVM path (also checks GRAALVM_HOME, JAVA_HOME, PATH)

                Examples:
                  qraven                        Auto-generate if needed, then build
                  qraven --quickly               Build skipping tests
                  qraven -pl quarkus-arc -am     Build one module and its dependencies
                  qraven --no-build              Generate only, don't run the build
                  qraven --force-bootstrap       Force rebuild of bootstrap modules
                  qraven -fg --quickly           Force regeneration, skip tests
                  qraven -i                      Incremental build (only changed modules)
                """);
    }

    private static void printVersion() {
        System.out.println("qraven " + RUNTIME_VERSION);
        try (var is = QravenCli.class.getResourceAsStream("/qraven-build.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String ts = props.getProperty("build.timestamp");
                if (ts != null && !ts.isBlank()) {
                    var instant = java.time.OffsetDateTime.parse(ts).toInstant();
                    var formatted = java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss z")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(instant);
                    System.out.println("Built on " + formatted);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static String loadVersion() {
        try (var is = QravenCli.class.getResourceAsStream("/qraven-build.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String v = props.getProperty("build.version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception e) {
            // fall through
        }
        return "dev";
    }

    private static Path resolveNativeImage(String graalvmHome) {
        if (graalvmHome != null) {
            Path bin = Path.of(graalvmHome, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
            throw new RuntimeException("native-image not found at " + bin);
        }
        String envGraalvm = System.getenv("GRAALVM_HOME");
        if (envGraalvm != null) {
            Path bin = Path.of(envGraalvm, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
        }
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null) {
            Path bin = Path.of(envJavaHome, "bin", "native-image");
            if (Files.isExecutable(bin)) return bin;
        }
        try {
            Process p = new ProcessBuilder("which", "native-image").start();
            if (p.waitFor() == 0) {
                String path = new String(p.getInputStream().readAllBytes()).trim();
                if (!path.isEmpty()) return Path.of(path);
            }
        } catch (Exception e) {
            // fall through
        }
        throw new RuntimeException(
                "native-image not found. Set --graalvm-home, GRAALVM_HOME, or add native-image to PATH");
    }

    private static void compileNativeImage(Path nativeImageBin, Path buildJar, Path outputBinary) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(nativeImageBin.toString());
        cmd.add("-jar");
        cmd.add(buildJar.toString());
        cmd.add("-H:IncludeResourceBundles=com.sun.tools.javac.resources.compiler,com.sun.tools.javac.resources.javac,com.sun.tools.javac.resources.launcher");
        cmd.add("--initialize-at-build-time=com.sun.tools.javac");
        cmd.add("-H:+AllowJRTFileSystem");
        cmd.add("-march=native");
        cmd.add(outputBinary.toString());

        System.out.println("  " + String.join(" \\\n    ", cmd));
        System.out.println();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("  " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("native-image failed with exit code " + exitCode);
        }
    }
}

package io.quarkiverse.qraven.hardcoded;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final String ERASE_LINE = "\r[2K";
    private static final String RUNTIME_GROUP_ID = "io.quarkiverse.qraven";
    private static final String RUNTIME_ARTIFACT_ID = "qraven-runtime";
    private static final String RUNTIME_VERSION = "1.0-SNAPSHOT";

    public static void main(String[] args) throws Exception {
        Path projectDir = Path.of(".").toAbsolutePath().normalize();
        int threads = Runtime.getRuntime().availableProcessors();
        Path outputDir = null;
        boolean buildNative = false;
        String graalvmHome = null;

        boolean forceGenerate = false;
        boolean noGenerate = false;
        boolean noBuild = false;
        boolean forceBootstrap = false;

        List<String> commonBuildArgs = new ArrayList<>();
        List<String> mainBuildArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> { printHelp(); return; }
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
                case "--quickly" -> {
                    commonBuildArgs.add("-DskipTests");
                    commonBuildArgs.add("-DskipITs");
                    commonBuildArgs.add("-Dquarkus.build.skip");
                }
                case "--projects", "-pl" -> {
                    mainBuildArgs.add("-pl");
                    mainBuildArgs.add(args[++i]);
                }
                case "--also-make", "-am" -> mainBuildArgs.add("-am");
                case "--incremental", "-i" -> commonBuildArgs.add("-i");
                case "--no-kotlin" -> commonBuildArgs.add("--no-kotlin");
                case "--fast-kotlin" -> commonBuildArgs.add("--fast-kotlin");
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

        // Phase 1: Generation
        long regenCheckStart = System.currentTimeMillis();
        String regenReason = noGenerate ? null : (forceGenerate ? "forced" : regenerationReason(projectDir, buildJar));
        long regenCheckMs = System.currentTimeMillis() - regenCheckStart;
        boolean shouldGenerate = regenReason != null;

        if (shouldGenerate) {
            System.out.println("Regenerating: " + regenReason + " (" + regenCheckMs + "ms)");
            long totalStart = System.currentTimeMillis();

            PomParser parser = new PomParser(projectDir, resolver);
            parser.setThreads(threads);
            parser.setProgressListener((phase, detail, current, total) -> {
                String msg = switch (phase) {
                    case "scan" -> "Scanning modules... " + current + " found (" + detail + ")";
                    case "resolve" -> "Resolving " + current + "/" + total + " (" + detail + ")";
                    default -> phase + ": " + detail;
                };
                System.err.print(ERASE_LINE + "  " + msg);
            });
            List<ModuleInfo> modules = parser.parseProject();
            System.err.print(ERASE_LINE);

            System.out.println("Scanned " + modules.size() + " modules in " + parser.getScanTimeMs() + "ms");
            System.out.println("Resolved dependencies in " + parser.getResolveTimeMs() + "ms");

            List<String> warnings = parser.getWarnings();
            if (!warnings.isEmpty()) {
                for (String w : warnings) {
                    System.err.println("WARNING: " + w);
                }
            }

            List<ModuleInfo> bootstrapModules = detectBootstrapModules(modules, resolver);

            if (!bootstrapModules.isEmpty() && (forceBootstrap || bootstrapNeeded(bootstrapModules, resolver))) {
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
                    String status = resolver.resolveArtifactPath(
                            m.getGroupId(), m.getArtifactId(), m.getVersion()) == null
                            ? "MISSING" : "STALE";
                    System.out.println("  " + m.getArtifactId() + " (" + status + ")");
                }

                long stepStart = System.currentTimeMillis();
                BuildFileGenerator bootstrapGen = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                bootstrapGen.generate(bootstrapModules, "bootstrap");
                bootstrapGen.compileAndPackage("bootstrap");
                System.out.println("Generated bootstrap.jar (" + bootstrapModules.size() + " modules) in " +
                        (System.currentTimeMillis() - stepStart) + "ms");

                stepStart = System.currentTimeMillis();
                deferredMainGen = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                deferredMainGen.setPreBuiltModules(bootstrapModules);
                deferredMainGen.setProgressListener((detail, current, total) ->
                        System.err.print(ERASE_LINE + "  Generating " + current + "/" + total + " (" + detail + ")"));
                deferredMainGen.generate(mainModules, "build");
                System.err.print(ERASE_LINE);
                System.out.println("Generated " + (mainModules.size() + 1) + " main build sources in " +
                        (System.currentTimeMillis() - stepStart) + "ms");
                System.out.println("  (build.jar will be packaged after bootstrap runs)");
            } else {
                if (!bootstrapModules.isEmpty()) {
                    System.out.println("Bootstrap modules found but jars are fresh — including in main build");
                }

                long stepStart = System.currentTimeMillis();
                BuildFileGenerator generator = new BuildFileGenerator(projectDir, outputDir, threads, resolver);
                generator.setProgressListener((detail, current, total) ->
                        System.err.print(ERASE_LINE + "  Generating " + current + "/" + total + " (" + detail + ")"));
                generator.generate(modules, "build");
                System.err.print(ERASE_LINE);

                System.out.println("Generated " + (modules.size() + 1) + " source files in " +
                        (System.currentTimeMillis() - stepStart) + "ms");

                stepStart = System.currentTimeMillis();
                System.err.print("  Compiling and packaging build.jar...");
                generator.compileAndPackage("build");
                System.err.print(ERASE_LINE);
                System.out.println("Packaged build.jar in " + (System.currentTimeMillis() - stepStart) + "ms");

                Files.deleteIfExists(bootstrapJar);
            }

            System.out.println();
            System.out.println("Total generation time: " + (System.currentTimeMillis() - totalStart) + "ms");
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
                }
                System.out.println();
            }

            if (!Files.exists(buildJar)) {
                System.err.println("Error: build.jar not found at " + buildJar);
                System.err.println("Run without --no-generate to create it.");
                System.exit(1);
            }

            List<String> allBuildArgs = new ArrayList<>(commonBuildArgs);
            allBuildArgs.addAll(mainBuildArgs);

            System.out.println("Running build...");
            System.out.println();
            int exitCode = runBuild(buildJar, projectDir, allBuildArgs);
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
        }

        return allModules.stream()
                .filter(m -> visited.contains(m.getArtifactId()))
                .toList();
    }

    private static boolean bootstrapNeeded(List<ModuleInfo> bootstrapModules, DependencyResolver resolver) {
        for (ModuleInfo module : bootstrapModules) {
            String jarPath = resolver.resolveArtifactPath(
                    module.getGroupId(), module.getArtifactId(), module.getVersion());
            if (jarPath == null) {
                return true;
            }
            try {
                long jarTime = Files.getLastModifiedTime(Path.of(jarPath)).toMillis();
                if (hasNewerSources(module.getBaseDir(), jarTime)) {
                    return true;
                }
            } catch (IOException e) {
                return true;
            }
        }
        return false;
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

            try (var stream = Files.walk(projectDir)) {
                var changed = stream
                        .filter(p -> p.getFileName().toString().equals("pom.xml"))
                        .filter(p -> !p.toString().contains("/target/"))
                        .filter(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis() > buildJarTime;
                            } catch (IOException e) {
                                return true;
                            }
                        })
                        .findFirst();
                if (changed.isPresent()) {
                    Path relative = projectDir.relativize(changed.get());
                    return "pom changed: " + relative;
                }
            }
            return null;
        } catch (IOException e) {
            return "error checking: " + e.getMessage();
        }
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
                  --quickly                 Alias for -DskipTests -DskipITs -Dquarkus.build.skip
                  -pl, --projects <list>    Comma-separated list of module artifactIds to build
                  -am, --also-make          Build dependencies of modules specified by -pl
                  -i, --incremental         Only rebuild modules with changed sources
                  --no-kotlin               Skip Kotlin modules and their dependents
                  --fast-kotlin             Use optimized Kotlin compiler pipeline (shared environment)
                  -D<key>=<value>           Set a system property

                Other options:
                  -h, --help                Show this help message and exit
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

package io.github.fromage.qraven.hardcoded;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public class BuildFileGenerator {

    private static final String RUNTIME_GROUP_ID = "io.github.fromage.qraven";
    private static final String RUNTIME_ARTIFACT_ID = "qraven-runtime";
    private static final String RUNTIME_VERSION = loadVersion();

    private final Path projectRoot;
    private final Path outputDir;
    private final int threads;
    private final DependencyResolver resolver;
    private boolean hasProtobufModules;
    private boolean hasAntlrModules;
    private String protocPath;
    private String grpcJavaPluginPath;
    private String antlrToolClasspath;
    private String jdtFormatterClasspath;
    private String ktfmtClasspath;

    public BuildFileGenerator(Path projectRoot, Path outputDir, int threads, DependencyResolver resolver) {
        this.projectRoot = projectRoot;
        this.outputDir = outputDir;
        this.threads = threads;
        this.resolver = resolver;
    }

    public interface ProgressListener {
        void update(String detail, int current, int total);
    }

    private ProgressListener progressListener;

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    private List<ModuleInfo> preBuiltModules = List.of();
    private Map<String, String> reactorVersions = Map.of();

    public void setPreBuiltModules(List<ModuleInfo> preBuiltModules) {
        this.preBuiltModules = preBuiltModules;
    }

    public void generate(List<ModuleInfo> modules) throws IOException {
        generate(modules, "build");
    }

    private PhaseTimings timings = new PhaseTimings();

    public void setPhaseTimings(PhaseTimings timings) {
        this.timings = timings;
    }

    private boolean incremental;
    private boolean stateWrittenByCaller;
    private boolean reused;
    /** Whether the main class or what it is built from differs from the previous generation's. */
    private boolean mainChanged = true;

    /**
     * Keep the compiled main class and jar of a previous generation when nothing in them changed. Without usable
     * previous output this behaves like a full generation.
     */
    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    /**
     * The generation state covers every module of the project, not just those of this build (the bootstrap build and
     * the main build share it), so when several generators work on one project the caller writes it. Otherwise the
     * generator writes it from the modules it is given.
     */
    public void setStateWrittenByCaller(boolean stateWrittenByCaller) {
        this.stateWrittenByCaller = stateWrittenByCaller;
    }

    /** Whether the last generate() could reuse the previous output. */
    public boolean reusedPreviousOutput() {
        return reused;
    }

    /** The file the build reads its modules from. */
    public Path stateFile() {
        return outputDir.resolve("state.cbor");
    }

    private static boolean hasClassFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Older generations wrote a class per module, which the build no longer uses. */
    private static boolean hasModuleSources(Path srcDir) {
        try (var files = Files.list(srcDir)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith("Build_"));
        } catch (IOException e) {
            return true;
        }
    }

    /** Writes the file unless it already has exactly this content; returns whether it was written. */
    private static boolean writeIfChanged(Path file, String content) throws IOException {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Files.exists(file) && Arrays.equals(Files.readAllBytes(file), bytes)) {
            return false;
        }
        Files.write(file, bytes);
        return true;
    }

    public void generate(List<ModuleInfo> modules, String buildName) throws IOException {
        Path srcDir = outputDir.resolve(buildName + "-src");
        Path classesDir = outputDir.resolve(buildName + "-classes");
        boolean reuse = incremental && Files.isDirectory(srcDir) && Files.isDirectory(classesDir)
                && hasClassFiles(classesDir) && !hasModuleSources(srcDir);
        reused = reuse;
        if (!reuse) {
            deleteDirectory(srcDir);
            deleteDirectory(classesDir);
        }
        Files.createDirectories(srcDir);

        hasProtobufModules = modules.stream().anyMatch(m -> m.isHasProtobufSources() || m.isHasTestProtobufSources());
        if (hasProtobufModules) {
            resolveProtocPaths(modules);
        }
        hasAntlrModules = modules.stream().anyMatch(ModuleInfo::isHasAntlrSources);
        if (hasAntlrModules) {
            resolveAntlrToolClasspath(modules);
        }
        resolveJdtFormatterClasspath(modules);
        resolveKtfmtClasspath(modules);
        timings.lap("generator: tool classpaths (protoc, antlr, jdt, ktfmt)");

        // the build reads each module's data from the state; the flattened poms it points to live next to it
        for (ModuleInfo m : modules) {
            writeFlattenedPom(m);
        }
        for (ModuleInfo m : preBuiltModules) {
            writeFlattenedPom(m);
        }
        if (!stateWrittenByCaller) {
            List<ModuleInfo> all = new ArrayList<>(modules);
            all.addAll(preBuiltModules);
            StateStore.write(stateFile(), "generator", projectRoot, "", all);
        }

        Map<String, String> rv = new HashMap<>();
        for (ModuleInfo m : modules) {
            rv.put(m.getGroupId() + ":" + m.getArtifactId(), m.getVersion());
        }
        for (ModuleInfo m : preBuiltModules) {
            rv.put(m.getGroupId() + ":" + m.getArtifactId(), m.getVersion());
        }
        this.reactorVersions = rv;

        Path mainFile = srcDir.resolve("Build.java");
        mainChanged = writeIfChanged(mainFile, generateMainClass(modules, buildName)) || !reuse;
        if (progressListener != null) {
            progressListener.update("Build.java", 1, 1);
        }
        timings.lap("generator: write sources");
    }

    private String writeFlattenedPom(ModuleInfo module) {
        if (module.getFlattenedBomModel() == null) return null;
        Path file = outputDir.resolve("flattened-poms")
                .resolve(module.getArtifactId() + "-" + module.getVersion() + ".pom");
        try {
            Files.createDirectories(file.getParent());
            java.io.StringWriter xml = new java.io.StringWriter();
            new org.apache.maven.model.io.xpp3.MavenXpp3Writer().write(xml, module.getFlattenedBomModel());
            // the build compares this file's timestamp with the installed artifact's, so rewriting it with the same
            // content would make the module look changed (and everything depending on it)
            writeIfChanged(file, xml.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write flattened pom " + file, e);
        }
        return file.toString();
    }

    private String generateMainClass(List<ModuleInfo> modules, String buildName) {
        StringBuilder sb = new StringBuilder();
        sb.append("import io.github.fromage.qraven.hardcoded.runtime.*;\n");
        sb.append("import java.util.*;\n\n");

        sb.append("public class Build {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        java.nio.file.Path projectRoot = java.nio.file.Path.of(\".\").toAbsolutePath().normalize();\n");
        sb.append("        int threads = Runtime.getRuntime().availableProcessors();\n");
        sb.append("        String projects = null;\n");
        sb.append("        boolean alsoMake = false;\n");
        sb.append("        boolean incremental = false;\n");
        sb.append("        boolean noKotlin = false;\n");
        sb.append("        boolean fastKotlin = true;\n\n");

        sb.append("        for (int i = 0; i < args.length; i++) {\n");
        sb.append("            if (\"--help\".equals(args[i]) || \"-h\".equals(args[i])) {\n");
        sb.append("                System.out.println(\"Usage: java -jar build.jar [options]\");\n");
        sb.append("                System.out.println();\n");
        sb.append("                System.out.println(\"Options:\");\n");
        sb.append("                System.out.println(\"  -h, --help                Show this help message and exit\");\n");
        sb.append("                System.out.println(\"  -p, --project <path>      Project root directory (default: current directory)\");\n");
        sb.append("                System.out.println(\"  -t, --threads <n>         Thread count for parallel compilation (default: available CPUs)\");\n");
        sb.append("                System.out.println(\"  -pl, --projects <list>    Comma-separated list of module artifactIds to build\");\n");
        sb.append("                System.out.println(\"  -am, --also-make          Build dependencies of modules specified by -pl\");\n");
        sb.append("                System.out.println(\"  -i, --incremental         Only rebuild modules with changed sources\");\n");
        sb.append("                System.out.println(\"      --no-kotlin           Skip Kotlin modules and their dependents\");\n");
        sb.append("                System.out.println(\"      --no-fast-kotlin      Disable JIT warmup for Kotlin compiler (enabled by default)\");\n");
        sb.append("                System.out.println(\"  -D<key>=<value>           Set a system property\");\n");
        sb.append("                return;\n");
        sb.append("            } else if (args[i].startsWith(\"-D\")) {\n");
        sb.append("                String prop = args[i].substring(2);\n");
        sb.append("                int eq = prop.indexOf('=');\n");
        sb.append("                if (eq >= 0) {\n");
        sb.append("                    System.setProperty(prop.substring(0, eq), prop.substring(eq + 1));\n");
        sb.append("                } else {\n");
        sb.append("                    System.setProperty(prop, \"true\");\n");
        sb.append("                }\n");
        sb.append("            } else if ((\"--threads\".equals(args[i]) || \"-t\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                threads = Integer.parseInt(args[++i]);\n");
        sb.append("            } else if ((\"--project\".equals(args[i]) || \"-p\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                projectRoot = java.nio.file.Path.of(args[++i]).toAbsolutePath().normalize();\n");
        sb.append("            } else if ((\"--projects\".equals(args[i]) || \"-pl\".equals(args[i])) && i + 1 < args.length) {\n");
        sb.append("                projects = args[++i];\n");
        sb.append("            } else if (\"--also-make\".equals(args[i]) || \"-am\".equals(args[i])) {\n");
        sb.append("                alsoMake = true;\n");
        sb.append("            } else if (\"--incremental\".equals(args[i]) || \"-i\".equals(args[i])) {\n");
        sb.append("                incremental = true;\n");
        sb.append("            } else if (\"--no-kotlin\".equals(args[i])) {\n");
        sb.append("                noKotlin = true;\n");
        sb.append("            } else if (\"--no-fast-kotlin\".equals(args[i])) {\n");
        sb.append("                fastKotlin = false;\n");
        sb.append("            } else {\n");
        sb.append("                System.err.println(\"Unknown option: \" + args[i]);\n");
        sb.append("                System.err.println(\"Run with --help for usage information.\");\n");
        sb.append("                System.exit(1);\n");
        sb.append("            }\n");
        sb.append("        }\n\n");

        sb.append("        BuildRuntime runtime = new BuildRuntime(projectRoot);\n");
        sb.append("        runtime.setFastKotlin(fastKotlin);\n");
        if (protocPath != null) {
            sb.append("        runtime.setProtocPath(").append(quote(protocPath)).append(");\n");
        }
        if (grpcJavaPluginPath != null) {
            sb.append("        runtime.setGrpcJavaPluginPath(").append(quote(grpcJavaPluginPath)).append(");\n");
        }
        if (antlrToolClasspath != null) {
            sb.append("        runtime.setAntlrToolClasspath(").append(quote(antlrToolClasspath)).append(");\n");
        }
        if (jdtFormatterClasspath != null) {
            sb.append("        runtime.setJdtFormatterClasspath(").append(quote(jdtFormatterClasspath)).append(");\n");
        }
        if (ktfmtClasspath != null) {
            sb.append("        runtime.setKtfmtClasspath(").append(quote(ktfmtClasspath)).append(");\n");
        }
        sb.append("        ModuleStates states = ModuleStates.open(java.nio.file.Path.of(")
                .append(quote(stateFile().toString())).append("));\n");
        sb.append("        List<ModuleBuild> modules = new ArrayList<>();\n");
        sb.append("        for (String id : ids(").append(idChunks(modules)).append(")) {\n");
        sb.append("            modules.add(states.create(id, runtime));\n");
        sb.append("        }\n");

        if (!preBuiltModules.isEmpty()) {
            sb.append("\n        // Bootstrap modules (already built in bootstrap phase)\n");
            sb.append("        for (String id : ids(").append(idChunks(preBuiltModules)).append(")) {\n");
            sb.append("            ModuleBuild m = states.create(id, runtime);\n");
            sb.append("            m.markPreBuilt();\n");
            sb.append("            modules.add(m);\n");
            sb.append("        }\n");
        }

        sb.append("\n        new BuildOrchestrator(threads).setLogName(").append(quote(buildName)).append(").buildAll(modules, projects, alsoMake, incremental, noKotlin);\n");
        sb.append("    }\n\n");
        sb.append("    /** The ids are given in chunks, since a constant can't hold more than 64 KB. */\n");
        sb.append("    private static List<String> ids(String... chunks) {\n");
        sb.append("        List<String> ids = new ArrayList<>();\n");
        sb.append("        for (String chunk : chunks) {\n");
        sb.append("            for (String id : chunk.split(\"\\n\")) {\n");
        sb.append("                if (!id.isEmpty()) ids.add(id);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return ids;\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** The ids of the modules as string literals, each holding as many ids as fit comfortably in a constant. */
    private String idChunks(List<ModuleInfo> modules) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (ModuleInfo m : modules) {
            String id = m.getGroupId() + ":" + m.getArtifactId();
            if (chunk.length() + id.length() > 20_000) {
                chunks.add(quote(chunk.toString()));
                chunk.setLength(0);
            }
            chunk.append(id).append('\n');
        }
        chunks.add(quote(chunk.toString()));
        return String.join(",\n                ", chunks);
    }

    public void compileAndPackage() throws IOException {
        compileAndPackage("build");
    }

    public void compileAndPackage(String buildName) throws IOException {
        Path srcDir = outputDir.resolve(buildName + "-src");
        Path classesDir = outputDir.resolve(buildName + "-classes");
        Path buildJar = outputDir.resolve(buildName + ".jar");
        Files.createDirectories(classesDir);

        List<String> runtimeClasspath = resolver.resolveAnnotationProcessorPath(
                RUNTIME_GROUP_ID, RUNTIME_ARTIFACT_ID, RUNTIME_VERSION, null);
        if (runtimeClasspath.isEmpty()) {
            throw new RuntimeException("Could not resolve " + RUNTIME_GROUP_ID + ":" +
                    RUNTIME_ARTIFACT_ID + ":" + RUNTIME_VERSION + " from ~/.m2. " +
                    "Build the runtime module first: mvn install -f runtime/");
        }

        boolean unchanged = reused && !mainChanged && Files.exists(buildJar);
        if (unchanged) {
            // nothing changed: the jar is current, just record that it was checked
            Files.setLastModifiedTime(buildJar, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
            return;
        }
        deleteClassFiles(classesDir, "Build");
        List<Path> sourceFiles;
        try (var stream = Files.walk(srcDir)) {
            sourceFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("No Java compiler available");
        }

        String classpath = String.join(File.pathSeparator, runtimeClasspath);

        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = List.of(
                    "-d", classesDir.toString(),
                    "-classpath", classpath,
                    "--release", "17"
            );

            var task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            if (!task.call()) {
                StringBuilder sb = new StringBuilder("Compilation of generated sources failed:\n");
                diagnostics.getDiagnostics().forEach(d -> sb.append("  ").append(d).append("\n"));
                throw new RuntimeException(sb.toString());
            }
        }

        runtimeClasspath = substituteReactorVersions(runtimeClasspath);
        createBuildJar(classesDir, runtimeClasspath, buildJar);
    }

    private static void deleteClassFiles(Path classesDir, String className) throws IOException {
        Files.deleteIfExists(classesDir.resolve(className + ".class"));
        try (var files = Files.list(classesDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (f.getFileName().toString().startsWith(className + "$")) {
                    Files.delete(f);
                }
            }
        }
    }

    private List<String> substituteReactorVersions(List<String> classpath) {
        if (reactorVersions.isEmpty()) return classpath;
        String m2 = System.getProperty("user.home") + "/.m2/repository/";
        List<String> result = new ArrayList<>(classpath.size());
        for (String entry : classpath) {
            if (!entry.startsWith(m2)) {
                result.add(entry);
                continue;
            }
            String relative = entry.substring(m2.length());
            String[] parts = relative.split("/");
            if (parts.length < 4) {
                result.add(entry);
                continue;
            }
            String version = parts[parts.length - 2];
            String artifactId = parts[parts.length - 3];
            String groupId = String.join(".", Arrays.copyOf(parts, parts.length - 3));
            String ga = groupId + ":" + artifactId;

            String reactorVersion = reactorVersions.get(ga);
            if (reactorVersion != null && !reactorVersion.equals(version)) {
                String fileName = parts[parts.length - 1];
                String newFileName = fileName.replace(artifactId + "-" + version,
                        artifactId + "-" + reactorVersion);
                String newPath = m2 + groupId.replace('.', '/') + "/" + artifactId
                        + "/" + reactorVersion + "/" + newFileName;
                if (new File(newPath).exists()) {
                    result.add(newPath);
                    continue;
                }
            }
            result.add(entry);
        }
        return result;
    }

    private void resolveProtocPaths(List<ModuleInfo> modules) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "");
        String os;
        if (osName.contains("linux")) os = "linux";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "osx";
        else if (osName.contains("win")) os = "windows";
        else os = osName;
        String arch;
        if ("amd64".equals(osArch) || "x86_64".equals(osArch)) arch = "x86_64";
        else if ("aarch64".equals(osArch)) arch = "aarch_64";
        else arch = osArch;
        String classifier = os + "-" + arch;

        String protocVersion = modules.stream()
                .map(ModuleInfo::getProtocVersion).filter(v -> v != null)
                .findFirst().orElse(null);
        String grpcVersion = modules.stream()
                .map(ModuleInfo::getGrpcVersion).filter(v -> v != null)
                .findFirst().orElse(null);

        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");

        Path protocBase = m2.resolve("com/google/protobuf/protoc");
        if (Files.isDirectory(protocBase)) {
            try (var versions = Files.list(protocBase)) {
                protocPath = versions.filter(Files::isDirectory)
                        .map(v -> {
                            String ver = v.getFileName().toString();
                            Path exe = v.resolve("protoc-" + ver + "-" + classifier + ".exe");
                            return Files.exists(exe) ? exe.toString() : null;
                        })
                        .filter(p -> p != null)
                        .findFirst().orElse(null);
            } catch (IOException e) {
                // ignore
            }
        }
        if (protocPath == null && protocVersion != null) {
            Path resolved = resolver.resolveClassifiedArtifact(
                    "com.google.protobuf", "protoc", protocVersion, classifier, "exe");
            if (resolved != null) {
                protocPath = resolved.toString();
                Status.line("Resolved protoc " + protocVersion + " via download");
            }
        }

        boolean needsGrpc = modules.stream().anyMatch(ModuleInfo::isProtobufUsesGrpc);
        if (needsGrpc) {
            Path grpcBase = m2.resolve("io/grpc/protoc-gen-grpc-java");
            if (Files.isDirectory(grpcBase)) {
                try (var versions = Files.list(grpcBase)) {
                    grpcJavaPluginPath = versions.filter(Files::isDirectory)
                            .map(v -> {
                                String ver = v.getFileName().toString();
                                Path exe = v.resolve("protoc-gen-grpc-java-" + ver + "-" + classifier + ".exe");
                                return Files.exists(exe) ? exe.toString() : null;
                            })
                            .filter(p -> p != null)
                            .findFirst().orElse(null);
                } catch (IOException e) {
                    // ignore
                }
            }
            if (grpcJavaPluginPath == null && grpcVersion != null) {
                Path resolved = resolver.resolveClassifiedArtifact(
                        "io.grpc", "protoc-gen-grpc-java", grpcVersion, classifier, "exe");
                if (resolved != null) {
                    grpcJavaPluginPath = resolved.toString();
                    Status.line("Resolved protoc-gen-grpc-java " + grpcVersion + " via download");
                }
            }
        }

        if (protocPath != null) {
            Status.line("Resolved protoc: " + protocPath);
        } else {
            Status.warning("WARNING: protoc binary not found in ~/.m2/repository");
        }
        if (needsGrpc && grpcJavaPluginPath != null) {
            Status.line("Resolved protoc-gen-grpc-java: " + grpcJavaPluginPath);
        }
    }

    private void resolveAntlrToolClasspath(List<ModuleInfo> modules) {
        String antlrVersion = null;
        for (ModuleInfo module : modules) {
            if (!module.isHasAntlrSources()) continue;
            for (String cp : module.getCompileClasspath()) {
                if (cp.contains("/antlr4-runtime/")) {
                    String resolved = cp.startsWith("$HOME/")
                            ? System.getProperty("user.home") + cp.substring(5) : cp;
                    Path jar = Path.of(resolved);
                    String name = jar.getFileName().toString();
                    if (name.startsWith("antlr4-runtime-") && name.endsWith(".jar")) {
                        antlrVersion = name.substring("antlr4-runtime-".length(),
                                name.length() - ".jar".length());
                        break;
                    }
                }
            }
            if (antlrVersion != null) break;
        }
        if (antlrVersion == null) {
            Status.warning("WARNING: could not determine ANTLR4 version from classpath");
            return;
        }

        List<String> jars = resolver.resolveAnnotationProcessorPath(
                "org.antlr", "antlr4", antlrVersion, null);
        if (!jars.isEmpty()) {
            antlrToolClasspath = String.join(java.io.File.pathSeparator, jars);
            Status.line("Resolved ANTLR4 tool classpath (" + antlrVersion + ")");
        } else {
            Status.warning("WARNING: could not resolve ANTLR4 tool artifact org.antlr:antlr4:" + antlrVersion);
        }
    }

    private void resolveJdtFormatterClasspath(List<ModuleInfo> modules) {
        Path formatConfigFile = projectRoot.resolve(
                "independent-projects/ide-config/src/main/resources/eclipse-format.xml");
        if (!Files.exists(formatConfigFile)) return;

        String version = null;
        for (ModuleInfo m : modules) {
            if (m.getFormatterPluginVersion() != null) {
                version = m.getFormatterPluginVersion();
                break;
            }
        }
        if (version == null) return;

        List<String> jars = resolver.resolveAnnotationProcessorPath(
                "net.revelc.code.formatter", "formatter-maven-plugin", version, null);
        List<String> jdtJars = jars.stream()
                .filter(p -> {
                    String name = java.nio.file.Path.of(p).getFileName().toString();
                    return name.startsWith("org.eclipse.jdt.core-")
                            || name.startsWith("ecj-")
                            || name.startsWith("org.eclipse.text-")
                            || name.startsWith("org.eclipse.equinox.common-");
                })
                .toList();
        if (jdtJars.size() >= 4) {
            jdtFormatterClasspath = String.join(java.io.File.pathSeparator, jdtJars);
            Status.line("Resolved Eclipse JDT formatter classpath (" + version + ")");
        } else if (!jdtJars.isEmpty()) {
            jdtFormatterClasspath = String.join(java.io.File.pathSeparator, jdtJars);
            Status.line("Resolved Eclipse JDT formatter classpath (" + version + ", " + jdtJars.size() + " jars)");
        }
    }

    private void resolveKtfmtClasspath(List<ModuleInfo> modules) {
        boolean hasKotlin = modules.stream().anyMatch(ModuleInfo::isHasKotlinSources);
        if (!hasKotlin) return;

        String spotlessVersion = null;
        for (ModuleInfo m : modules) {
            if (m.getSpotlessPluginVersion() != null) {
                spotlessVersion = m.getSpotlessPluginVersion();
                break;
            }
        }
        if (spotlessVersion == null) return;

        try {
            List<String> pluginJars = resolver.resolveAnnotationProcessorPath(
                    "com.diffplug.spotless", "spotless-maven-plugin", spotlessVersion, null);
            String spotlessLibJar = pluginJars.stream()
                    .filter(p -> {
                        String name = Path.of(p).getFileName().toString();
                        return name.startsWith("spotless-lib-") && !name.contains("-extra");
                    })
                    .findFirst().orElse(null);
            if (spotlessLibJar == null) return;

            String ktfmtVersion = null;
            try (var cl = new java.net.URLClassLoader(
                    new java.net.URL[]{Path.of(spotlessLibJar).toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> ktfmtStep = cl.loadClass("com.diffplug.spotless.kotlin.KtfmtStep");
                java.lang.reflect.Method defaultVersion = ktfmtStep.getMethod("defaultVersion");
                ktfmtVersion = (String) defaultVersion.invoke(null);
            }
            if (ktfmtVersion == null) return;

            List<String> jars = resolver.resolveAnnotationProcessorPath(
                    "com.facebook", "ktfmt", ktfmtVersion, null);
            if (!jars.isEmpty()) {
                ktfmtClasspath = String.join(java.io.File.pathSeparator, jars);
                Status.line("Resolved ktfmt classpath (" + ktfmtVersion + ")");
            }
        } catch (Exception e) {
            Status.warning("WARNING: could not resolve ktfmt classpath: " + e.getMessage());
        }
    }

    private void createBuildJar(Path classesDir, List<String> classpath, Path buildJar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Build");

        String classPathValue = classpath.stream()
                .map(p -> Path.of(p).toUri().toString())
                .collect(Collectors.joining(" "));
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPathValue);

        try (OutputStream fos = Files.newOutputStream(buildJar);
             JarOutputStream jos = new JarOutputStream(fos, manifest)) {
            addDirectoryToJar(classesDir, classesDir, jos);
        }
    }

    private void addDirectoryToJar(Path baseDir, Path root, JarOutputStream jos) throws IOException {
        Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String entryName = root.relativize(file).toString().replace('\\', '/');
                if (entryName.equals("META-INF/MANIFEST.MF")) {
                    return FileVisitResult.CONTINUE;
                }
                jos.putNextEntry(new JarEntry(entryName));
                Files.copy(file, jos);
                jos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String loadVersion() {
        try (var is = BuildFileGenerator.class.getResourceAsStream("/qraven-build.properties")) {
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
}

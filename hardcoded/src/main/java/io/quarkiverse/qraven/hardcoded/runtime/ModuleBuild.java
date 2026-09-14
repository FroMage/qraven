package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class ModuleBuild {

    private static final String HOME = System.getProperty("user.home");

    protected final BuildRuntime runtime;
    private volatile CompletableFuture<Void> buildFuture;
    private List<ModuleBuild> dependencies = List.of();
    private ProgressDisplay progress;
    private BuildStats stats;
    private ConcurrentHashMap<Long, Integer> threadIndices;
    private AtomicInteger threadIndexCounter;
    private int maxThreadIndex;
    private boolean incremental;
    private List<String> resolvedClasspath;
    private Set<String> resolvedOptionalClasspath;
    private List<String> resolvedAnnotationProcessorPaths;

    protected ModuleBuild(BuildRuntime runtime) {
        this.runtime = runtime;
    }

    static String resolvePath(String path) {
        if (path.startsWith("$HOME/")) {
            return HOME + path.substring(5);
        }
        return path;
    }

    static List<String> resolvePaths(List<String> paths) {
        return paths.stream().map(ModuleBuild::resolvePath).toList();
    }

    public List<String> resolvedClasspath() {
        if (resolvedClasspath == null) {
            List<String> cp = new ArrayList<>(resolvePaths(compileClasspath()));
            cp.addAll(resolvePaths(optionalCompileClasspath()));
            resolvedClasspath = cp;
        }
        return resolvedClasspath;
    }

    public Set<String> resolvedOptionalClasspathEntries() {
        if (resolvedOptionalClasspath == null) {
            resolvedOptionalClasspath = new HashSet<>(resolvePaths(optionalCompileClasspath()));
        }
        return resolvedOptionalClasspath;
    }

    public List<String> resolvedAnnotationProcessorPaths() {
        if (resolvedAnnotationProcessorPaths == null) {
            resolvedAnnotationProcessorPaths = resolvePaths(annotationProcessorPaths());
        }
        return resolvedAnnotationProcessorPaths;
    }

    public abstract String groupId();
    public abstract String artifactId();
    public abstract String version();
    public abstract String packaging();
    public abstract Path baseDir();
    public abstract boolean hasJavaSources();
    public abstract boolean hasKotlinSources();
    public abstract boolean hasProtobufSources();
    public abstract boolean protobufUsesGrpc();
    public abstract boolean protobufUsesMutiny();
    public boolean hasAntlrSources() { return false; }
    public boolean antlrVisitor() { return false; }
    public abstract String[][] resourceDirs();
    public abstract Map<String, String> filterProperties();
    public abstract boolean needsJandexIndex();
    public abstract Map<String, String> manifestEntries();
    public abstract List<String> compileClasspath();
    public abstract List<String> optionalCompileClasspath();
    public abstract List<String> annotationProcessorPaths();
    public abstract boolean isApCacheable();
    public abstract List<String> compilerArgs();
    public abstract List<String> moduleDependencyIds();
    public abstract List<String> optionalModuleDependencyIds();
    public abstract boolean hasExtensionPlugin();
    public abstract String extensionValidationSkipWhen();
    public abstract Map<String, String> extensionDescriptorProperties();
    public abstract String extensionProjectName();
    public abstract String extensionProjectDescription();
    public abstract String extensionScmUrl();
    public abstract String extensionMinimumJavaVersion();
    public abstract List<String> extensionModelDeps();
    public abstract List<String> extensionReactorGAs();
    public abstract List<String> extensionParentFirstArtifacts();
    public abstract List<String> extensionRunnerParentFirstArtifacts();
    public abstract List<String> extensionExcludedArtifacts();
    public abstract List<String> extensionLesserPriorityArtifacts();
    public abstract List<String> extensionProvidesCapabilities();
    public abstract List<String> extensionRequiresCapabilities();
    public abstract boolean hasQuarkusBuildPlugin();
    public abstract String quarkusBuildSkipWhen();
    public abstract boolean hasGenerateCodeGoal();
    public abstract boolean hasCodeGenProviders();
    public abstract boolean hasSisuPlugin();
    public abstract String generateCodeSkipWhen();
    public abstract Map<String, String> quarkusBuildProperties();
    public abstract List<String> deploymentClasspath();
    public abstract List<String> runtimeExtensionArtifacts();
    public abstract Map<String, String> extensionDevProperties();
    public abstract String protocVersion();
    public abstract String grpcVersion();
    public abstract String quarkusGrpcVersion();
    public abstract Map<String, String> allReactorExtensionDeployments();

    public void setDependencies(List<ModuleBuild> dependencies) {
        this.dependencies = dependencies;
    }

    public List<ModuleBuild> getDependencies() {
        return dependencies;
    }

    public void setProgress(ProgressDisplay progress, BuildStats stats,
                           ConcurrentHashMap<Long, Integer> threadIndices,
                           AtomicInteger threadIndexCounter, int maxThreadIndex,
                           boolean incremental) {
        this.progress = progress;
        this.stats = stats;
        this.threadIndices = threadIndices;
        this.threadIndexCounter = threadIndexCounter;
        this.maxThreadIndex = maxThreadIndex;
        this.incremental = incremental;
    }

    private volatile long buildScheduledAt;
    private volatile boolean buildSucceeded = false;
    private volatile boolean skippedIncremental = false;
    private volatile String failureMessage;

    public boolean didSucceed() {
        return buildSucceeded;
    }

    public boolean wasSkippedIncremental() {
        return skippedIncremental;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void markPreBuilt() {
        buildSucceeded = true;
        skippedIncremental = true;
        buildFuture = CompletableFuture.completedFuture(null);
    }

    public synchronized CompletableFuture<Void> buildAsync(ExecutorService executor) {
        if (buildFuture != null) {
            return buildFuture;
        }
        CompletableFuture<?>[] depFutures = dependencies.stream()
                .map(dep -> dep.buildAsync(executor))
                .toArray(CompletableFuture[]::new);
        buildScheduledAt = System.currentTimeMillis();
        buildFuture = CompletableFuture.allOf(depFutures)
                .handleAsync((v, ex) -> { doBuild(); return null; }, executor);
        return buildFuture;
    }

    private int getThreadIndex() {
        if (threadIndices == null) return 0;
        return threadIndices.computeIfAbsent(Thread.currentThread().getId(),
                k -> threadIndexCounter.getAndIncrement() % maxThreadIndex);
    }

    private int countSources() {
        Path src = sourceDir();
        if (!Files.isDirectory(src)) return 0;
        try (var stream = Files.walk(src)) {
            return (int) stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    protected void doBuild() {
        long start = System.currentTimeMillis();
        System.err.println("[timing] [" + artifactId() + "] doBuild started at T+" + (start - buildScheduledAt) + "ms after scheduling");

        List<String> failedDeps = new ArrayList<>();
        for (ModuleBuild dep : dependencies) {
            if (!dep.didSucceed()) {
                failedDeps.add(dep.artifactId());
            }
        }

        int threadIdx = getThreadIndex();

        if (!failedDeps.isEmpty() && progress != null) {
            progress.moduleStarted(threadIdx, artifactId(), "skipped", 0);
            progress.moduleCompleted(threadIdx, false);
            return;
        }

        try {
            if ("pom".equals(packaging())) {
                if (incremental && isUpToDate()) {
                    skippedIncremental = true;
                    buildSucceeded = true;
                    if (progress != null) {
                        progress.moduleStarted(threadIdx, artifactId(), "up-to-date", 0);
                        progress.moduleCompleted(threadIdx, true);
                    }
                    return;
                }
                if (progress != null) progress.moduleStarted(threadIdx, artifactId(), "pom", 0);
                long t = System.currentTimeMillis();
                runtime.install(null, pomFile(), groupId(), artifactId(), version(), packaging());
                recordPhase("install", t);
            } else {
                if (incremental && isUpToDate()) {
                    skippedIncremental = true;
                    buildSucceeded = true;
                    if (progress != null) {
                        progress.moduleStarted(threadIdx, artifactId(), "up-to-date", 0);
                        progress.moduleCompleted(threadIdx, true);
                    }
                    return;
                }

                long prePhaseStart = System.currentTimeMillis();
                if (!incremental) {
                    runtime.clean(targetDir());
                }
                long cleanElapsed = System.currentTimeMillis() - prePhaseStart;

                List<String> fullClasspath = new ArrayList<>(resolvedClasspath());
                Set<String> added = new HashSet<>();
                addReactorJars(this, fullClasspath, added);
                long cpElapsed = System.currentTimeMillis() - prePhaseStart - cleanElapsed;

                boolean skipFormat = evaluateSkip("${no-format}");

                long prePhaseTotal = System.currentTimeMillis() - prePhaseStart;
                if (prePhaseTotal > 50) {
                    System.err.println("[timing] [" + artifactId() + "] pre-phase: " + prePhaseTotal
                            + "ms (clean=" + cleanElapsed + "ms, classpath=" + cpElapsed + "ms)");
                }

                if (progress != null) progress.moduleStarted(threadIdx, artifactId(), "resources", 0);
                long t = System.currentTimeMillis();

                if (!skipFormat) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "enforcer", 0);
                    t = System.currentTimeMillis();
                    List<String> violations = runtime.getBannedDependencyChecker().check(fullClasspath);
                    if (!violations.isEmpty()) {
                        for (String v : violations) {
                            System.err.println("WARN: [" + artifactId() + "] banned dependency: " + v);
                        }
                    }
                    recordPhase("enforcer", t);
                }

                t = System.currentTimeMillis();
                for (String[] rd : resourceDirs()) {
                    Path dir = runtime.getProjectRoot().resolve(baseDir()).resolve(rd[0]);
                    boolean filtering = "true".equals(rd[1]);
                    Path outputDir = classesDir();
                    if (rd.length > 2 && rd[2] != null) {
                        outputDir = classesDir().resolve(rd[2]);
                    }
                    if (filtering) {
                        runtime.copyResourcesFiltered(dir, outputDir, filterProperties());
                    } else {
                        runtime.copyResources(dir, outputDir);
                    }
                }
                recordPhase("resources", t);

                if (hasExtensionPlugin()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "ext-descriptor", 0);
                    t = System.currentTimeMillis();
                    ExtensionDescriptorHelper.generate(
                            classesDir(), extensionDescriptorProperties(),
                            fullClasspath, extensionReactorGAs(),
                            extensionProjectName(), extensionProjectDescription(),
                            extensionScmUrl(), extensionMinimumJavaVersion(),
                            extensionModelDeps(),
                            extensionParentFirstArtifacts(),
                            extensionRunnerParentFirstArtifacts(),
                            extensionExcludedArtifacts(),
                            extensionLesserPriorityArtifacts(),
                            extensionProvidesCapabilities(),
                            extensionRequiresCapabilities(),
                            evaluateSkip(extensionValidationSkipWhen()),
                            resolvePaths(deploymentClasspath()),
                            new HashSet<>(runtimeExtensionArtifacts()),
                            extensionDevProperties(),
                            allReactorExtensionDeployments());
                    long edElapsed = System.currentTimeMillis() - t;
                    recordPhase("ext-descriptor", t);
                    if (edElapsed > 100) {
                        System.err.println("[timing] [" + artifactId() + "] ext-descriptor: " + edElapsed + "ms");
                    }
                }

                Path generatedSourcesDir = null;
                if (hasGenerateCodeGoal() && hasCodeGenProviders()
                        && !evaluateSkip(generateCodeSkipWhen())
                        && hasCodeGenSourceFiles()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "generate-code", 0);
                    t = System.currentTimeMillis();
                    Files.createDirectories(classesDir());
                    try {
                        generatedSourcesDir = QuarkusBuildHelper.generateCode(this, dependencies);
                    } catch (Exception e) {
                        Throwable cause = e;
                        while (cause.getCause() != null && (cause.getMessage() == null
                                || cause instanceof java.lang.reflect.InvocationTargetException)) {
                            cause = cause.getCause();
                        }
                        System.err.println("[" + artifactId() + "] generate-code failed: " + cause.getMessage());
                        cause.printStackTrace(System.err);
                    }
                    long gcElapsed = System.currentTimeMillis() - t;
                    recordPhase("generate-code", t);
                    System.err.println("[timing] [" + artifactId() + "] generate-code: " + gcElapsed + "ms");
                }

                Path generatedProtoDir = null;
                if (hasProtobufSources() && !evaluateSkip(generateCodeSkipWhen())) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "protobuf", 0);
                    t = System.currentTimeMillis();
                    generatedProtoDir = generatedProtobufDir();
                    runtime.compileProtobuf(protoSourceDir(), generatedProtoDir,
                            protobufUsesGrpc(), protobufUsesMutiny(), fullClasspath);
                    recordPhase("protobuf", t);
                }

                Path generatedAntlrDir = null;
                if (hasAntlrSources()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "antlr", 0);
                    t = System.currentTimeMillis();
                    generatedAntlrDir = generatedAntlrDir();
                    runtime.compileAntlr(antlrSourceDir(), generatedAntlrDir, antlrVisitor());
                    recordPhase("antlr", t);
                }

                if (hasKotlinSources() && !skipFormat) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "ktfmt", 0);
                    t = System.currentTimeMillis();
                    runtime.getCodeStyleHelper().formatKotlinFiles(kotlinSourceDir());
                    recordPhase("ktfmt", t);
                }

                if (hasKotlinSources()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "kotlin", 0);
                    t = System.currentTimeMillis();
                    List<Path> kotlinExtraRoots = new ArrayList<>();
                    if (generatedSourcesDir != null && Files.isDirectory(generatedSourcesDir)) {
                        addGeneratedSourceDirs(generatedSourcesDir, kotlinExtraRoots);
                    }
                    if (generatedProtoDir != null) {
                        Path javaDir = generatedProtoDir.resolve("java");
                        if (Files.isDirectory(javaDir)) kotlinExtraRoots.add(javaDir);
                        Path grpcDir = generatedProtoDir.resolve("grpc-java");
                        if (Files.isDirectory(grpcDir)) kotlinExtraRoots.add(grpcDir);
                        Path quarkusDir = generatedProtoDir.resolve("quarkus-grpc");
                        if (Files.isDirectory(quarkusDir)) kotlinExtraRoots.add(quarkusDir);
                    }
                    runtime.compileKotlin(kotlinSourceDir(), sourceDir(), classesDir(), fullClasspath,
                            kotlinExtraRoots.toArray(new Path[0]));
                    fullClasspath.add(0, classesDir().toString());
                    recordPhase("kotlin", t);
                }

                if (hasJavaSources() && !skipFormat) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "format", 0);
                    t = System.currentTimeMillis();
                    CodeStyleHelper codeStyle = runtime.getCodeStyleHelper();
                    codeStyle.formatJavaFiles(sourceDir());
                    codeStyle.sortImports(sourceDir());
                    recordPhase("format", t);
                }

                boolean hasKotlinJava = hasKotlinSources() && hasJavaInKotlinDir();
                boolean hasGenSources = generatedSourcesDir != null && Files.isDirectory(generatedSourcesDir);
                if (hasJavaSources() || hasKotlinJava || generatedProtoDir != null
                        || generatedAntlrDir != null || hasGenSources) {
                    List<String> apPaths = resolvedAnnotationProcessorPaths();
                    String compilePhase = apPaths.isEmpty() ? "compile" : "compile+apt";
                    int sourceCount = countSources();
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), compilePhase, sourceCount);
                    t = System.currentTimeMillis();
                    List<Path> extraDirs = new ArrayList<>();
                    if (generatedProtoDir != null) {
                        extraDirs.add(generatedProtoDir.resolve("java"));
                        extraDirs.add(generatedProtoDir.resolve("grpc-java"));
                        extraDirs.add(generatedProtoDir.resolve("quarkus-grpc"));
                    }
                    if (generatedAntlrDir != null && Files.isDirectory(generatedAntlrDir)) {
                        extraDirs.add(generatedAntlrDir);
                    }
                    if (hasGenSources) {
                        addGeneratedSourceDirs(generatedSourcesDir, extraDirs);
                    }
                    if (hasKotlinJava) {
                        extraDirs.add(kotlinSourceDir());
                    }
                    runtime.compile(sourceDir(), classesDir(), fullClasspath,
                            apPaths, isApCacheable(), compilerArgs(),
                            extraDirs.toArray(new Path[0]));
                    recordPhase(compilePhase, t);
                }

                if (needsJandexIndex()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jandex", 0);
                    t = System.currentTimeMillis();
                    runtime.generateJandexIndex(classesDir());
                    recordPhase("jandex", t);
                }

                if (hasSisuPlugin()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "sisu-index", 0);
                    t = System.currentTimeMillis();
                    generateSisuIndex(sourceDir(), classesDir());
                    recordPhase("sisu-index", t);
                }

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jar", 0);
                t = System.currentTimeMillis();
                Files.createDirectories(classesDir());
                runtime.createJar(classesDir(), jarFile(), manifestEntries());
                recordPhase("jar", t);

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "install", 0);
                t = System.currentTimeMillis();
                runtime.install(jarFile(), pomFile(), groupId(), artifactId(), version(), packaging());
                recordPhase("install", t);

                if (hasQuarkusBuildPlugin() && !evaluateSkip(quarkusBuildSkipWhen())) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "quarkus-build", 0);
                    t = System.currentTimeMillis();
                    QuarkusBuildHelper.run(this, dependencies);
                    recordPhase("quarkus-build", t);
                }
            }

            buildSucceeded = true;
            long totalElapsed = System.currentTimeMillis() - start;
            if (totalElapsed > 500) {
                System.err.println("[timing] [" + artifactId() + "] doBuild total: " + totalElapsed + "ms");
            }
            if (progress != null) {
                progress.moduleCompleted(threadIdx, true);
            }
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - start;
            StringBuilder msg = new StringBuilder();
            msg.append("[").append(artifactId()).append("] FAILED after ").append(elapsed).append("ms: ").append(e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                msg.append("\n  Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
                cause = cause.getCause();
            }
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            if (root instanceof NoClassDefFoundError || root instanceof ClassNotFoundException) {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                msg.append("\n  Full stack trace:\n").append(sw);
            }
            failureMessage = msg.toString();
            if (progress != null) {
                progress.moduleCompleted(threadIdx, false);
            }
            throw new RuntimeException("Build failed for " + artifactId(), e);
        }
    }

    private void recordPhase(String phase, long startTime) {
        if (stats != null) {
            stats.record(phase, System.currentTimeMillis() - startTime);
        }
    }

    private void addReactorJars(ModuleBuild module, List<String> classpath, Set<String> visited) {
        for (ModuleBuild dep : module.getDependencies()) {
            if (!visited.add(dep.artifactId())) {
                continue;
            }
            if (dep.didSucceed() && !"pom".equals(dep.packaging())) {
                classpath.add(dep.jarFile().toString());
            }
            if (dep.didSucceed()) {
                for (String cp : dep.resolvedClasspath()) {
                    if (!classpath.contains(cp)) {
                        classpath.add(cp);
                    }
                }
            }
            addReactorJars(dep, classpath, visited);
        }
    }

    public Path targetDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("target");
    }

    public Path classesDir() {
        return targetDir().resolve("classes");
    }

    public Path jarFile() {
        return targetDir().resolve(artifactId() + "-" + version() + ".jar");
    }

    public Path pomFile() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("pom.xml");
    }

    public Path sourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/java");
    }

    public Path kotlinSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/kotlin");
    }

    private void addGeneratedSourceDirs(Path generatedSourcesDir, List<Path> extraDirs) {
        try (var subdirs = Files.list(generatedSourcesDir)) {
            subdirs.filter(Files::isDirectory).forEach(extraDirs::add);
        } catch (IOException e) {
            // ignore
        }
    }

    private boolean hasJavaInKotlinDir() {
        Path kotlinDir = kotlinSourceDir();
        if (!Files.isDirectory(kotlinDir)) return false;
        try (var stream = Files.walk(kotlinDir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private void generateSisuIndex(Path sourceDir, Path classesDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) return;
        List<String> namedClasses = new ArrayList<>();
        try (var stream = Files.walk(sourceDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (content.contains("@Named")) {
                                String pkg = "";
                                for (String line : content.split("\n")) {
                                    String trimmed = line.trim();
                                    if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                                        pkg = trimmed.substring(8, trimmed.length() - 1).trim();
                                        break;
                                    }
                                }
                                String fileName = p.getFileName().toString();
                                String className = fileName.substring(0, fileName.length() - 5);
                                String fqcn = pkg.isEmpty() ? className : pkg + "." + className;
                                namedClasses.add(fqcn);
                            }
                        } catch (IOException e) {
                            // skip
                        }
                    });
        }
        if (!namedClasses.isEmpty()) {
            java.util.Collections.sort(namedClasses);
            Path sisuDir = classesDir.resolve("META-INF/sisu");
            Files.createDirectories(sisuDir);
            Files.writeString(sisuDir.resolve("javax.inject.Named"),
                    String.join("\n", namedClasses) + "\n");
        }
    }

    private boolean isUpToDate() {
        Path installedArtifact = installedArtifactPath();
        if (!Files.exists(installedArtifact)) return false;

        long artifactMtime;
        try {
            artifactMtime = Files.getLastModifiedTime(installedArtifact).toMillis();
        } catch (IOException e) {
            return false;
        }

        for (ModuleBuild dep : dependencies) {
            if (dep.didSucceed() && !dep.wasSkippedIncremental()) {
                return false;
            }
        }

        if ("pom".equals(packaging())) {
            try {
                return Files.getLastModifiedTime(pomFile()).toMillis() <= artifactMtime;
            } catch (IOException e) {
                return false;
            }
        }

        long newestInput = newestMtime(sourceDir());
        if (newestInput > artifactMtime) return false;

        if (hasKotlinSources()) {
            newestInput = newestMtime(kotlinSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        Path base = runtime.getProjectRoot().resolve(baseDir());
        for (String[] rd : resourceDirs()) {
            Path dir = base.resolve(rd[0]);
            if (Files.isDirectory(dir)) {
                newestInput = newestMtime(dir);
                if (newestInput > artifactMtime) return false;
            }
        }

        if (hasProtobufSources()) {
            newestInput = newestMtime(protoSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        if (hasAntlrSources()) {
            newestInput = newestMtime(antlrSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        try {
            if (Files.getLastModifiedTime(pomFile()).toMillis() > artifactMtime) return false;
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    private static long newestMtime(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var stream = Files.walk(dir)) {
            return stream.map(p -> {
                try {
                    return Files.readAttributes(p, BasicFileAttributes.class).lastModifiedTime().toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }).max(Long::compare).orElse(0L);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private Path installedArtifactPath() {
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        String ext = "pom".equals(packaging()) ? ".pom" : ".jar";
        return localRepo
                .resolve(groupId().replace('.', '/'))
                .resolve(artifactId())
                .resolve(version())
                .resolve(artifactId() + "-" + version() + ext);
    }

    private boolean hasCodeGenSourceFiles() {
        Path base = runtime.getProjectRoot().resolve(baseDir()).resolve("src/main");
        Path protoDir = base.resolve("proto");
        if (Files.isDirectory(protoDir)) {
            try (var stream = Files.walk(protoDir)) {
                if (stream.anyMatch(p -> p.getFileName().toString().endsWith(".proto"))) {
                    return true;
                }
            } catch (IOException e) {
                return true;
            }
        }
        Path avroDir = base.resolve("avro");
        if (Files.isDirectory(avroDir)) {
            try (var stream = Files.walk(avroDir)) {
                if (stream.anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".avsc") || name.endsWith(".avpr") || name.endsWith(".avdl");
                })) {
                    return true;
                }
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    public Path protoSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/proto");
    }

    public Path antlrSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/antlr4");
    }

    public Path generatedProtobufDir() {
        return targetDir().resolve("generated-sources/protobuf");
    }

    public Path generatedAntlrDir() {
        return targetDir().resolve("generated-sources/antlr4");
    }

    protected static Map<String, String> parseProps(String packed) {
        if (packed == null || packed.isEmpty()) return Map.of();
        Map<String, String> map = new HashMap<>();
        for (String line : packed.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return map;
    }

    static boolean evaluateSkip(String expression) {
        if (expression == null || expression.isEmpty()) return false;
        if (expression.startsWith("${") && expression.endsWith("}")) {
            String propName = expression.substring(2, expression.length() - 1);
            String value = System.getProperty(propName);
            return value != null && !"false".equalsIgnoreCase(value);
        }
        return "true".equalsIgnoreCase(expression);
    }
}

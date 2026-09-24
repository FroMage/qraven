package io.github.fromage.qraven.hardcoded.runtime;

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

    public record ShadeExecution(String id, boolean attached, String classifier,
            List<String> includeArtifacts, List<ShadeFilter> filters, String mainClass) {}
    public record ShadeFilter(String artifact, List<String> excludes) {}

    private static final String HOME = System.getProperty("user.home");

    protected final BuildRuntime runtime;
    private volatile CompletableFuture<Void> buildFuture;
    private final CompletableFuture<Void> mainBuildDone = new CompletableFuture<>();
    volatile boolean mainBuildSucceeded;
    private List<ModuleBuild> dependencies = List.of();
    private List<ModuleBuild> testDependencies = List.of();
    private List<ModuleBuild> testJarDependencies = List.of();
    private ProgressDisplay progress;
    private BuildStats stats;
    private ConcurrentHashMap<Long, Integer> threadIndices;
    private AtomicInteger threadIndexCounter;
    private int maxThreadIndex;
    private boolean incremental;
    private List<String> resolvedClasspath;
    private Set<String> resolvedOptionalClasspath;
    private List<String> resolvedAnnotationProcessorPaths;
    private volatile List<String> mainBuildClasspath;
    private volatile Set<String> mainBuildAdded;
    private volatile boolean mainBuildSkipFormat;

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
    public boolean hasTestJavaSources() { return false; }
    public boolean hasTestKotlinSources() { return false; }
    public boolean hasTestProtobufSources() { return false; }
    public boolean hasGenerateCodeTestsGoal() { return false; }
    public List<String> testCompileClasspath() { return List.of(); }
    public List<String> testModuleDependencyIds() { return List.of(); }
    public List<String> testJarModuleDependencyIds() { return List.of(); }
    public boolean hasAntlrSources() { return false; }
    public boolean antlrVisitor() { return false; }
    public List<String> kotlinCompilerPlugins() { return List.of(); }
    public List<String> kotlinPluginOptions() { return List.of(); }
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
    public abstract String pluginName();
    public abstract String pluginDescription();
    public List<ShadeExecution> shadeExecutions() { return List.of(); }

    public void setDependencies(List<ModuleBuild> dependencies) {
        this.dependencies = dependencies;
    }

    public List<ModuleBuild> getDependencies() {
        return dependencies;
    }

    public void setTestDependencies(List<ModuleBuild> testDependencies) {
        this.testDependencies = testDependencies;
    }

    public List<ModuleBuild> getTestDependencies() {
        return testDependencies;
    }

    public void setTestJarDependencies(List<ModuleBuild> testJarDependencies) {
        this.testJarDependencies = testJarDependencies;
    }

    public List<ModuleBuild> getTestJarDependencies() {
        return testJarDependencies;
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
        mainBuildSucceeded = true;
        skippedIncremental = true;
        mainBuildDone.complete(null);
        buildFuture = CompletableFuture.completedFuture(null);
    }

    public synchronized CompletableFuture<Void> buildAsync(ExecutorService executor) {
        if (buildFuture != null) {
            return buildFuture;
        }
        // Set buildFuture BEFORE recursing to prevent StackOverflow from dependency cycles
        // (Java synchronized is re-entrant, so without this, a cycle re-enters with buildFuture still null)
        CompletableFuture<Void> result = new CompletableFuture<>();
        buildFuture = result;

        CompletableFuture<?>[] depFutures = dependencies.stream()
                .map(dep -> {
                    dep.buildAsync(executor);
                    return dep.mainBuildDone;
                })
                .toArray(CompletableFuture[]::new);
        buildScheduledAt = System.currentTimeMillis();
        CompletableFuture<Void> mainBuild = CompletableFuture.allOf(depFutures)
                .handleAsync((v, ex) -> { doBuild(); return null; }, executor);
        if (!testDependencies.isEmpty() || !testJarDependencies.isEmpty()) {
            List<CompletableFuture<?>> testWaits = new ArrayList<>();
            testWaits.add(mainBuild);
            for (ModuleBuild td : testDependencies) {
                testWaits.add(td.mainBuildDone);
                td.buildAsync(executor);
            }
            for (ModuleBuild tjd : testJarDependencies) {
                CompletableFuture<Void> tjdFuture = tjd.buildAsync(executor);
                testWaits.add(tjdFuture);
            }
            CompletableFuture.allOf(testWaits.toArray(CompletableFuture[]::new))
                    .handleAsync((v, ex) -> { doTestCompilation(); return null; }, executor)
                    .whenComplete((v, ex) -> {
                        if (ex != null) result.completeExceptionally(ex);
                        else result.complete(null);
                    });
        } else {
            mainBuild.whenComplete((v, ex) -> {
                if (ex != null) result.completeExceptionally(ex);
                else result.complete(null);
            });
        }
        return result;
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
            if (!dep.mainBuildSucceeded) {
                failedDeps.add(dep.artifactId());
            }
        }

        int threadIdx = getThreadIndex();

        if (!failedDeps.isEmpty()) {
            failureMessage = "[" + artifactId() + "] SKIPPED: dependency failed: " + String.join(", ", failedDeps);
            if (progress != null) {
                progress.moduleStarted(threadIdx, artifactId(), "skipped", 0);
                progress.moduleCompleted(threadIdx, false);
            }
            mainBuildDone.complete(null);
            return;
        }

        try {
            if ("pom".equals(packaging())) {
                if (incremental && isUpToDate()) {
                    skippedIncremental = true;
                    buildSucceeded = true;
                    mainBuildSucceeded = true;
                    mainBuildDone.complete(null);
                    if (progress != null) {
                        progress.moduleStarted(threadIdx, artifactId(), "up-to-date", 0);
                        progress.moduleCompleted(threadIdx, true);
                    }
                    return;
                }
                if (progress != null) progress.moduleStarted(threadIdx, artifactId(), "pom", 0);
                long t = System.currentTimeMillis();
                runtime.install(null, pomFile(), groupId(), artifactId(), version(), packaging());
                mainBuildSucceeded = true;
                mainBuildDone.complete(null);
                recordPhase("install", t);
            } else {
                if (incremental && isUpToDate()) {
                    skippedIncremental = true;
                    buildSucceeded = true;
                    mainBuildSucceeded = true;
                    mainBuildDone.complete(null);
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
                    generatedSourcesDir = QuarkusBuildHelper.generateCode(this, dependencies);
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
                            kotlinCompilerPlugins(), kotlinPluginOptions(),
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

                if ("maven-plugin".equals(packaging())) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "plugin-descriptor", 0);
                    t = System.currentTimeMillis();
                    List<String> descriptorCp = new ArrayList<>(resolvedClasspath());
                    addReactorJars(this, descriptorCp, new HashSet<>());
                    MavenPluginDescriptorGenerator.generate(
                            classesDir(), groupId(), artifactId(), version(),
                            pluginName(), pluginDescription(), descriptorCp);
                    recordPhase("plugin-descriptor", t);
                }

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jar", 0);
                t = System.currentTimeMillis();
                Files.createDirectories(classesDir());
                runtime.createJar(classesDir(), jarFile(), manifestEntries());
                recordPhase("jar", t);

                List<ShadeExecution> shadeExecs = shadeExecutions();
                if (!shadeExecs.isEmpty()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "shade", 0);
                    t = System.currentTimeMillis();
                    for (ShadeExecution shade : shadeExecs) {
                        executeShade(shade, fullClasspath);
                    }
                    recordPhase("shade", t);
                }

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "install", 0);
                t = System.currentTimeMillis();
                runtime.install(jarFile(), pomFile(), groupId(), artifactId(), version(), packaging());
                for (ShadeExecution shade : shadeExecs) {
                    if (shade.attached() && shade.classifier() != null) {
                        Path classifiedJar = targetDir().resolve(
                                artifactId() + "-" + version() + "-" + shade.classifier() + ".jar");
                        if (Files.exists(classifiedJar)) {
                            runtime.installClassified(classifiedJar, groupId(), artifactId(),
                                    version(), shade.classifier());
                        }
                    }
                }
                recordPhase("install", t);

                mainBuildSucceeded = true;
                mainBuildDone.complete(null);

                if (!testDependencies.isEmpty() || !testJarDependencies.isEmpty()) {
                    mainBuildClasspath = fullClasspath;
                    mainBuildAdded = added;
                    mainBuildSkipFormat = skipFormat;
                } else if ((hasTestJavaSources() || hasTestKotlinSources()
                        || hasTestProtobufSources() || hasGenerateCodeTestsGoal())
                        && !evaluateSkip("${maven.test.skip}")) {
                    List<String> testCp = new ArrayList<>(fullClasspath);
                    testCp.addAll(resolvePaths(testCompileClasspath()));
                    compileTests(testCp, added, skipFormat);
                }

                if (hasQuarkusBuildPlugin() && !evaluateSkip(quarkusBuildSkipWhen())) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "quarkus-build", 0);
                    t = System.currentTimeMillis();
                    QuarkusBuildHelper.run(this, dependencies);
                    recordPhase("quarkus-build", t);
                }
            }

            if (testDependencies.isEmpty() && testJarDependencies.isEmpty()) {
                buildSucceeded = true;
            }
            long totalElapsed = System.currentTimeMillis() - start;
            if (totalElapsed > 500) {
                System.err.println("[timing] [" + artifactId() + "] doBuild total: " + totalElapsed + "ms");
            }
            if (testDependencies.isEmpty() && testJarDependencies.isEmpty() && progress != null) {
                progress.moduleCompleted(threadIdx, true);
            }
        } catch (Throwable e) {
            mainBuildSucceeded = false;
            mainBuildDone.complete(null);
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
            if (root instanceof NoClassDefFoundError || root instanceof ClassNotFoundException
                    || root instanceof NoSuchMethodError) {
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

    private void doTestCompilation() {
        if (!mainBuildSucceeded) {
            return;
        }
        for (ModuleBuild testDep : testDependencies) {
            if (!testDep.mainBuildSucceeded) {
                failureMessage = "[" + artifactId() + "] FAILED: test dependency " + testDep.artifactId() + " failed";
                if (progress != null) {
                    progress.moduleCompleted(getThreadIndex(), false);
                }
                return;
            }
        }
        for (ModuleBuild testJarDep : testJarDependencies) {
            if (!testJarDep.buildSucceeded) {
                failureMessage = "[" + artifactId() + "] FAILED: test-jar dependency " + testJarDep.artifactId() + " failed";
                if (progress != null) {
                    progress.moduleCompleted(getThreadIndex(), false);
                }
                return;
            }
        }
        try {
            if ((hasTestJavaSources() || hasTestKotlinSources()
                    || hasTestProtobufSources() || hasGenerateCodeTestsGoal())
                    && !evaluateSkip("${maven.test.skip}")) {
                List<String> fullClasspath = mainBuildClasspath;
                Set<String> added = mainBuildAdded;
                List<String> testCp = new ArrayList<>(fullClasspath);
                testCp.addAll(resolvePaths(testCompileClasspath()));
                Set<String> testVisited = new HashSet<>(added);
                for (ModuleBuild testDep : testDependencies) {
                    if (testVisited.add(testDep.artifactId())) {
                        if (testDep.mainBuildSucceeded && !"pom".equals(testDep.packaging())) {
                            Path jar = testDep.jarFile();
                            if (Files.exists(jar)) {
                                testCp.add(jar.toString());
                            } else {
                                Path installed = testDep.installedArtifactPath();
                                if (Files.exists(installed)) {
                                    testCp.add(installed.toString());
                                }
                            }
                        }
                        if (testDep.mainBuildSucceeded) {
                            for (String cp : testDep.resolvedClasspath()) {
                                if (!testCp.contains(cp)) {
                                    testCp.add(cp);
                                }
                            }
                        }
                        addReactorJars(testDep, testCp, testVisited);
                    }
                }
                for (ModuleBuild testJarDep : testJarDependencies) {
                    Path testClassesDir = testJarDep.testClassesDir();
                    if (Files.exists(testClassesDir)) {
                        String tcd = testClassesDir.toString();
                        if (!testCp.contains(tcd)) {
                            testCp.add(tcd);
                        }
                    }
                }
                compileTests(testCp, added, mainBuildSkipFormat);
            }
            buildSucceeded = true;
            if (progress != null) {
                int threadIdx = getThreadIndex();
                progress.moduleCompleted(threadIdx, true);
            }
        } catch (Throwable e) {
            StringBuilder msg = new StringBuilder();
            msg.append("[").append(artifactId()).append("] FAILED (test compilation): ").append(e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                msg.append("\n  Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
                cause = cause.getCause();
            }
            failureMessage = msg.toString();
            if (progress != null) {
                progress.moduleCompleted(getThreadIndex(), false);
            }
            throw new RuntimeException("Test compilation failed for " + artifactId(), e);
        }
    }

    private void compileTests(List<String> testCp, Set<String> added, boolean skipFormat) throws Exception {
        int threadIdx = getThreadIndex();
        long t;
        testCp.add(classesDir().toString());

        List<Path> testExtraDirs = new ArrayList<>();

        Path generatedTestProtoDir = null;
        if (hasTestProtobufSources()) {
            if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "test-protobuf", 0);
            t = System.currentTimeMillis();
            generatedTestProtoDir = generatedTestProtobufDir();
            runtime.compileProtobuf(testProtoSourceDir(), generatedTestProtoDir,
                    protobufUsesGrpc(), protobufUsesMutiny(), testCp);
            Path javaDir = generatedTestProtoDir.resolve("java");
            if (Files.isDirectory(javaDir)) testExtraDirs.add(javaDir);
            Path grpcDir = generatedTestProtoDir.resolve("grpc-java");
            if (Files.isDirectory(grpcDir)) testExtraDirs.add(grpcDir);
            Path quarkusDir = generatedTestProtoDir.resolve("quarkus-grpc");
            if (Files.isDirectory(quarkusDir)) testExtraDirs.add(quarkusDir);
            recordPhase("test-protobuf", t);
        }

        Path generatedTestSourcesDir = null;
        if (hasGenerateCodeTestsGoal() && hasCodeGenProviders()
                && !evaluateSkip(generateCodeSkipWhen())
                && hasTestCodeGenSourceFiles()) {
            if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "generate-code-tests", 0);
            t = System.currentTimeMillis();
            generatedTestSourcesDir = QuarkusBuildHelper.generateCodeTests(this, dependencies);
            if (generatedTestSourcesDir != null && Files.isDirectory(generatedTestSourcesDir)) {
                addGeneratedSourceDirs(generatedTestSourcesDir, testExtraDirs);
            }
            recordPhase("generate-code-tests", t);
        }

        if (hasTestKotlinSources()) {
            if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "test-kotlin", 0);
            t = System.currentTimeMillis();
            runtime.compileKotlin(testKotlinSourceDir(), testSourceDir(), testClassesDir(), testCp,
                    kotlinCompilerPlugins(), kotlinPluginOptions(),
                    testExtraDirs.toArray(new Path[0]));
            testCp.add(0, testClassesDir().toString());
            recordPhase("test-kotlin", t);
        }

        if (hasTestJavaSources() && !skipFormat) {
            if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "test-format", 0);
            t = System.currentTimeMillis();
            CodeStyleHelper codeStyle = runtime.getCodeStyleHelper();
            codeStyle.formatJavaFiles(testSourceDir());
            codeStyle.sortImports(testSourceDir());
            recordPhase("test-format", t);
        }

        if (hasTestJavaSources() || !testExtraDirs.isEmpty()) {
            List<String> testApPaths = resolvedAnnotationProcessorPaths();
            String testCompilePhase = testApPaths.isEmpty() ? "test-compile" : "test-compile+apt";
            if (progress != null) progress.phaseChanged(threadIdx, artifactId(), testCompilePhase, 0);
            t = System.currentTimeMillis();
            runtime.compile(testSourceDir(), testClassesDir(), testCp,
                    testApPaths, isApCacheable(), compilerArgs(),
                    testExtraDirs.toArray(new Path[0]));
            recordPhase(testCompilePhase, t);
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
            if (dep.mainBuildSucceeded && !"pom".equals(dep.packaging())) {
                Path jar = dep.jarFile();
                if (Files.exists(jar)) {
                    classpath.add(jar.toString());
                } else {
                    Path installed = dep.installedArtifactPath();
                    if (Files.exists(installed)) {
                        classpath.add(installed.toString());
                    }
                }
            }
            if (dep.mainBuildSucceeded) {
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

    public Path testSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/test/java");
    }

    public Path testKotlinSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/test/kotlin");
    }

    public Path testClassesDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("target/test-classes");
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
            if (dep.mainBuildSucceeded && !dep.wasSkippedIncremental()) {
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

        if (hasTestProtobufSources() && !evaluateSkip("${maven.test.skip}")) {
            newestInput = newestMtime(testProtoSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        if (hasTestJavaSources() && !evaluateSkip("${maven.test.skip}")) {
            newestInput = newestMtime(testSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        if (hasTestKotlinSources() && !evaluateSkip("${maven.test.skip}")) {
            newestInput = newestMtime(testKotlinSourceDir());
            if (newestInput > artifactMtime) return false;
        }

        try {
            if (Files.getLastModifiedTime(pomFile()).toMillis() > artifactMtime) return false;
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    boolean hasChangedSources() {
        Path installedArtifact = installedArtifactPath();
        if (!Files.exists(installedArtifact)) return true;
        try {
            long artifactMtime = Files.getLastModifiedTime(installedArtifact).toMillis();
            if (newestMtime(sourceDir()) > artifactMtime) return true;
            if (hasKotlinSources() && newestMtime(kotlinSourceDir()) > artifactMtime) return true;
            if (hasTestJavaSources() && newestMtime(testSourceDir()) > artifactMtime) return true;
            if (hasTestKotlinSources() && newestMtime(testKotlinSourceDir()) > artifactMtime) return true;
            if (hasTestProtobufSources() && newestMtime(testProtoSourceDir()) > artifactMtime) return true;
            if (Files.getLastModifiedTime(pomFile()).toMillis() > artifactMtime) return true;
        } catch (IOException e) {
            return true;
        }
        return false;
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

    Path installedArtifactPath() {
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

    private boolean hasTestCodeGenSourceFiles() {
        Path base = runtime.getProjectRoot().resolve(baseDir()).resolve("src/test");
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

    public Path testProtoSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/test/proto");
    }

    public Path antlrSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/antlr4");
    }

    public Path generatedProtobufDir() {
        return targetDir().resolve("generated-sources/protobuf");
    }

    public Path generatedTestProtobufDir() {
        return targetDir().resolve("generated-test-sources/protobuf");
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

    private void executeShade(ShadeExecution shade, List<String> fullClasspath) throws IOException {
        Path baseJar = jarFile();
        Path outputJar;
        if (shade.attached() && shade.classifier() != null) {
            outputJar = targetDir().resolve(
                    artifactId() + "-" + version() + "-" + shade.classifier() + ".jar");
        } else {
            outputJar = baseJar;
        }

        List<Path> jarsToMerge = new ArrayList<>();
        for (String cp : fullClasspath) {
            Path jar = Path.of(cp);
            if (!Files.exists(jar) || Files.isDirectory(jar)) continue;
            if (shade.includeArtifacts().isEmpty()) {
                jarsToMerge.add(jar);
            } else {
                String ga = extractGA(cp);
                if (ga != null && shade.includeArtifacts().contains(ga)) {
                    jarsToMerge.add(jar);
                }
            }
        }

        Map<String, List<String>> filterMap = new HashMap<>();
        for (ShadeFilter filter : shade.filters()) {
            filterMap.put(filter.artifact(), filter.excludes());
        }

        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        if (shade.mainClass() != null) {
            manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, shade.mainClass());
        }

        Path tmpJar = outputJar.resolveSibling(outputJar.getFileName() + ".shade.tmp");
        Set<String> written = new HashSet<>();
        written.add("META-INF/MANIFEST.MF");

        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(tmpJar), manifest)) {
            copyShadeEntries(baseJar, out, written, null);
            for (Path jar : jarsToMerge) {
                String ga = extractGA(jar.toString());
                List<String> excludes = shadeExcludes(filterMap, ga);
                copyShadeEntries(jar, out, written, excludes);
            }
        }

        Files.move(tmpJar, outputJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyShadeEntries(Path jarPath, java.util.jar.JarOutputStream out,
            Set<String> written, List<String> excludes) throws IOException {
        try (var in = new java.util.jar.JarInputStream(Files.newInputStream(jarPath))) {
            java.util.jar.JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                if (!written.add(name)) continue;
                if (excludes != null && matchesShadeExclude(name, excludes)) continue;
                out.putNextEntry(new java.util.jar.JarEntry(name));
                in.transferTo(out);
                out.closeEntry();
            }
        }
    }

    private static List<String> shadeExcludes(Map<String, List<String>> filterMap, String ga) {
        List<String> excludes = new ArrayList<>();
        if (ga != null) {
            List<String> specific = filterMap.get(ga);
            if (specific != null) excludes.addAll(specific);
        }
        List<String> wildcard = filterMap.get("*:*");
        if (wildcard != null) excludes.addAll(wildcard);
        return excludes.isEmpty() ? null : excludes;
    }

    private static boolean matchesShadeExclude(String name, List<String> excludes) {
        for (String pattern : excludes) {
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                if (name.startsWith(prefix + "/") || name.equals(prefix)) return true;
            } else if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (name.startsWith(prefix + "/") && !name.substring(prefix.length() + 1).contains("/")) return true;
            } else if (name.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String extractGA(String jarPath) {
        String home = System.getProperty("user.home");
        String m2 = home + "/.m2/repository/";
        if (!jarPath.startsWith(m2)) return null;
        String relative = jarPath.substring(m2.length());
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String beforeFile = relative.substring(0, lastSlash);
        int versionSlash = beforeFile.lastIndexOf('/');
        if (versionSlash < 0) return null;
        String beforeVersion = beforeFile.substring(0, versionSlash);
        int artifactSlash = beforeVersion.lastIndexOf('/');
        if (artifactSlash < 0) return null;
        String artifactId = beforeVersion.substring(artifactSlash + 1);
        String groupId = beforeVersion.substring(0, artifactSlash).replace('/', '.');
        return groupId + ":" + artifactId;
    }
}

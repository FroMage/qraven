package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public abstract String generateCodeSkipWhen();
    public abstract Map<String, String> quarkusBuildProperties();
    public abstract List<String> deploymentClasspath();
    public abstract List<String> runtimeExtensionArtifacts();
    public abstract Map<String, String> extensionDevProperties();

    public void setDependencies(List<ModuleBuild> dependencies) {
        this.dependencies = dependencies;
    }

    public List<ModuleBuild> getDependencies() {
        return dependencies;
    }

    public void setProgress(ProgressDisplay progress, BuildStats stats,
                           ConcurrentHashMap<Long, Integer> threadIndices,
                           AtomicInteger threadIndexCounter, int maxThreadIndex) {
        this.progress = progress;
        this.stats = stats;
        this.threadIndices = threadIndices;
        this.threadIndexCounter = threadIndexCounter;
        this.maxThreadIndex = maxThreadIndex;
    }

    private volatile boolean buildSucceeded = false;
    private volatile String failureMessage;

    public boolean didSucceed() {
        return buildSucceeded;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public synchronized CompletableFuture<Void> buildAsync(ExecutorService executor) {
        if (buildFuture != null) {
            return buildFuture;
        }
        CompletableFuture<?>[] depFutures = dependencies.stream()
                .map(dep -> dep.buildAsync(executor))
                .toArray(CompletableFuture[]::new);
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
                if (progress != null) progress.moduleStarted(threadIdx, artifactId(), "pom", 0);
                long t = System.currentTimeMillis();
                runtime.install(null, pomFile(), groupId(), artifactId(), version(), packaging());
                recordPhase("install", t);
            } else {
                runtime.clean(targetDir());

                List<String> fullClasspath = new ArrayList<>(resolvedClasspath());
                Set<String> added = new HashSet<>();
                addReactorJars(this, fullClasspath, added);

                boolean skipFormat = evaluateSkip("${no-format}");

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
                            deploymentClasspath());
                    recordPhase("ext-descriptor", t);
                }

                Path generatedSourcesDir = null;
                if (hasGenerateCodeGoal() && !evaluateSkip(generateCodeSkipWhen())) {
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
                    }
                    recordPhase("generate-code", t);
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
                    int sourceCount = countSources();
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "compile", sourceCount);
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
                            resolvedAnnotationProcessorPaths(), compilerArgs(),
                            extraDirs.toArray(new Path[0]));
                    recordPhase("compile", t);
                }

                if (needsJandexIndex()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jandex", 0);
                    t = System.currentTimeMillis();
                    runtime.generateJandexIndex(classesDir());
                    recordPhase("jandex", t);
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

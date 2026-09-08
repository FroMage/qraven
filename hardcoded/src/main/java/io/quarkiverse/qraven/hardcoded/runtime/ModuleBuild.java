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

    public void setProgress(ProgressDisplay progress, ConcurrentHashMap<Long, Integer> threadIndices,
                           AtomicInteger threadIndexCounter, int maxThreadIndex) {
        this.progress = progress;
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
                runtime.install(null, pomFile(), groupId(), artifactId(), version(), packaging());
            } else {
                runtime.clean(targetDir());

                List<String> fullClasspath = new ArrayList<>(resolvedClasspath());
                Set<String> added = new HashSet<>();
                addReactorJars(this, fullClasspath, added);

                if (progress != null) progress.moduleStarted(threadIdx, artifactId(), "resources", 0);
                for (String[] rd : resourceDirs()) {
                    Path dir = runtime.getProjectRoot().resolve(baseDir()).resolve(rd[0]);
                    boolean filtering = "true".equals(rd[1]);
                    if (filtering) {
                        runtime.copyResourcesFiltered(dir, classesDir(), filterProperties());
                    } else {
                        runtime.copyResources(dir, classesDir());
                    }
                }

                if (hasExtensionPlugin()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "ext-descriptor", 0);
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
                            extensionRequiresCapabilities());
                }

                Path generatedProtoDir = null;
                if (hasProtobufSources()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "protobuf", 0);
                    generatedProtoDir = generatedProtobufDir();
                    runtime.compileProtobuf(protoSourceDir(), generatedProtoDir,
                            protobufUsesGrpc(), protobufUsesMutiny(), fullClasspath);
                }

                if (hasKotlinSources()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "kotlin", 0);
                    runtime.compileKotlin(kotlinSourceDir(), sourceDir(), classesDir(), fullClasspath);
                    fullClasspath.add(0, classesDir().toString());
                }

                if (hasJavaSources() || generatedProtoDir != null) {
                    int sourceCount = countSources();
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "compile", sourceCount);
                    if (generatedProtoDir != null) {
                        Path javaOut = generatedProtoDir.resolve("java");
                        Path grpcOut = generatedProtoDir.resolve("grpc-java");
                        Path mutinyOut = generatedProtoDir.resolve("quarkus-grpc");
                        runtime.compile(sourceDir(), classesDir(), fullClasspath,
                                resolvedAnnotationProcessorPaths(), compilerArgs(),
                                javaOut, grpcOut, mutinyOut);
                    } else {
                        runtime.compile(sourceDir(), classesDir(), fullClasspath,
                                resolvedAnnotationProcessorPaths(), compilerArgs());
                    }
                }

                if (needsJandexIndex()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jandex", 0);
                    runtime.generateJandexIndex(classesDir());
                }

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "jar", 0);
                runtime.createJar(classesDir(), jarFile(), manifestEntries());

                if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "install", 0);
                runtime.install(jarFile(), pomFile(), groupId(), artifactId(), version(), packaging());

                if (hasQuarkusBuildPlugin()) {
                    if (progress != null) progress.phaseChanged(threadIdx, artifactId(), "quarkus-build", 0);
                    QuarkusBuildHelper.run(this, dependencies);
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
            failureMessage = msg.toString();
            if (progress != null) {
                progress.moduleCompleted(threadIdx, false);
            }
            throw new RuntimeException("Build failed for " + artifactId(), e);
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

    public Path protoSourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/proto");
    }

    public Path generatedProtobufDir() {
        return targetDir().resolve("generated-sources/protobuf");
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
}

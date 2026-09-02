package io.quarkiverse.qraven.hardcoded.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public abstract class ModuleBuild {

    protected final BuildRuntime runtime;
    private volatile CompletableFuture<Void> buildFuture;
    private List<ModuleBuild> dependencies = List.of();

    protected ModuleBuild(BuildRuntime runtime) {
        this.runtime = runtime;
    }

    public abstract String groupId();
    public abstract String artifactId();
    public abstract String version();
    public abstract String packaging();
    public abstract Path baseDir();
    public abstract boolean hasJavaSources();
    public abstract boolean hasResources();
    public abstract List<String> compileClasspath();
    public abstract List<String> annotationProcessorPaths();
    public abstract List<String> compilerArgs();
    public abstract List<String> moduleDependencyIds();

    public void setDependencies(List<ModuleBuild> dependencies) {
        this.dependencies = dependencies;
    }

    public List<ModuleBuild> getDependencies() {
        return dependencies;
    }

    public synchronized CompletableFuture<Void> buildAsync(ExecutorService executor) {
        if (buildFuture != null) {
            return buildFuture;
        }
        CompletableFuture<?>[] depFutures = dependencies.stream()
                .map(dep -> dep.buildAsync(executor))
                .toArray(CompletableFuture[]::new);
        buildFuture = CompletableFuture.allOf(depFutures)
                .thenRunAsync(this::doBuild, executor);
        return buildFuture;
    }

    protected void doBuild() {
        long start = System.currentTimeMillis();
        System.out.println("[" + artifactId() + "] Building...");

        try {
            if ("pom".equals(packaging())) {
                runtime.install(null, pomFile(), groupId(), artifactId(), version(), packaging());
            } else {
                runtime.clean(targetDir());

                List<String> fullClasspath = new ArrayList<>(compileClasspath());
                Set<String> added = new HashSet<>();
                addReactorJars(this, fullClasspath, added);

                if (hasResources()) {
                    runtime.copyResources(resourceDir(), classesDir());
                }

                if (hasJavaSources()) {
                    runtime.compile(sourceDir(), classesDir(), fullClasspath,
                            annotationProcessorPaths(), compilerArgs());
                }

                runtime.createJar(classesDir(), jarFile());
                runtime.install(jarFile(), pomFile(), groupId(), artifactId(), version(), packaging());
            }

            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[" + artifactId() + "] Done in " + elapsed + "ms");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.err.println("[" + artifactId() + "] FAILED after " + elapsed + "ms: " + e.getMessage());
            throw new RuntimeException("Build failed for " + artifactId(), e);
        }
    }

    private void addReactorJars(ModuleBuild module, List<String> classpath, Set<String> visited) {
        for (ModuleBuild dep : module.getDependencies()) {
            if (!visited.add(dep.artifactId())) {
                continue;
            }
            if (!"pom".equals(dep.packaging())) {
                classpath.add(dep.jarFile().toString());
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

    public Path resourceDir() {
        return runtime.getProjectRoot().resolve(baseDir()).resolve("src/main/resources");
    }
}

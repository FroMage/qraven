package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class BuildOrchestrator {

    private final int threadCount;

    public BuildOrchestrator(int threadCount) {
        this.threadCount = threadCount;
    }

    public void buildAll(List<ModuleBuild> modules, String projectsFilter, boolean alsoMake) {
        buildAll(modules, projectsFilter, alsoMake, false, false);
    }

    public void buildAll(List<ModuleBuild> modules, String projectsFilter, boolean alsoMake, boolean incremental) {
        buildAll(modules, projectsFilter, alsoMake, incremental, false);
    }

    public void buildAll(List<ModuleBuild> modules, String projectsFilter, boolean alsoMake,
                          boolean incremental, boolean noKotlin) {
        Map<String, ModuleBuild> byId = new LinkedHashMap<>();
        for (ModuleBuild m : modules) {
            byId.put(m.artifactId(), m);
        }

        Map<String, String> installPathToArtifactId = new HashMap<>();
        for (ModuleBuild m : modules) {
            if (!"pom".equals(m.packaging())) {
                String installPath = "$HOME/.m2/repository/"
                        + m.groupId().replace('.', '/') + "/"
                        + m.artifactId() + "/"
                        + m.version() + "/"
                        + m.artifactId() + "-" + m.version() + ".jar";
                installPathToArtifactId.put(installPath, m.artifactId());
            }
        }

        for (ModuleBuild m : modules) {
            List<String> allDepIds = new ArrayList<>(m.moduleDependencyIds());
            for (String optId : m.optionalModuleDependencyIds()) {
                if (byId.containsKey(optId) && !allDepIds.contains(optId)) {
                    allDepIds.add(optId);
                }
            }
            if (m.hasExtensionPlugin()) {
                for (String path : m.deploymentClasspath()) {
                    String reactorId = installPathToArtifactId.get(path);
                    if (reactorId != null && !allDepIds.contains(reactorId)) {
                        allDepIds.add(reactorId);
                    }
                }
            }
            List<String> unresolved = allDepIds.stream()
                    .filter(id -> !byId.containsKey(id))
                    .toList();
            if (!unresolved.isEmpty()) {
                System.err.println("WARNING: " + m.artifactId()
                        + " has unresolved reactor dependencies: " + unresolved);
            }
            List<ModuleBuild> deps = allDepIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
            m.setDependencies(deps);

            List<ModuleBuild> testDeps = m.testModuleDependencyIds().stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(d -> !allDepIds.contains(d.artifactId()))
                    .toList();
            m.setTestDependencies(testDeps);
        }

        if (projectsFilter != null) {
            modules = filterProjects(modules, byId, projectsFilter, alsoMake);
            Set<String> included = new LinkedHashSet<>();
            for (ModuleBuild m : modules) {
                included.add(m.artifactId());
            }
            for (ModuleBuild m : byId.values()) {
                if (!included.contains(m.artifactId())) {
                    m.markPreBuilt();
                }
            }
        }

        if (noKotlin) {
            List<String> kotlinNames = new ArrayList<>();
            for (ModuleBuild m : byId.values()) {
                if (m.hasKotlinSources()) {
                    m.markPreBuilt();
                    kotlinNames.add(m.artifactId());
                }
            }
            if (!kotlinNames.isEmpty()) {
                modules = new ArrayList<>(modules.stream()
                        .filter(m -> !m.hasKotlinSources())
                        .toList());
                System.out.println("Skipping " + kotlinNames.size() + " Kotlin module(s): "
                        + String.join(", ", kotlinNames));
            }
        }

        Map<String, Integer> forwardReach = computeForwardReach(modules);
        Comparator<ModuleBuild> byPriority = Comparator.comparingInt(
                (ModuleBuild m) -> forwardReach.getOrDefault(m.artifactId(), 0)).reversed();
        for (ModuleBuild m : modules) {
            List<ModuleBuild> sorted = new ArrayList<>(m.getDependencies());
            sorted.sort(byPriority);
            m.setDependencies(sorted);
        }
        modules.sort(byPriority);

        Set<String> allJars = new LinkedHashSet<>();
        for (ModuleBuild m : modules) {
            allJars.addAll(m.resolvedClasspath());
            allJars.addAll(m.resolvedAnnotationProcessorPaths());
        }

        List<String> missing = allJars.stream()
                .filter(jar -> !java.nio.file.Files.exists(java.nio.file.Path.of(jar)))
                .toList();
        if (!missing.isEmpty()) {
            System.err.println("ERROR: " + missing.size() + " dependency jar(s) not found:");
            missing.forEach(jar -> System.err.println("  " + jar));
            System.err.println("Run 'mvn dependency:go-offline' or check your ~/.m2/repository");
            System.exit(1);
        }

        Set<String> willRebuild = incremental ? computeRebuildSet(modules) : null;
        int rebuildCount = willRebuild != null ? willRebuild.size() : modules.size();

        if (!modules.isEmpty() && rebuildCount > 0) {
            boolean needsJavaWarmup = modules.stream()
                    .filter(m -> m.hasJavaSources() || m.hasTestJavaSources())
                    .anyMatch(m -> willRebuild == null || willRebuild.contains(m.artifactId()));
            if (needsJavaWarmup) {
                modules.get(0).runtime.warmupClasspath(allJars);
            }
            // Kotlin warmup MUST happen upfront, before parallel compilation starts.
            // Without it, parallel kotlinc threads run while the JIT hasn't compiled
            // the compiler hot paths yet, making compilation much slower.
            boolean needsKotlinWarmup = modules.stream()
                    .filter(m -> m.hasKotlinSources() || m.hasTestKotlinSources())
                    .anyMatch(m -> willRebuild == null || willRebuild.contains(m.artifactId()));
            if (needsKotlinWarmup) {
                modules.get(0).runtime.warmupKotlin();
            }
        }

        boolean noProgress = "true".equals(System.getProperty("no-progress"))
                || System.getProperties().containsKey("no-progress");
        ProgressDisplay progress = noProgress ? null : new ProgressDisplay(modules.size(), rebuildCount, threadCount);

        AtomicInteger threadIndexCounter = new AtomicInteger();
        ConcurrentHashMap<Long, Integer> threadIndices = new ConcurrentHashMap<>();

        long orchestratorStart = System.currentTimeMillis();
        StringBuilder buildMsg = new StringBuilder();
        buildMsg.append("Building ").append(modules.size()).append(" modules with ")
                .append(threadCount).append(" threads");
        if (incremental) {
            buildMsg.append(" (incremental, ").append(rebuildCount).append(" to rebuild)");
        }
        System.out.println(buildMsg);

        // Redirect System.out, System.err and JUL to build log so they don't break the progress bar.
        // ProgressDisplay already captured the original System.err at construction.
        Path logFile = modules.isEmpty() ? null
                : modules.get(0).runtime.getProjectRoot().resolve("target/qraven/build.log");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream buildLog = null;
        Logger julRoot = Logger.getLogger("");
        Handler[] originalJulHandlers = julRoot.getHandlers();
        Handler buildLogHandler = null;
        if (logFile != null) {
            try {
                Files.createDirectories(logFile.getParent());
                buildLog = new PrintStream(
                        Files.newOutputStream(logFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                        true);
                System.setOut(buildLog);
                System.setErr(buildLog);

                for (Handler h : originalJulHandlers) {
                    julRoot.removeHandler(h);
                }
                final PrintStream logOut = buildLog;
                buildLogHandler = new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        if (record != null && isLoggable(record)) {
                            logOut.println("[" + record.getLevel() + "] " + record.getLoggerName()
                                    + ": " + record.getMessage());
                        }
                    }

                    @Override
                    public void flush() {
                        logOut.flush();
                    }

                    @Override
                    public void close() {
                    }
                };
                julRoot.addHandler(buildLogHandler);
            } catch (IOException e) {
                buildLog = null;
            }
        }
        BuildStats stats = new BuildStats();
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (ModuleBuild m : modules) {
                m.setProgress(progress, stats, threadIndices, threadIndexCounter, threadCount, incremental);
            }
            CompletableFuture<?>[] allFutures = modules.stream()
                    .map(m -> m.buildAsync(executor))
                    .toArray(CompletableFuture[]::new);
            for (int i = 0; i < allFutures.length; i++) {
                try {
                    allFutures[i].join();
                } catch (Exception e) {
                    // handled below
                }
            }
        } finally {
            executor.shutdown();

            // Restore System.out, System.err and JUL before stopping progress
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (buildLogHandler != null) {
                julRoot.removeHandler(buildLogHandler);
            }
            for (Handler h : originalJulHandlers) {
                julRoot.addHandler(h);
            }

            if (!modules.isEmpty()) {
                long closeStart = System.currentTimeMillis();
                modules.get(0).runtime.close();
                long closeElapsed = System.currentTimeMillis() - closeStart;
                if (closeElapsed > 50) {
                    originalErr.println("[timing] runtime.close(): " + closeElapsed + "ms");
                }
            }
            if (progress != null) progress.stop();
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[timing] orchestrator wall clock: " + (System.currentTimeMillis() - orchestratorStart) + "ms");
        List<String> directFailures = new ArrayList<>();
        List<String> cascadeFailures = new ArrayList<>();
        int succeeded = 0;
        int skippedIncremental = 0;
        for (ModuleBuild m : modules) {
            if (m.didSucceed()) {
                succeeded++;
                if (m.wasSkippedIncremental()) {
                    skippedIncremental++;
                }
            } else {
                boolean hasFailed = m.getDependencies().stream().anyMatch(d -> !d.didSucceed())
                        || m.getTestDependencies().stream().anyMatch(d -> !d.didSucceed());
                if (hasFailed) {
                    cascadeFailures.add(m.artifactId());
                } else {
                    directFailures.add(m.artifactId());
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append("Build completed in ").append(elapsed).append("ms: ")
                .append(succeeded).append(" succeeded");
        if (skippedIncremental > 0) {
            summary.append(" (").append(skippedIncremental).append(" up-to-date)");
        }
        summary.append(", ").append(directFailures.size()).append(" failed, ")
                .append(cascadeFailures.size()).append(" skipped (cascade)");
        System.out.println(summary);
        String phaseSummary = stats.summary();
        if (!phaseSummary.isEmpty()) {
            System.out.println(phaseSummary);
        }
        if (!modules.isEmpty()) {
            System.out.println(modules.get(0).runtime.apCacheStats());
        }
        if (!directFailures.isEmpty()) {
            System.err.println("Direct failures: " + String.join(", ", directFailures));
            if (logFile != null) {
                try {
                    // Append failure summary to the build log (which already has JUL/stderr output)
                    try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(logFile,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                        pw.println();
                        pw.println("=== BUILD FAILURE SUMMARY ===");
                        pw.println("Build completed in " + elapsed + "ms: " + succeeded + " succeeded, "
                                + directFailures.size() + " failed, " + cascadeFailures.size()
                                + " skipped (cascade)");
                        pw.println();
                        pw.println("Direct failures: " + String.join(", ", directFailures));
                        pw.println();
                        for (ModuleBuild m : modules) {
                            if (m.getFailureMessage() != null) {
                                pw.println(m.getFailureMessage());
                            }
                        }
                        if (!cascadeFailures.isEmpty()) {
                            pw.println();
                            pw.println("Cascade failures (" + cascadeFailures.size() + "): "
                                    + String.join(", ", cascadeFailures));
                        }
                    }
                    System.err.println(directFailures.size() + " compilation error(s), see " + logFile
                            + " for details");
                } catch (IOException e) {
                    for (ModuleBuild m : modules) {
                        if (m.getFailureMessage() != null) {
                            System.err.println(m.getFailureMessage());
                        }
                    }
                }
            }
        }
        if (buildLog != null) {
            buildLog.close();
        }
        if (!cascadeFailures.isEmpty()) {
            System.err.println("Cascade failures (" + cascadeFailures.size() + "): "
                    + String.join(", ", cascadeFailures));
        }
    }

    private Map<String, Integer> computeForwardReach(List<ModuleBuild> modules) {
        Map<String, List<String>> dependents = new HashMap<>();
        for (ModuleBuild m : modules) {
            for (ModuleBuild dep : m.getDependencies()) {
                dependents.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>()).add(m.artifactId());
            }
            for (ModuleBuild dep : m.getTestDependencies()) {
                dependents.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>()).add(m.artifactId());
            }
        }
        Map<String, Integer> result = new HashMap<>();
        for (ModuleBuild m : modules) {
            computeForwardReachRecursive(m.artifactId(), dependents, result);
        }
        return result;
    }

    private int computeForwardReachRecursive(String id, Map<String, List<String>> dependents,
                                              Map<String, Integer> cache) {
        if (cache.containsKey(id)) return cache.get(id);
        cache.put(id, 0);
        int max = 0;
        for (String depId : dependents.getOrDefault(id, List.of())) {
            max = Math.max(max, 1 + computeForwardReachRecursive(depId, dependents, cache));
        }
        cache.put(id, max);
        return max;
    }

    private List<ModuleBuild> filterProjects(List<ModuleBuild> allModules,
                                              Map<String, ModuleBuild> byId, String filter,
                                              boolean alsoMake) {
        Set<String> selectors = new LinkedHashSet<>();
        for (String s : filter.split(",")) {
            selectors.add(s.trim());
        }

        Set<String> selected = new LinkedHashSet<>();
        for (ModuleBuild m : allModules) {
            for (String sel : selectors) {
                if (sel.contains(":")) {
                    if ((m.groupId() + ":" + m.artifactId()).equals(sel)) {
                        selected.add(m.artifactId());
                    }
                } else if (sel.equals(m.artifactId())) {
                    selected.add(m.artifactId());
                } else if (m.baseDir().toString().equals(sel)
                        || m.baseDir().toString().endsWith("/" + sel)) {
                    selected.add(m.artifactId());
                }
            }
        }

        Set<String> needed = new LinkedHashSet<>(selected);
        if (alsoMake) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String id : new ArrayList<>(needed)) {
                    ModuleBuild m = byId.get(id);
                    if (m == null) continue;
                    for (ModuleBuild dep : m.getDependencies()) {
                        if (needed.add(dep.artifactId())) {
                            changed = true;
                        }
                    }
                    for (ModuleBuild dep : m.getTestDependencies()) {
                        if (needed.add(dep.artifactId())) {
                            changed = true;
                        }
                    }
                }
            }
        }

        List<ModuleBuild> filtered = new ArrayList<>();
        for (ModuleBuild m : allModules) {
            if (needed.contains(m.artifactId())) {
                filtered.add(m);
            }
        }

        if (alsoMake) {
            System.out.println("Filtered to " + filtered.size() + " modules (" +
                    selected.size() + " selected + " + (filtered.size() - selected.size()) + " dependencies)");
        } else {
            System.out.println("Filtered to " + filtered.size() + " modules");
        }
        return filtered;
    }

    private static Set<String> computeRebuildSet(List<ModuleBuild> modules) {
        Map<String, List<ModuleBuild>> dependents = new HashMap<>();
        for (ModuleBuild m : modules) {
            for (ModuleBuild dep : m.getDependencies()) {
                dependents.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>()).add(m);
            }
            for (ModuleBuild dep : m.getTestDependencies()) {
                dependents.computeIfAbsent(dep.artifactId(), k -> new ArrayList<>()).add(m);
            }
        }
        Set<String> willRebuild = new LinkedHashSet<>();
        java.util.LinkedList<String> queue = new java.util.LinkedList<>();
        for (ModuleBuild m : modules) {
            if (m.hasChangedSources()) {
                willRebuild.add(m.artifactId());
                queue.add(m.artifactId());
            }
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (ModuleBuild dep : dependents.getOrDefault(id, List.of())) {
                if (willRebuild.add(dep.artifactId())) {
                    queue.add(dep.artifactId());
                }
            }
        }
        return willRebuild;
    }
}

package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
        Map<String, ModuleBuild> byId = new LinkedHashMap<>();
        for (ModuleBuild m : modules) {
            byId.put(m.artifactId(), m);
        }

        for (ModuleBuild m : modules) {
            List<ModuleBuild> deps = m.moduleDependencyIds().stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
            m.setDependencies(deps);
        }

        if (projectsFilter != null) {
            modules = filterProjects(modules, byId, projectsFilter, alsoMake);
        }

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

        if (!modules.isEmpty()) {
            modules.get(0).runtime.warmupClasspath(allJars);
            if (modules.stream().anyMatch(ModuleBuild::hasKotlinSources)) {
                modules.get(0).runtime.warmupKotlin();
            }
        }

        ProgressDisplay progress = new ProgressDisplay(modules.size(), threadCount);

        AtomicInteger threadIndexCounter = new AtomicInteger();
        ConcurrentHashMap<Long, Integer> threadIndices = new ConcurrentHashMap<>();

        System.out.println("Building " + modules.size() + " modules with " + threadCount + " threads");

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
                m.setProgress(progress, stats, threadIndices, threadIndexCounter, threadCount);
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
                modules.get(0).runtime.close();
            }
            progress.stop();
        }

        long elapsed = System.currentTimeMillis() - start;
        List<String> directFailures = new ArrayList<>();
        List<String> cascadeFailures = new ArrayList<>();
        int succeeded = 0;
        for (ModuleBuild m : modules) {
            if (m.didSucceed()) {
                succeeded++;
            } else {
                boolean hasFailed = m.getDependencies().stream().anyMatch(d -> !d.didSucceed());
                if (hasFailed) {
                    cascadeFailures.add(m.artifactId());
                } else {
                    directFailures.add(m.artifactId());
                }
            }
        }
        System.out.println("Build completed in " + elapsed + "ms: " + succeeded + " succeeded, "
                + directFailures.size() + " failed, " + cascadeFailures.size() + " skipped (cascade)");
        String phaseSummary = stats.summary();
        if (!phaseSummary.isEmpty()) {
            System.out.println(phaseSummary);
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
}

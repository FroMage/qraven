package io.quarkiverse.qraven.hardcoded.runtime;

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

public class BuildOrchestrator {

    private final int threadCount;

    public BuildOrchestrator(int threadCount) {
        this.threadCount = threadCount;
    }

    public void buildAll(List<ModuleBuild> modules) {
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
        }

        ProgressDisplay progress = new ProgressDisplay(modules.size(), threadCount);

        AtomicInteger threadIndexCounter = new AtomicInteger();
        ConcurrentHashMap<Long, Integer> threadIndices = new ConcurrentHashMap<>();

        System.out.println("Building " + modules.size() + " modules with " + threadCount + " threads");
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (ModuleBuild m : modules) {
                m.setProgress(progress, threadIndices, threadIndexCounter, threadCount);
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
        if (!directFailures.isEmpty()) {
            System.err.println("Direct failures: " + String.join(", ", directFailures));
            System.err.println();
            for (ModuleBuild m : modules) {
                if (m.getFailureMessage() != null) {
                    System.err.println(m.getFailureMessage());
                }
            }
        }
        if (!cascadeFailures.isEmpty()) {
            System.err.println("Cascade failures (" + cascadeFailures.size() + "): "
                    + String.join(", ", cascadeFailures));
        }
    }
}

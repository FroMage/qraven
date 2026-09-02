package io.quarkiverse.qraven.hardcoded.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            allJars.addAll(m.compileClasspath());
        }
        if (!modules.isEmpty()) {
            modules.get(0).runtime.warmupClasspath(allJars);
        }

        System.out.println("Building " + modules.size() + " modules with " + threadCount + " threads");
        long start = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CompletableFuture<?>[] allFutures = modules.stream()
                    .map(m -> m.buildAsync(executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(allFutures).join();
        } finally {
            executor.shutdown();
            if (!modules.isEmpty()) {
                modules.get(0).runtime.close();
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Build completed in " + elapsed + "ms");
    }
}

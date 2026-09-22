package io.github.fromage.qraven.hardcoded.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class BuildStats {

    private final ConcurrentHashMap<String, LongAdder> phaseTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> phaseCounts = new ConcurrentHashMap<>();

    public void record(String phase, long elapsedMs) {
        phaseTimes.computeIfAbsent(phase, k -> new LongAdder()).add(elapsedMs);
        phaseCounts.computeIfAbsent(phase, k -> new AtomicInteger()).incrementAndGet();
    }

    public String summary() {
        if (phaseTimes.isEmpty()) return "";

        List<String> phases = new ArrayList<>(phaseTimes.keySet());
        phases.sort(Comparator.comparingLong(
                (String p) -> phaseTimes.get(p).sum()).reversed());

        int maxNameLen = phases.stream().mapToInt(String::length).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("Phase summary:\n");
        for (String phase : phases) {
            long totalMs = phaseTimes.get(phase).sum();
            int count = phaseCounts.get(phase).get();
            sb.append(String.format("  %-" + maxNameLen + "s  %4d modules  %s%n",
                    phase, count, formatTime(totalMs)));
        }
        return sb.toString();
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}

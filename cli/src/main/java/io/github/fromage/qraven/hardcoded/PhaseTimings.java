package io.github.fromage.qraven.hardcoded;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sequential stopwatch for the generation phases: each {@link #lap} attributes the time since the previous
 * lap (or {@link #reset}) to a named phase, so every millisecond of generation lands in exactly one phase.
 */
final class PhaseTimings {

    private final Map<String, Long> millis = new LinkedHashMap<>();
    private long last = System.nanoTime();

    synchronized void reset() {
        last = System.nanoTime();
    }

    synchronized void lap(String phase) {
        long now = System.nanoTime();
        millis.merge(phase, (now - last) / 1_000_000, Long::sum);
        last = now;
    }

    synchronized String report() {
        long total = millis.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder sb = new StringBuilder("Generation phases:\n");
        millis.forEach((phase, ms) -> sb.append(String.format("  %-44s %7.1fs  %3d%%%n", phase, ms / 1000.0,
                total == 0 ? 0 : Math.round(100.0 * ms / total))));
        sb.append(String.format("  %-44s %7.1fs%n", "total", total / 1000.0));
        return sb.toString();
    }
}

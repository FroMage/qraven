package io.quarkiverse.qraven.hardcoded.runtime;

import java.io.PrintStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ProgressDisplay {

    private static final String ESC = "\u001b[";
    private static final String BOLD = ESC + "1m";
    private static final String DIM = ESC + "2m";
    private static final String CYAN = ESC + "36m";
    private static final String RED = ESC + "31m";
    private static final String RESET = ESC + "0m";
    private static final String CURSOR_UP = ESC + "A";
    private static final String ERASE_LINE = ESC + "2K";

    private final int totalModules;
    private final int threadCount;
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final ConcurrentHashMap<Integer, String> threadStatus = new ConcurrentHashMap<>();
    private final long startTime;
    private final PrintStream out;
    private volatile boolean stopped;
    private int lastLineCount;
    private final int termWidth;

    public ProgressDisplay(int totalModules, int threadCount) {
        this.totalModules = totalModules;
        this.threadCount = threadCount;
        this.startTime = System.currentTimeMillis();
        this.out = System.err;
        this.termWidth = detectTerminalWidth();
    }

    private static int detectTerminalWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                int w = Integer.parseInt(cols.trim());
                if (w > 0) return w;
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        try {
            Process p = new ProcessBuilder("tput", "cols")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (p.exitValue() == 0) {
                int w = Integer.parseInt(output);
                if (w > 0) return w;
            }
        } catch (Exception e) {
            // fall through
        }
        return 120;
    }

    public void moduleStarted(int threadIndex, String artifactId, String phase, int detail) {
        String status = switch (phase) {
            case "compile" -> artifactId + " │ compiling " + detail + " files";
            case "compile+apt" -> artifactId + " │ compiling " + detail + " files (apt)";
            case "resources" -> artifactId + " │ copying resources";
            case "jandex" -> artifactId + " │ indexing classes";
            case "jar" -> artifactId + " │ packaging jar";
            case "install" -> artifactId + " │ installing";
            case "pom" -> artifactId + " │ installing pom";
            default -> artifactId + " │ " + phase;
        };
        threadStatus.put(threadIndex, status);
        safeRender();
    }

    public void phaseChanged(int threadIndex, String artifactId, String phase, int detail) {
        moduleStarted(threadIndex, artifactId, phase, detail);
    }

    public void moduleCompleted(int threadIndex, boolean success) {
        if (success) {
            completed.incrementAndGet();
        } else {
            failed.incrementAndGet();
        }
        threadStatus.remove(threadIndex);
        safeRender();
    }

    public void stop() {
        stopped = true;
        synchronized (this) {
            clearLines();
            out.flush();
        }
    }

    private void safeRender() {
        try {
            render();
        } catch (Throwable t) {
            // never let display errors propagate into the build
        }
    }

    private synchronized void render() {
        if (stopped) return;

        clearLines();

        int done = completed.get();
        int fail = failed.get();
        int total = totalModules;
        int processed = done + fail;
        long elapsed = System.currentTimeMillis() - startTime;

        String elapsedStr = formatTime(elapsed);
        String eta = "";
        if (processed > 0 && processed < total) {
            long remaining = elapsed * (total - processed) / processed;
            eta = " ETA " + formatTime(remaining);
        }

        int barWidth = 40;
        int filled = total > 0 ? Math.min(barWidth, (int) ((long) processed * barWidth / total)) : 0;
        StringBuilder bar = new StringBuilder();
        bar.append(BOLD).append('[');
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) bar.append('█');
            else bar.append('░');
        }
        bar.append("] ");
        bar.append(processed).append('/').append(total);
        if (fail > 0) bar.append(" (").append(RED).append(fail).append(" failed").append(RESET).append(BOLD).append(')');
        bar.append(' ').append(elapsedStr).append(eta);
        bar.append(RESET);
        out.println(truncate(bar.toString()));

        int linesWritten = 1;
        for (int i = 0; i < threadCount; i++) {
            String status = threadStatus.get(i);
            String prefix = threadCount > 1 ? "  T" + i + " " : "  ";
            if (status == null) {
                out.println(truncate(prefix + DIM + "idle" + RESET));
            } else {
                out.println(truncate(prefix + CYAN + status + RESET));
            }
            linesWritten++;
        }
        lastLineCount = linesWritten;
    }

    public synchronized void adjustForStrayOutput(int extraLines) {
        lastLineCount += extraLines;
    }

    private void clearLines() {
        for (int i = 0; i < lastLineCount; i++) {
            out.print(CURSOR_UP + ERASE_LINE);
        }
    }

    private String truncate(String line) {
        int maxVisible = termWidth - 1;
        if (maxVisible <= 0) return line;
        int visible = 0;
        boolean inEscape = false;
        int cutIndex = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\u001b') {
                inEscape = true;
            } else if (inEscape) {
                if (c == 'm') inEscape = false;
            } else {
                visible++;
                if (visible >= maxVisible && cutIndex < 0) {
                    cutIndex = i + 1;
                }
            }
        }
        if (cutIndex < 0) return line;
        return line.substring(0, cutIndex) + RESET;
    }

    private static String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        return (secs / 60) + "m" + (secs % 60) + "s";
    }
}

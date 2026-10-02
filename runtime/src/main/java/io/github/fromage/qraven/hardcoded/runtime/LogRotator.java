package io.github.fromage.qraven.hardcoded.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps previous copies of a log file: {@code build.log} becomes {@code logs/build.1.log},
 * the previous {@code build.1.log} becomes {@code build.2.log}, and so on, up to
 * {@link #MAX_PREVIOUS} copies (1 is the most recent). The oldest one is dropped.
 * <p>
 * NOTE: a copy of this class lives in the CLI module (which does not depend on the runtime
 * module); keep both in sync.
 */
public final class LogRotator {

    public static final int MAX_PREVIOUS = 20;
    public static final String PREVIOUS_DIR = "logs";

    private LogRotator() {
    }

    /** Rotates {@code dir/<name>.log} into {@code dir/logs/}; does nothing if it does not exist. */
    public static void rotate(Path dir, String name) throws IOException {
        Path current = dir.resolve(name + ".log");
        if (!Files.exists(current)) {
            return;
        }
        Path logs = dir.resolve(PREVIOUS_DIR);
        Files.createDirectories(logs);
        Files.deleteIfExists(previous(logs, name, MAX_PREVIOUS));
        for (int i = MAX_PREVIOUS - 1; i >= 1; i--) {
            Path from = previous(logs, name, i);
            if (Files.exists(from)) {
                Files.move(from, previous(logs, name, i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(current, previous(logs, name, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path previous(Path logs, String name, int n) {
        return logs.resolve(name + "." + n + ".log");
    }
}

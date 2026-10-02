package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.LogRotator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LogRotatorTest {

    @Test
    void rotate_withoutExistingLog_doesNothing(@TempDir Path dir) throws Exception {
        LogRotator.rotate(dir, "build");
        assertThat(dir.resolve("logs")).doesNotExist();
    }

    @Test
    void rotate_shiftsPreviousLogs(@TempDir Path dir) throws Exception {
        for (String run : new String[] { "one", "two", "three" }) {
            LogRotator.rotate(dir, "build");
            Files.writeString(dir.resolve("build.log"), run);
        }
        assertThat(dir.resolve("build.log")).hasContent("three");
        assertThat(dir.resolve("logs/build.1.log")).hasContent("two");
        assertThat(dir.resolve("logs/build.2.log")).hasContent("one");
        assertThat(dir.resolve("logs/build.3.log")).doesNotExist();
    }

    @Test
    void rotate_keepsAtMostTwentyPreviousLogs(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 30; i++) {
            LogRotator.rotate(dir, "build");
            Files.writeString(dir.resolve("build.log"), "run" + i);
        }
        assertThat(dir.resolve("logs/build.1.log")).hasContent("run28");
        assertThat(dir.resolve("logs/build.20.log")).hasContent("run9");
        assertThat(dir.resolve("logs/build.21.log")).doesNotExist();
    }

    @Test
    void rotate_logsAreIndependentPerName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.log"), "b");
        Files.writeString(dir.resolve("bootstrap.log"), "s");
        LogRotator.rotate(dir, "build");
        assertThat(dir.resolve("bootstrap.log")).hasContent("s");
        assertThat(dir.resolve("logs/build.1.log")).hasContent("b");
        assertThat(dir.resolve("logs/bootstrap.1.log")).doesNotExist();
    }
}

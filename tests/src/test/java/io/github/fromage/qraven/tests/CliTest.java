package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.QravenCli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {

    static final Path QRAVEN_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    private String captureOutput(String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(capture);
        System.setOut(ps);
        System.setErr(ps);
        try {
            QravenCli.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return capture.toString();
    }

    @Test
    void help_showsUsage() throws Exception {
        String output = captureOutput("--help");
        assertThat(output).contains("qraven");
        assertThat(output).contains("--help");
        assertThat(output).contains("--projects");
        assertThat(output).contains("--also-make");
        assertThat(output).contains("--incremental");
    }

    @Test
    void version_showsVersion() throws Exception {
        String output = captureOutput("--version");
        assertThat(output).contains("qraven");
    }

    @Test
    void noBuild_generatesWithoutRunning() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "--no-build");
        assertThat(output).contains("build.jar");
        assertThat(output).contains("Qraven Build Tool");
    }

    @Test
    void noBuild_showsProjectDir() throws Exception {
        Path projectDir = QRAVEN_ROOT.resolve("test-projects/simple-jar");
        String output = captureOutput("-p", projectDir.toString(), "--no-build");
        assertThat(output).contains("Project:");
        assertThat(output).contains(projectDir.toString());
    }

    @Test
    void noBuild_showsThreadCount() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "-t", "4", "--no-build");
        assertThat(output).contains("Threads:  4");
    }

    @Test
    void forceGenerate_regenerates() throws Exception {
        String output = captureOutput(
                "-p", QRAVEN_ROOT.resolve("test-projects/simple-jar").toString(),
                "--force-generate", "--no-build");
        assertThat(output).contains("Regenerating: forced");
    }

    @Test
    void failure_suggestsReportingABug(@TempDir Path dir) throws Exception {
        // a module that does not compile; System.exit means a subprocess is needed
        Files.copy(QRAVEN_ROOT.resolve("test-projects/simple-jar/pom.xml"), dir.resolve("pom.xml"));
        Path src = Files.createDirectories(dir.resolve("src/main/java/com/test"));
        Files.writeString(src.resolve("Broken.java"), "package com.test; class Broken { this does not compile }");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QravenCli.class.getName(),
                "-p", dir.toString(), "-Dno-progress")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(output).isNotZero();
        assertThat(output).contains("qraven --report-bug");
    }

    @Test
    void success_doesNotSuggestReportingABug() throws Exception {
        assertThat(captureOutput("--help")).doesNotContain("should not have failed");
    }
}
